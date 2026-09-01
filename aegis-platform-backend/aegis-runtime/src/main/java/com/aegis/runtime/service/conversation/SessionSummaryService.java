package com.aegis.runtime.service.conversation;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.session.SessionMessage;
import com.aegis.core.domain.session.SessionSummary;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.session.MessageType;
import com.aegis.dal.mapper.session.SessionMessageMapper;
import com.aegis.dal.mapper.session.SessionSummaryMapper;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 会话渐进式摘要领域服务。
 *
 * <p>负责"何时生成"（{@link #shouldSummary}）、"如何生成"（{@link #summarizeAsync}）
 * 与"如何加载"（{@link #loadSummaryContext}）三件事，封装 LIGHT 档 LLM 调用与 DB 落库，
 * 对上层暴露 fire-and-forget 的摘要能力，不阻塞主对话链路。</p>
 *
 * <h3>触发策略</h3>
 * <p>每累计 {@link #SUMMARY_INTERVAL} 轮（默认 10）且会话消息总数与最后一条已摘要 seq_end 之差
 * 达到阈值时触发一次。生成范围为 [lastSummarized+1, 当前最后一条 USER/ASSISTANT 消息的 seq]。</p>
 *
 * <h3>降级策略</h3>
 * <p>LLM 调用异常、DB 写入异常均只打 warn 日志，不阻塞主流程；
 * 已写入的摘要在 {@link #loadSummaryContext} 中加载，无摘要时返回空串，
 * 调用方直接忽略。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSummaryService {

    private final SessionSummaryMapper summaryMapper;
    private final SessionMessageMapper messageMapper;
    private final LlmClientFactory llmClientFactory;

    /** 摘要触发阈值：每累计 10 轮生成一个摘要 */
    private static final int SUMMARY_INTERVAL = 10;

    /**
     * 判断是否需要生成新摘要。
     *
     * <p>逻辑：会话消息总数 - 最后一个摘要的 seq_end >= SUMMARY_INTERVAL。
     * 无任何摘要时，lastSummarized 视为 0。</p>
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @return true=已达到触发阈值，应生成新摘要
     */
    public boolean shouldSummary(String sessionId, Long tenantId) {
        // 1. 查会话消息总数
        Long totalMsgs = messageMapper.selectCount(
                new LambdaQueryWrapper<SessionMessage>()
                        .eq(SessionMessage::getSessionId, sessionId)
                        .eq(SessionMessage::getTenantId, tenantId));
        // 2. 查最后一个摘要的 seq_end
        Integer maxSeqEnd = summaryMapper.findMaxSeqEnd(sessionId, tenantId);
        int lastSummarized = maxSeqEnd != null ? maxSeqEnd : 0;
        return (totalMsgs - lastSummarized) >= SUMMARY_INTERVAL;
    }

    /**
     * 异步生成摘要（fire-and-forget）。
     *
     * <p>读取 [lastSummarized+1, ...] 范围内的 USER/ASSISTANT 消息，
     * 拼成 prompt 调 LIGHT 档模型，结果写入 DB。调用方返回 {@link CompletableFuture}
     * 供链路跟踪（也可直接 fire-and-forget 丢弃）。</p>
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @param userId    用户ID（写入审计字段）
     * @return 执行完成信号
     */
    public CompletableFuture<Void> summarizeAsync(String sessionId, Long tenantId, Long userId) {
        return CompletableFuture.runAsync(() -> {
            // 虚拟线程不可靠继承调用方租户上下文（依赖创建线程的 ThreadLocal 状态），
            // 而 findMaxSeqEnd 查询 res_session_summary（非租户插件忽略表）要求 ThreadLocal 上下文，
            // 缺失时 fail-closed 插件抛异常、被下方 catch 吞掉——摘要将永不生成。
            // 显式绑定参数 tenantId，finally 清理（虚拟线程一次性使用，防患于线程复用改造）。
            TenantContextHolder.bind(tenantId);
            try {
                // 1. 读取最后一个摘要的 seq_end
                Integer maxSeqEnd = summaryMapper.findMaxSeqEnd(sessionId, tenantId);
                int seqStart = (maxSeqEnd != null ? maxSeqEnd : 0) + 1;

                // 2. 读取 [seqStart, +SUMMARY_INTERVAL] 范围内的 USER/ASSISTANT 消息
                List<SessionMessage> rangeMsgs = messageMapper.selectList(
                        new LambdaQueryWrapper<SessionMessage>()
                                .eq(SessionMessage::getSessionId, sessionId)
                                .eq(SessionMessage::getTenantId, tenantId)
                                .ge(SessionMessage::getSeq, seqStart)
                                .in(SessionMessage::getMessageType, MessageType.USER, MessageType.ASSISTANT)
                                .orderByAsc(SessionMessage::getSeq)
                                .last("LIMIT " + (SUMMARY_INTERVAL * 2))); // 最多 20 条

                if (rangeMsgs == null || rangeMsgs.isEmpty()) {
                    return;
                }

                int seqEnd = rangeMsgs.get(rangeMsgs.size() - 1).getSeq();

                // 3. 拼 prompt 调 LIGHT 档模型
                String messagesText = rangeMsgs.stream()
                        .map(m -> (m.getMessageType() == MessageType.USER ? "用户: " : "助手: ")
                                + (m.getContent() != null ? m.getContent() : ""))
                        .collect(Collectors.joining("\n"));

                String systemPrompt = """
                        你是一个对话摘要助手。请将以下多轮对话内容简洁总结为一段话（不超过150字），
                        保留关键事实、决定、偏好和未解决的问题。摘要用于帮助 AI 理解早期对话上下文。
                        """;
                String result = llmClientFactory.create(tenantId, ModelTier.LIGHT)
                        .chat(systemPrompt, messagesText, 0.1f, 200, 10);

                if (result != null && !result.isBlank()) {
                    SessionSummary summary = SessionSummary.builder()
                            .sessionId(sessionId)
                            .seqStart(seqStart)
                            .seqEnd(seqEnd)
                            .summaryText(result.trim())
                            .tokenCount(result.length() / 4)
                            .build();
                    summary.setTenantId(tenantId);
                    summary.setCreateBy(userId);
                    summaryMapper.insert(summary);
                    log.info("session_summary 生成: sessionId={}, seq={}-{}, len={}",
                            sessionId, seqStart, seqEnd, result.length());
                }
            } catch (Exception e) {
                log.warn("session_summary 生成失败（已降级不阻塞）: sessionId={}, error={}",
                        sessionId, e.getMessage());
            } finally {
                TenantContextHolder.clear();
            }
        }, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * 加载会话所有摘要文本，拼接成上下文前缀。
     *
     * <p>按 seq 升序拼接："【历史摘要1】... 【历史摘要2】..."。
     * 无摘要时返回空串，调用方直接跳过注入。</p>
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @return 可直接拼入 system prompt 的上下文前缀，空串表示无摘要
     */
    public String loadSummaryContext(String sessionId, Long tenantId) {
        List<SessionSummary> summaries = summaryMapper.findBySession(sessionId, tenantId);
        if (summaries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n【历史对话摘要】\n");
        for (int i = 0; i < summaries.size(); i++) {
            sb.append(i + 1).append(". ").append(summaries.get(i).getSummaryText()).append("\n");
        }
        sb.append("请参考以上摘要理解早期对话上下文，回答用户最新问题。\n");
        return sb.toString();
    }
}
