package com.aegis.runtime.integration.agent;

import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.dto.security.ToolRiskInfo;
import com.aegis.runtime.service.tool.ToolRiskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentScope {@code io.agentscope.core.event.AgentEvent} → Aegis {@link AgentEvent} 转换器。
 *
 * <p>单一职责：将 AgentScope HarnessAgent 输出的流式事件转换为 Aegis 前端可消费的 AgentEvent。
 * 从已废弃的 {@code UponIntegrationAdapter.convertEvent} 提取，作为独立组件供
 * {@code TaskExecutionService} 使用。
 *
 * <h3>事件映射表（与前端 src/types/session.ts 契约对齐）</h3>
 * <table>
 *   <tr><th>AgentScope 事件</th><th>Aegis 事件</th><th>data 字段</th></tr>
 *   <tr><td>TEXT_BLOCK_DELTA</td><td>text_delta</td><td>delta, replyId</td></tr>
 *   <tr><td>THINKING_BLOCK_DELTA</td><td>reasoning</td><td>delta, replyId</td></tr>
 *   <tr><td>TOOL_CALL_START</td><td>tool_call</td><td>id, name, arguments</td></tr>
 *   <tr><td>TOOL_CALL_DELTA</td><td>（累积参数，不直接输出）</td><td>累积后供 tool_call / tool_result 使用</td></tr>
 *   <tr><td>TOOL_RESULT_TEXT_DELTA</td><td>（累积结果，不直接输出）</td><td>累积后供 tool_result 使用</td></tr>
 *   <tr><td>TOOL_RESULT_DATA_DELTA</td><td>（累积数据块，不直接输出）</td><td>累积后供 tool_result 使用</td></tr>
 *   <tr><td>TOOL_RESULT_END</td><td>tool_result</td><td>id, status, result, arguments, durationMs</td></tr>
 *   <tr><td>REQUIRE_USER_CONFIRM</td><td>tool_confirm_required</td><td>replyId, toolCalls[{id,name,input}]</td></tr>
 *   <tr><td>AGENT_END</td><td>agent_end</td><td>replyId</td></tr>
 *   <tr><td>MODEL_CALL_END</td><td>token_usage（不透传前端）</td><td>tokenInput, tokenOutput, tokenTotal</td></tr>
 *   <tr><td>AGENT_START / TEXT_BLOCK_START / ...</td><td>null（跳过）</td><td>前端不需要的中间事件</td></tr>
 * </table>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class HarnessEventConverter {

    private final ToolResultCache toolResultCache;
    private final ObjectMapper objectMapper;
    private final ToolRiskService toolRiskService;

    /** 工具调用参数累积缓冲：toolCallId → 参数 JSON 字符串 */
    private final Map<String, StringBuilder> toolCallArgsBuffer = new ConcurrentHashMap<>();

    /** 工具结果文本累积缓冲：toolCallId → 结果文本 */
    private final Map<String, StringBuilder> toolResultTextBuffer = new ConcurrentHashMap<>();

    /** 工具结果数据块累积缓冲：toolCallId → ContentBlock 列表 */
    private final Map<String, List<ContentBlock>> toolResultDataBuffer = new ConcurrentHashMap<>();

    /** 工具调用开始时间：toolCallId → 开始时间戳 */
    private final Map<String, Long> toolCallStartTime = new ConcurrentHashMap<>();

    /** 工具调用名称缓冲：toolCallId → 工具名称（供 tool_result 事件使用） */
    private final Map<String, String> toolCallNameBuffer = new ConcurrentHashMap<>();

    /** 子代理信息跟踪：agentId → SubagentInfo */
    private final Map<String, SubagentInfo> subagentInfoMap = new ConcurrentHashMap<>();

    /** 子代理开始时间：agentId → 开始时间戳 */
    private final Map<String, Long> subagentStartTime = new ConcurrentHashMap<>();

    /** 模型调用开始时间缓冲：replyId → 开始时间戳（供 MODEL_CALL_END 计算耗时） */
    private final Map<String, Long> modelCallStartBuffer = new ConcurrentHashMap<>();

    public HarnessEventConverter(ToolResultCache toolResultCache,
                                  ObjectMapper objectMapper,
                                  ToolRiskService toolRiskService) {
        this.toolResultCache = toolResultCache;
        this.objectMapper = objectMapper;
        this.toolRiskService = toolRiskService;
    }

    /**
     * 将 AgentScope AgentEvent 转换为 Aegis AgentEvent。
     *
     * @param event AgentScope 事件（类型为 {@code io.agentscope.core.event.AgentEvent}）
     * @return Aegis {@link AgentEvent}；对于前端不需要的中间事件返回 null
     */
    public AgentEvent convert(io.agentscope.core.event.AgentEvent event) {
        if (event == null) {
            return null;
        }

        try {
            String eventType = event.getType().name();
            
            // 诊断日志：记录所有进入转换器的事件类型
            switch (eventType) {
                case "TOOL_CALL_START":
                    log.info("========== [EventConverter] TOOL_CALL_START: toolCallId={}, toolName={} ==========",
                            ((ToolCallStartEvent) event).getToolCallId(),
                            ((ToolCallStartEvent) event).getToolCallName());
                    break;
                case "TOOL_CALL_DELTA":
                    // 忽略高频日志
                    break;
                case "TOOL_CALL_END":
                    log.info("========== [EventConverter] TOOL_CALL_END ==========");
                    break;
                case "TOOL_RESULT_START":
                    log.info("========== [EventConverter] TOOL_RESULT_START: toolCallId={} ==========",
                            ((ToolResultStartEvent) event).getToolCallId());
                    break;
                case "TOOL_RESULT_TEXT_DELTA":
                    // P2-6④：高频增量日志降 DEBUG（与 TEXT_BLOCK_DELTA 同策略）
                    log.debug("[EventConverter] TOOL_RESULT_TEXT_DELTA: toolCallId={}, deltaLen={}",
                            ((ToolResultTextDeltaEvent) event).getToolCallId(),
                            ((ToolResultTextDeltaEvent) event).getDelta() != null ?
                                    ((ToolResultTextDeltaEvent) event).getDelta().length() : 0);
                    break;
                case "TOOL_RESULT_DATA_DELTA":
                    // P2-6④：高频增量日志降 DEBUG
                    log.debug("[EventConverter] TOOL_RESULT_DATA_DELTA: toolCallId={}",
                            ((ToolResultDataDeltaEvent) event).getToolCallId());
                    break;
                case "TOOL_RESULT_END":
                    log.info("========== [EventConverter] TOOL_RESULT_END: toolCallId={}, state={} ==========",
                            ((ToolResultEndEvent) event).getToolCallId(),
                            ((ToolResultEndEvent) event).getState());
                    break;
                case "TEXT_BLOCK_DELTA":
                    // 忽略高频日志
                    break;
                default:
                    log.debug("事件类型: {}", eventType);
                    break;
            }

            AgentEvent converted = switch (eventType) {
                case "TEXT_BLOCK_DELTA" -> convertTextDelta((TextBlockDeltaEvent) event);
                case "THINKING_BLOCK_DELTA" -> convertThinkingDelta((ThinkingBlockDeltaEvent) event);
                case "TOOL_CALL_START" -> convertToolCallStart((ToolCallStartEvent) event);
                case "TOOL_CALL_DELTA" -> {
                    accumulateToolCallDelta((ToolCallDeltaEvent) event);
                    yield null;
                }
                case "TOOL_CALL_END" -> null;
                case "TOOL_RESULT_START" -> null;
                case "TOOL_RESULT_TEXT_DELTA" -> {
                    accumulateToolResultTextDelta((ToolResultTextDeltaEvent) event);
                    yield null;
                }
                case "TOOL_RESULT_DATA_DELTA" -> {
                    accumulateToolResultDataDelta((ToolResultDataDeltaEvent) event);
                    yield null;
                }
                case "TOOL_RESULT_END" -> convertToolResultEnd((ToolResultEndEvent) event);
                case "MODEL_CALL_START" -> {
                    // 缓存模型调用开始时间，供 MODEL_CALL_END 关联计算耗时
                    // 模型输入/输出参数由 AegisTraceMiddleware 通过 onModelCall 原生 API 采集
                    ModelCallStartEvent startEvent = (ModelCallStartEvent) event;
                    modelCallStartBuffer.put(startEvent.getReplyId(), System.currentTimeMillis());
                    yield null;
                }
                case "REQUIRE_USER_CONFIRM" -> convertRequireUserConfirm((RequireUserConfirmEvent) event);
                case "MODEL_CALL_END" -> convertModelCallEnd((ModelCallEndEvent) event);
                case "AGENT_END" -> {
                    AgentEvent endEvent = convertAgentEnd((AgentEndEvent) event);
                    if (endEvent != null) {
                        yield endEvent;
                    }
                    yield null;
                }
                // 前端不需要的中间事件
                case "AGENT_START", "TEXT_BLOCK_START", "TEXT_BLOCK_END",
                     "THINKING_BLOCK_START", "THINKING_BLOCK_END",
                     // USER_CONFIRM_RESULT 是 Aegis → AS 方向事件，不会出现在 AS 输出流
                     "USER_CONFIRM_RESULT",
                     // REQUEST_STOP 是 AS 内部暂停信号，已被 REQUIRE_USER_CONFIRM 覆盖语义
                     "REQUEST_STOP",
                     // REQUIRE_EXTERNAL_EXECUTION 当前未启用，预留
                     "REQUIRE_EXTERNAL_EXECUTION",
                     "EXTERNAL_EXECUTION_RESULT",
                     "EXCEED_MAX_ITERS",
                     "HINT_BLOCK",
                     "ALL_TOOLS_DENIED" -> null;
                case "CUSTOM" -> {
                    if (event instanceof CustomEvent ce) {
                        String customName = ce.getName();
                        Map<String, Object> customValue = ce.getValue();
                        log.info("转换 CustomEvent: name={}, value={}", customName, customValue);
                        yield AgentEvent.of(customName, customValue != null ? customValue : Map.of());
                    }
                    yield null;
                }
                case "SUBAGENT_EXPOSED" -> convertSubagentExposed((SubagentExposedEvent) event);
                case "DATA_BLOCK_START", "DATA_BLOCK_DELTA", "DATA_BLOCK_END" -> null;
                default -> {
                    log.debug("未处理的 AgentScope 事件类型: {}", eventType);
                    yield null;
                }
            };
            
            // 记录转换结果
            if (converted != null) {
                log.info("→ 转换成功: {} -> {}", eventType, converted.getEvent());
            }
            return converted;
        } catch (Exception e) {
            log.warn("转换 AgentScope 事件失败: type={}", event.getType(), e);
            return null;
        }
    }

    /**
     * 将 AgentScope 事件转换为 0~N 个 Aegis AgentEvent。
     *
     * <p>支持"一个 AgentScope 事件 → 多个 SSE 事件"的场景，典型用途：
     * skill_creator 工具执行完成后，除了发射 tool.result 事件，
     * 还需要从 {@link ToolResultCache.Entry#events} 中取出编排阶段事件，
     * 逐个作为独立 SSE 事件发射给前端。
     *
     * @param event AgentScope 事件
     * @return Aegis AgentEvent 列表；无事件时返回空列表
     */
    public List<AgentEvent> convertMany(io.agentscope.core.event.AgentEvent event) {
        if (event == null) {
            return Collections.emptyList();
        }

        List<AgentEvent> out = new ArrayList<>();
        try {
            // P0-ITEM-5 修复：对于 TOOL_RESULT_END，必须在 convert() 之前先 peek 提取编排事件。
            // 因为 convertToolResultEnd 内部会调用 toolResultCache.getAndRemove() 移除缓存，
            // 如果先 convert 再 peek，缓存已被清空，skill_creator 的编排事件会全部丢失。
            List<com.aegis.core.dto.agent.AgentEvent> extraEvents = null;
            if ("TOOL_RESULT_END".equals(event.getType().name())) {
                ToolResultEndEvent tre = (ToolResultEndEvent) event;
                String toolCallId = tre.getToolCallId();
                if (toolCallId != null && !toolCallId.isEmpty()) {
                    ToolResultCache.Entry entry = toolResultCache.peek(toolCallId);
                    if (entry != null && entry.events != null && !entry.events.isEmpty()) {
                        extraEvents = entry.events;
                    }
                }
            }

            AgentEvent primary = convert(event);
            if (primary != null) {
                out.add(primary);
            }

            // 额外事件：skill_creator 编排阶段事件（在 convert 之前已提取）
            if (extraEvents != null) {
                for (com.aegis.core.dto.agent.AgentEvent e : extraEvents) {
                    if (e != null && e.getEvent() != null) {
                        out.add(e);
                    }
                }
                log.info("convertMany: 追加 {} 个编排阶段事件", extraEvents.size());
            }
        } catch (Exception e) {
            log.warn("convertMany 异常: type={}", event.getType(), e);
        }
        return out;
    }

    // ==================== 事件处理方法 ====================

    private AgentEvent convertTextDelta(TextBlockDeltaEvent e) {
        Map<String, Object> data = new HashMap<>();
        data.put("delta", e.getDelta());
        data.put("replyId", e.getReplyId());
        // 模型输出文本由 AegisTraceMiddleware.onModelCall 通过 doOnNext 累积
        return AgentEvent.of("text.delta", data);
    }

    private AgentEvent convertThinkingDelta(ThinkingBlockDeltaEvent e) {
        Map<String, Object> data = new HashMap<>();
        data.put("delta", e.getDelta());
        data.put("replyId", e.getReplyId());
        // 思考过程仅用于前端展示（底部折叠区），不混入正式回复文本
        return AgentEvent.of("reasoning.delta", data);
    }

    /**
     * 处理 TOOL_CALL_START 事件。
     *
     * <p>AgentScope 的 ToolCallStartEvent 本身不携带参数，但参数会在后续的
     * {@code TOOL_CALL_DELTA} 事件中以流式方式累积。这里创建占位事件，
     * 当累积完成后（TOOL_RESULT_END 到达时）再补充参数到 tool_result 事件。
     */
    private AgentEvent convertToolCallStart(ToolCallStartEvent e) {
        String toolCallId = e.getToolCallId();
        toolCallArgsBuffer.putIfAbsent(toolCallId, new StringBuilder());
        toolCallStartTime.put(toolCallId, System.currentTimeMillis());
        // BUG-5 FIX: 存储工具名称供后续 tool_result 事件使用
        toolCallNameBuffer.put(toolCallId, e.getToolCallName());

        Map<String, Object> data = new HashMap<>();
        data.put("id", toolCallId);
        data.put("name", e.getToolCallName());
        data.put("arguments", Map.of());
        return AgentEvent.of("tool.call", data);
    }

    /**
     * 累积 TOOL_CALL_DELTA 事件的参数片段。
     *
     * <p>AgentScope 框架通过 {@code ToolCallDeltaEvent} 以 delta 方式
     * 传递工具调用的参数（通常是 JSON 字符串）。累积后供后续
     * {@code TOOL_RESULT_END} 事件使用。
     */
    private void accumulateToolCallDelta(ToolCallDeltaEvent e) {
        String toolCallId = e.getToolCallId();
        StringBuilder sb = toolCallArgsBuffer.computeIfAbsent(toolCallId, k -> new StringBuilder());
        if (e.getDelta() != null) {
            sb.append(e.getDelta());
        }
    }

    /**
     * 累积 TOOL_RESULT_TEXT_DELTA 事件的结果文本。
     *
     * <p>AgentScope 框架通过 {@code ToolResultTextDeltaEvent} 以 delta 方式
     * 传递工具执行的文本结果。累积后供后续 {@code TOOL_RESULT_END} 事件使用。
     */
    private void accumulateToolResultTextDelta(ToolResultTextDeltaEvent e) {
        String toolCallId = e.getToolCallId();
        StringBuilder sb = toolResultTextBuffer.computeIfAbsent(toolCallId, k -> new StringBuilder());
        if (e.getDelta() != null) {
            sb.append(e.getDelta());
        }
    }

    /**
     * 累积 TOOL_RESULT_DATA_DELTA 事件的非文本数据块。
     *
     * <p>AgentScope 框架通过 {@code ToolResultDataDeltaEvent} 传递
     * 非文本类型的结果数据块（如图片、结构化数据等）。
     */
    private void accumulateToolResultDataDelta(ToolResultDataDeltaEvent e) {
        String toolCallId = e.getToolCallId();
        List<ContentBlock> blocks = toolResultDataBuffer.computeIfAbsent(toolCallId, k -> new ArrayList<>());
        if (e.getData() != null) {
            blocks.add(e.getData());
        }
    }

    /**
     * 处理 TOOL_RESULT_END 事件。
     *
     * <p>工具执行完成时，从累积缓冲中提取完整的工具结果文本、参数和执行时长，
     * 组装成 {@code tool.result} 事件发送给前端。同时支持多模态数据块 (dataBlocks)。
     */
    private AgentEvent convertToolResultEnd(ToolResultEndEvent e) {
        String toolCallId = e.getToolCallId();
        Map<String, Object> data = new HashMap<>();
        data.put("id", toolCallId);
        data.put("replyId", e.getReplyId());

        // BUG-5 FIX: 从缓冲中提取工具名称并放入 tool.result 事件数据
        String toolName = toolCallNameBuffer.remove(toolCallId);
        if (toolName != null) {
            data.put("name", toolName);
        }

        String state = e.getState() != null ? e.getState().name() : "SUCCESS";
        String status = state.contains("ERROR") || state.contains("FAIL") ? "ERROR" : "SUCCESS";
        data.put("status", status);

        // 1. 从累积缓冲提取工具结果文本（优先）
        StringBuilder accumulatedText = toolResultTextBuffer.remove(toolCallId);
        ToolResultCache.Entry cacheEntry = toolResultCache.getAndRemove(toolCallId);
        
        log.info("convertToolResultEnd: toolCallId={}, state={}, accumulatedText={}, cacheEntry={}",
                toolCallId, state,
                accumulatedText != null ? accumulatedText.length() + " chars" : "null",
                cacheEntry != null ? "present" : "null");

        if (accumulatedText != null && !accumulatedText.isEmpty()) {
            data.put("result", accumulatedText.toString());
            log.info("  → 使用累积文本作为结果: {} chars", accumulatedText.length());
        } else if (cacheEntry != null && cacheEntry.result != null && !cacheEntry.result.isEmpty()) {
            data.put("result", cacheEntry.result);
            log.info("  → 使用缓存作为结果: {} chars", cacheEntry.result.length());
        } else {
            data.put("result", state);
            log.warn("  → 无结果数据！使用状态作为 fallback: {}", state);
        }

        // 2. 从累积缓冲提取工具参数
        StringBuilder accumulatedArgs = toolCallArgsBuffer.remove(toolCallId);
        if (accumulatedArgs != null && !accumulatedArgs.isEmpty()) {
            try {
                Object parsed = objectMapper.readValue(accumulatedArgs.toString(), Object.class);
                data.put("arguments", parsed);
            } catch (Exception ex) {
                log.warn("解析工具参数 JSON 失败: toolCallId={}, args={}", toolCallId, accumulatedArgs, ex);
                data.put("arguments", Map.of("rawInput", accumulatedArgs.toString()));
            }
        }

        // 3. 计算并清理执行时长
        Long startTime = toolCallStartTime.remove(toolCallId);
        if (startTime != null) {
            data.put("durationMs", System.currentTimeMillis() - startTime);
        }

        // 4. 提取多模态数据块 (P3 新增: dataBlocks)
        List<ContentBlock> dataBlocks = toolResultDataBuffer.remove(toolCallId);
        if (dataBlocks != null && !dataBlocks.isEmpty()) {
            List<Object> serializedBlocks = serializeContentBlocks(dataBlocks);
            if (!serializedBlocks.isEmpty()) {
                data.put("dataBlocks", serializedBlocks);
                log.info("  → 包含 {} 个数据块", serializedBlocks.size());
            }
        }

        return AgentEvent.of("tool.result", data);
    }

    /**
     * 将 AS {@link SubagentExposedEvent} 转换为前端可消费的 {@code subagent.start} 事件。
     *
     * <p>当 AgentSpawnTool 创建子智能体时，会通过本事件向前端暴露子智能体 ID，
     * 使得 SubagentNav 组件能够展示子代理执行轨迹（P3 子代理可见）。
     */
    private AgentEvent convertSubagentExposed(SubagentExposedEvent e) {
        String agentId = e.getAgentId();
        String subagentId = e.getSubagentId();
        String label = e.getLabel();
        long now = System.currentTimeMillis();

        SubagentInfo info = new SubagentInfo(subagentId, label, now);
        subagentInfoMap.put(agentId, info);
        subagentStartTime.put(agentId, now);

        Map<String, Object> data = new HashMap<>();
        data.put("subagentId", subagentId);
        data.put("agentId", agentId);
        data.put("sessionId", e.getSessionId());
        data.put("label", label);
        data.put("timestamp", now);
        log.info("子代理暴露: subagentId={}, agentId={}, label={}", subagentId, agentId, label);
        return AgentEvent.of("subagent.start", data);
    }

    /**
     * 将 AS {@link ContentBlock} 列表序列化为可 JSON 透传的通用结构。
     *
     * <p>ContentBlock 为 AgentScope SDK 内部类型，SSE 透传时需要转换为通用 Map，
     * 避免直接序列化 SDK 对象导致循环引用或类型信息丢失。
     */
    private List<Object> serializeContentBlocks(List<ContentBlock> blocks) {
        List<Object> result = new ArrayList<>();
        if (blocks == null) {
            return result;
        }
        for (ContentBlock block : blocks) {
            try {
                Map<String, Object> map = objectMapper.convertValue(block, Map.class);
                result.add(map);
            } catch (Exception ex) {
                log.warn("序列化 ContentBlock 失败: {}", block, ex);
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("type", "unknown");
                fallback.put("raw", String.valueOf(block));
                result.add(fallback);
            }
        }
        return result;
    }

    private AgentEvent convertAgentEnd(AgentEndEvent e) {
        String sourceAgentId = e.getSource();
        if (sourceAgentId != null && subagentInfoMap.containsKey(sourceAgentId)) {
            return convertSubagentEnd(e, sourceAgentId);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("replyId", e.getReplyId());
        return AgentEvent.of("done", data);
    }

    /**
     * 处理子代理的 AGENT_END 事件，映射为 subagent.end。
     */
    private AgentEvent convertSubagentEnd(AgentEndEvent e, String agentId) {
        SubagentInfo info = subagentInfoMap.remove(agentId);
        Long startTime = subagentStartTime.remove(agentId);
        long durationMs = startTime != null ? System.currentTimeMillis() - startTime : 0L;

        Map<String, Object> data = new HashMap<>();
        data.put("subagentId", info != null ? info.subagentId() : agentId);
        data.put("agentId", agentId);
        data.put("replyId", e.getReplyId());
        data.put("status", "success");
        data.put("durationMs", durationMs);
        data.put("summary", "子代理执行完成");
        log.info("子代理结束: agentId={}, durationMs={}ms", agentId, durationMs);
        return AgentEvent.of("subagent.end", data);
    }

    /**
     * 将 AS {@link RequireUserConfirmEvent} 翻译为 Aegis {@code hitl.request} 事件。
     *
     * <p>事件 data 结构（已增强风险信息）：
     * <ul>
     *   <li>{@code replyId}：当前回复标识，用于关联审批与恢复</li>
     *   <li>{@code toolCalls}：待确认工具调用列表 [{id, name, input, riskLevel, riskReason, category, needApproval}]</li>
     *   <li>{@code maxRiskLevel}：所有工具调用中的最高风险等级</li>
     * </ul>
     *
     * <p>风险信息来源：
     * <ul>
     *   <li>内置工具：{@link com.aegis.core.dto.security.BuiltinToolRiskConfig}</li>
     *   <li>外部工具（MCP等）：数据库 {@code res_tool} 表</li>
     *   <li>未知工具：默认高风险兜底</li>
     * </ul>
     */
    private AgentEvent convertRequireUserConfirm(RequireUserConfirmEvent e) {
        Map<String, Object> data = new HashMap<>();
        data.put("replyId", e.getReplyId());

        List<Map<String, Object>> toolCalls = new ArrayList<>();
        ToolRiskInfo.RiskLevel maxRiskLevel = ToolRiskInfo.RiskLevel.LOW;
        boolean allLowRisk = true;

        if (e.getToolCalls() != null) {
            for (ToolUseBlock tub : e.getToolCalls()) {
                Map<String, Object> tc = new HashMap<>();
                tc.put("id", tub.getId());
                tc.put("name", tub.getName());
                
                Map<String, Object> input = tub.getInput() != null ? tub.getInput() : Map.of();
                tc.put("input", input);

                // 计算工具风险信息
                ToolRiskInfo riskInfo = toolRiskService.evaluateRisk(tub.getName(), input);
                
                Map<String, Object> riskInfoMap = new HashMap<>();
                riskInfoMap.put("riskLevel", riskInfo.getRiskLevel().name());
                riskInfoMap.put("riskReason", riskInfo.getRiskReason());
                riskInfoMap.put("category", riskInfo.getCategory());
                riskInfoMap.put("toolType", riskInfo.getToolType() != null ? riskInfo.getToolType().name() : "UNKNOWN");
                riskInfoMap.put("needApproval", riskInfo.isNeedApproval());
                riskInfoMap.put("sandboxExecution", riskInfo.isSandboxExecution());
                tc.put("riskInfo", riskInfoMap);

                // 计算最大风险等级
                if (riskInfo.getRiskLevel().ordinal() > maxRiskLevel.ordinal()) {
                    maxRiskLevel = riskInfo.getRiskLevel();
                }
                if (riskInfo.isNeedApproval()) {
                    allLowRisk = false;
                }

                toolCalls.add(tc);
            }
        }
        
        data.put("toolCalls", toolCalls);
        data.put("maxRiskLevel", maxRiskLevel.name());

        // Aegis 覆盖逻辑：当所有工具均为低风险（Aegis判定不需要审批），
        // 自动放行，避免不必要的审批弹窗
        if (allLowRisk && !toolCalls.isEmpty()) {
            data.put("autoApproved", true);
            log.info("HITL 自动放行：所有工具为低风险，跳过审批: replyId={}, toolCallCount={}, maxRiskLevel={}",
                    e.getReplyId(), toolCalls.size(), maxRiskLevel);
        } else {
            data.put("autoApproved", false);
            log.info("HITL 审批请求透传前端: replyId={}, toolCallCount={}, maxRiskLevel={}",
                    e.getReplyId(), toolCalls.size(), maxRiskLevel);
        }
        
        return AgentEvent.of("hitl.request", data);
    }

    /**
     * 从 ModelCallEndEvent 提取 Token usage 和耗时。
     * 内部使用，不直接透传前端（通过 task.status 事件间接反映）。
     * 包含 modelCall 标识，供 TaskExecutionService 创建 LLM_CALL Span。
     */
    private AgentEvent convertModelCallEnd(ModelCallEndEvent e) {
        ChatUsage usage = e.getUsage();
        if (usage == null) {
            return null;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("tokenInput", usage.getInputTokens());
        data.put("tokenOutput", usage.getOutputTokens());
        data.put("tokenTotal", usage.getTotalTokens());

        // 计算模型调用耗时（从 MODEL_CALL_START 缓存中取）
        String replyId = e.getReplyId();
        Long startTime = modelCallStartBuffer.remove(replyId);
        if (startTime != null) {
            long durationMs = System.currentTimeMillis() - startTime;
            data.put("durationMs", durationMs);
        }

        // 标记为模型调用事件，供 TaskExecutionService 创建 LLM_CALL Span
        data.put("modelCall", true);
        data.put("replyId", replyId);

        log.debug("Token usage 提取: input={}, output={}, replyId={}, durationMs={}",
                usage.getInputTokens(), usage.getOutputTokens(), replyId,
                startTime != null ? (System.currentTimeMillis() - startTime) : "N/A");
        // 内部事件，用于统计，不直接透传前端
        return AgentEvent.of("task.status", data);
    }

    // ==================== 新增事件方法 (P1) ====================

    /**
     * 创建知识库引用事件 (由调用方在 RAG 检索后调用)。
     */
    public AgentEvent createKbReferenceEvent(String replyId, List<Map<String, Object>> refs) {
        Map<String, Object> data = new HashMap<>();
        data.put("replyId", replyId);
        data.put("refs", refs);
        return AgentEvent.of("kb.reference", data);
    }

    /**
     * 创建产物创建事件 (由 generate_file 等工具调用完成后调用)。
     */
    public AgentEvent createArtifactCreatedEvent(
            String artifactId, int msgSeq, String name, String type,
            String previewUrl, String downloadUrl, Long size) {
        Map<String, Object> data = new HashMap<>();
        data.put("artifactId", artifactId);
        data.put("msgSeq", msgSeq);
        data.put("name", name);
        data.put("type", type);
        data.put("previewUrl", previewUrl);
        data.put("downloadUrl", downloadUrl);
        data.put("size", size);
        return AgentEvent.of("artifact.created", data);
    }

    /**
     * 创建任务状态事件。
     */
    public AgentEvent createTaskStatusEvent(String status, String sessionId, int progress) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        data.put("sessionId", sessionId);
        data.put("progress", progress);
        return AgentEvent.of("task.status", data);
    }


    // ==================== 内部记录类型 ====================

    /**
     * 子代理信息记录。
     */
    private record SubagentInfo(String subagentId, String label, long exposedAt) {
    }
}
