package com.aegis.runtime.service.conversation;

import com.aegis.core.common.tenant.TenantContextScope;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.enums.session.SessionStatus;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.SignalType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话审计投影服务。
 *
 * <p>收口消息审计投影（用户/助手/工具调用/工具结果）与会话状态流转。
 * 所有写入采用 fire-and-forget，不阻塞响应式主线程。
 * 状态流转通过内部缓存去重，避免冗余 DB UPDATE。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionProjectionService {

    private final SessionManageService sessionManageService;

    /**
     * 按 sessionId 维度的会话状态缓存。
     *
     * <p>记录每个 session 当前已落库的状态，只有状态实际变化时才执行 UPDATE，
     * 避免 text_delta 事件流中数百次冗余的 OUTPUTTING 状态更新。
     */
    private final ConcurrentHashMap<String, SessionStatus> statusCache = new ConcurrentHashMap<>();

    /**
     * 事件驱动的投影（流执行中调用）。
     *
     * <p>处理工具消息落库 + 状态流转。
     *
     * @param ctx   任务上下文
     * @param event 当前事件
     */
    public void onEvent(AegisTaskContext ctx, AgentEvent event) {
        // 恢复式租户作用域（P1-1）：事件回调运行在 AgentScope 内核线程（跨会话/租户复用），
        // 落库操作需绑定租户，且离开时必须恢复进入前上下文，防止泄漏到同线程的后续请求。
        try (var ignore = TenantContextScope.of(ctx.getTenantId())) {
            persistToolMessageIfNeeded(ctx, event);
            updateStatusByEvent(ctx.getSessionId(), event);
        }
    }

    /**
     * 终态投影（流结束时调用，无论 complete/cancel/error）。
     *
     * <p>统一处理助手消息落库 + 最终状态设置。
     *
     * @param ctx         任务上下文
     * @param outputText  累积的输出文本
     * @param tokenInput  输入 Token 数
     * @param tokenOutput 输出 Token 数
     * @param signal      Reactor 终止信号类型
     */
    public void onTerminate(AegisTaskContext ctx, String outputText, String reasoning,
                            int tokenInput, int tokenOutput, SignalType signal) {
        // 恢复式租户作用域（P1-1）：终态回调运行在 AgentScope 内核线程（跨会话/租户复用），
        // 落库操作需绑定租户，且离开时必须恢复进入前上下文，防止泄漏到同线程的后续请求。
        try (var ignore = TenantContextScope.of(ctx.getTenantId())) {
            try {
                // 1. 助手消息落库（fire-and-forget）— 含 reasoning 思考过程（P2-TBL 修复原 0 填充）
                if (outputText != null && !outputText.isEmpty()) {
                    sessionManageService.persistAssistantMessage(
                            ctx.getSessionId(), ctx.getTenantId(), ctx.getUserId(),
                            outputText, reasoning, tokenInput, tokenOutput, null, null);
                }
            } catch (Exception e) {
                log.error("persistAssistantMessage failed: sessionId={}", ctx.getSessionId(), e);
            }

            // 2. 终态状态设置（HITL/已完结感知）
            SessionStatus current = statusCache.get(ctx.getSessionId());
            if (SessionStatus.PAUSED.equals(current)) {
                // HITL 挂起：无论 complete/cancel/error 都保持 PAUSED
                log.info("HITL 挂起中，保持 PAUSED 状态: sessionId={}", ctx.getSessionId());
            } else if (SessionStatus.ENDED.equals(current)) {
                // agent_end 已将状态设为 ENDED：智能体已正常完成回复，
                // 即使后续 Flux 被 CANCEL（如 SSE 连接关闭），也保持 ENDED 不覆盖为 INTERRUPTED
                log.info("智能体已正常完成（agent_end），保持 ENDED 状态: sessionId={}", ctx.getSessionId());
            } else {
                SessionStatus terminalStatus = switch (signal) {
                    case ON_COMPLETE -> SessionStatus.ENDED;
                    case CANCEL -> SessionStatus.INTERRUPTED;
                    case ON_ERROR -> SessionStatus.EXCEPTION;
                    default -> null;
                };
                if (terminalStatus != null) {
                    try {
                        sessionManageService.updateStatus(ctx.getSessionId(), terminalStatus);
                    } catch (Exception e) {
                        log.warn("设置终态失败: sessionId={}, status={}", ctx.getSessionId(), terminalStatus, e);
                    }
                }
            }

            // 3. 清理状态缓存
            statusCache.remove(ctx.getSessionId());
        }
    }

    /**
     * 强制终态投影（外层 doFinally 兜底调用）。
     *
     * <p>当 CANCEL 信号未传播到内层 Flux 导致 {@link #onTerminate} 未触发时，
     * 由 {@code TaskExecutionService} 外层 doFinally 调用此方法。
     * 仅当会话当前为活跃态时才写 INTERRUPTED，避免覆盖内层已正确设置的终态
     * （ENDED/EXCEPTION/INTERRUPTED）。
     *
     * @param sessionId 会话ID
     */
    public void onForceTerminate(String sessionId) {
        onForceTerminate(sessionId, SessionStatus.INTERRUPTED);
    }

    /**
     * 强制终态投影（外层 doFinally 兜底调用，指定终态）。
     *
     * <p>与 {@link #onForceTerminate(String)} 同义，但允许调用方区分错误分支（EXCEPTION）
     * 与取消分支（INTERRUPTED）。仅当会话当前为活跃态时才写入终态，避免覆盖内层已正确设置的
     * ENDED/PAUSED/EXCEPTION/INTERRUPTED（C4 修复：确保异常分支会话必然进入终态，不卡死）。
     *
     * @param sessionId     会话ID
     * @param terminalStatus 终态（EXCEPTION / INTERRUPTED）
     */
    public void onForceTerminate(String sessionId, SessionStatus terminalStatus) {
        try {
            if (sessionManageService.terminateIfActive(sessionId, terminalStatus)) {
                log.warn("僵尸会话已强制终态（外层兜底）: sessionId={}, status={}", sessionId, terminalStatus);
            }
        } catch (Exception e) {
            log.warn("onForceTerminate 终态更新失败: sessionId={}, status={}", sessionId, terminalStatus, e);
        }
        statusCache.remove(sessionId);
    }

    /**
     * 持久化工具调用/结果消息到 session_message 表。
     */
    private void persistToolMessageIfNeeded(AegisTaskContext ctx, AgentEvent event) {
        // 租户上下文由 onEvent 入口统一绑定（TenantContextScope），此处无需重复绑定
        Long tId = ctx.getTenantId();
        try {
            String eventType = event.getEvent();
            long tenantId = tId != null ? tId : 0L;
            long userId = ctx.getUserId() != null ? ctx.getUserId() : 0L;

            // P2-TBL 修复：事件名匹配 HarnessEventConverter 实际输出（tool.call/tool.result，带点号），
            // 原匹配 "tool_call"/"tool_result"（下划线）永远不命中 → TOOL_CALL/TOOL_RESULT 消息 0 行。
            // 同时兼容下划线命名以防后续统一。
            if (("tool.call".equals(eventType) || "tool_call".equals(eventType))
                    && event.getData() instanceof Map<?, ?> dm) {
                String toolCallId = dm.get("id") != null ? dm.get("id").toString() : null;
                String toolName = dm.get("name") != null ? dm.get("name").toString() : null;
                if (toolCallId != null && toolName != null) {
                    sessionManageService.persistToolCallMessage(
                            ctx.getSessionId(), tenantId, userId, toolCallId, toolName, "{}");
                }
            } else if (("tool.result".equals(eventType) || "tool_result".equals(eventType))
                    && event.getData() instanceof Map<?, ?> dm) {
                String toolCallId = dm.get("id") != null ? dm.get("id").toString() : null;
                String result = dm.get("result") != null ? dm.get("result").toString() : null;
                if (toolCallId != null) {
                    sessionManageService.persistToolResultMessage(
                            ctx.getSessionId(), tenantId, userId, toolCallId, result);
                }
            }
        } catch (Exception e) {
            log.warn("持久化工具消息失败: sessionId={}, event={}",
                    ctx.getSessionId(), event.getEvent(), e);
        }
    }

    /**
     * 外部同步更新状态缓存（用于 HITL 等场景）。
     *
     * <p>当 doFinally 块中设置 PAUSED 状态后，需同步更新缓存，
     * 防止 onTerminate 因缓存中仍为 ENDED 而覆盖 PAUSED。
     */
    public void updateStatusCache(String sessionId, SessionStatus status) {
        statusCache.put(sessionId, status);
        log.debug("状态缓存已同步更新: sessionId={}, status={}", sessionId, status);
    }

    /**
     * 根据事件类型更新会话状态（带缓存去重）。
     */
    private void updateStatusByEvent(String sessionId, AgentEvent event) {
        try {
            String eventType = event.getEvent();
            SessionStatus targetStatus = null;
            if ("tool.call".equals(eventType) || "tool_call".equals(eventType)) {
                targetStatus = SessionStatus.TOOL_CALLING;
            } else if ("text.delta".equals(eventType) || "reasoning.delta".equals(eventType)) {
                targetStatus = SessionStatus.OUTPUTTING;
            } else if ("tool_confirm_required".equals(eventType) || "hitl.request".equals(eventType)) {
                targetStatus = SessionStatus.PAUSED;
            } else if ("agent_end".equals(eventType)) {
                // agent_end 表示智能体已完成回复，立即将 DB 状态更新为 ENDED。
                // 防止 agent.streamEvents() 未正确 ON_COMPLETE 时 onTerminate 不被调用，
                // 导致 DB 状态停留在 OUTPUTTING，第二次对话被 CONFLICT 拦截。
                targetStatus = SessionStatus.ENDED;
            }
            if (targetStatus == null) {
                return;
            }
            SessionStatus current = statusCache.get(sessionId);
            if (targetStatus.equals(current)) {
                return;
            }
            sessionManageService.updateStatus(sessionId, targetStatus);
            statusCache.put(sessionId, targetStatus);
        } catch (Exception e) {
            log.debug("更新会话状态失败: sessionId={}", sessionId, e);
        }
    }
}
