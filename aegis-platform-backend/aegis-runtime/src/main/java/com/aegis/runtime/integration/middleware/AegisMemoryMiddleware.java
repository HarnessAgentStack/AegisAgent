package com.aegis.runtime.integration.middleware;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;
import com.aegis.core.domain.agent.AgentMemory;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.conversation.SessionSummaryService;
import com.aegis.runtime.service.agent.AgentMemoryService;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.function.Function;

/**
 * 跨会话长期记忆中间件（应用层两层记忆的 Layer 2 实现）。
 *
 * <p>AgentScope 2.0 的 {@code LongTermMemory} SPI 与
 * {@code ReActAgent.builder().longTermMemory()} 全套 API 均已标记
 * {@code @Deprecated(forRemoval=true, since=2.0.0)}，官方建议在应用层实现跨会话持久化。
 * 本中间件复用 {@link AgentMemoryService}（DB 结构化记忆），通过 AS 标准中间件触发点接入。
 *
 * <h3>两层记忆设计</h3>
 * <ul>
 *   <li><b>Layer 1 - 短期工作记忆</b>：由 AgentScope 框架自动管理
 *     （{@code Memory} + {@code CompactionMiddleware} + {@code ToolResultEvictionMiddleware}），
 *     负责当前会话内上下文窗口的压缩与驱逐</li>
 *   <li><b>Layer 2 - 跨会话持久化</b>：本中间件负责
 *     <ul>
 *       <li>检索：{@code onSystemPrompt} 触发，按 (agentId, userId) 查询 top-K 记忆
 *           并注入系统提示词，让 LLM 在生成时参考历史上下文</li>
 *       <li>记录：{@code onAgent} 触发，结束时累积 TextBlockDeltaEvent 提取助手回复，
 *           异步调用 {@link AgentMemoryService#extractAndStore} 持久化</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>触发点选择依据</h3>
 * <ul>
 *   <li>{@code onSystemPrompt}（变换式）：唯一可改写系统提示词的触发点，
 *       适合注入历史记忆，无需修改用户输入</li>
 *   <li>{@code onAgent}（最外层洋葱）：包裹整个 Agent 调用过程，
 *       可在事件流末端通过 {@code doFinally} 触发异步记忆抽取</li>
 * </ul>
 *
 * <h3>容错策略</h3>
 * <p>记忆写入失败不阻塞主流程，仅记录错误日志（与 {@link AegisAuditLogMiddleware}
 * 一致的异步策略，使用 {@code Schedulers.boundedElastic()}）。
 *
 * <h3>执行顺序</h3>
 * <p>order=10，与 {@link AegisMaskMiddleware} 同处洋葱链最内层，
 * 位于 {@link AegisAuditLogMiddleware}(20) 之内。
 *
 * <h3>输出缓冲共享</h3>
 * <p>本中间件在 {@code onAgent} 创建 {@link StringBuilder} 累积助手回复，并写入
 * {@link AegisTaskContext#setAssistantReplyBuffer(StringBuilder) taskCtx.assistantReplyBuffer}。
 * {@link AegisMaskMiddleware} 不再自建缓冲，其 {@code doFinally} 只读消费本缓冲做输出安全审计——
 * 消除两中间件各自 doOnNext 重复累积同一份 {@link TextBlockDeltaEvent} 的冗余。
 * 洋葱式语义下，{@code doFinally} 由内向外触发：事件流终止时两 doFinally 均在全部 delta
 * 累积完成后执行，时序无依赖。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisMemoryMiddleware implements MiddlewareBase, OrderedMiddleware {

    /** 历史记忆注入条数上限 */
    private static final int RETRIEVE_TOP_K = 5;

    /** 防重复注入的提示词标记 */
    private static final String MEMORY_BLOCK_MARKER = "【AEGIS_LONG_TERM_MEMORY】";

    private final AgentMemoryService agentMemoryService;
    private final SessionSummaryService sessionSummaryService;

    @Override
    public int order() {
        // AS 降序执行，跨会话记忆为 preCall 末道（最内层）
        return 10;
    }

    /**
     * 变换式：将历史记忆注入系统提示词。
     *
     * <p>策略：按 (agentId, userId) 查询 top-K 条最近更新的记忆，
     * 以结构化区块追加到系统提示词末尾，让 LLM 参考历史上下文。
     *
     * <p>跳过条件：上下文缺失 / userId 或 agentId 为空 / 已注入标记 / 检索异常。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(prompt);
        }
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null || taskCtx.getUserId() == null || taskCtx.getAgentId() == null) {
            return Mono.just(prompt);
        }
        if (prompt.contains(MEMORY_BLOCK_MARKER)) {
            return Mono.just(prompt);
        }

        return Mono.fromCallable(() -> {
                    // boundedElastic 线程不继承装配线程的租户上下文（ThreadLocal 不跨线程），
                    // agent_memory 表未列入租户插件忽略清单，selectList 会触发 fail-closed
                    // "租户上下文缺失"异常导致记忆检索永远失败——显式绑定 taskCtx 租户，
                    // finally 恢复调用方上下文（boundedElastic 线程池复用，防止上下文泄漏）。
                    TenantContext previousContext = TenantContextHolder.get();
                    TenantContextHolder.bind(taskCtx.getTenantId());
                    try {
                        return retrieveAndInject(taskCtx, prompt);
                    } finally {
                        if (previousContext != null) {
                            TenantContextHolder.set(previousContext);
                        } else {
                            TenantContextHolder.clear();
                        }
                    }
                })
                .onErrorResume(e -> {
                    log.warn("Memory retrieval failed, fallback to original prompt: agentId={}, userId={}",
                            taskCtx.getAgentId(), taskCtx.getUserId(), e);
                    return Mono.just(prompt);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 最外层洋葱：累积助手回复文本，结束时异步抽取并存储。
     *
     * <p>策略：通过 {@code doOnNext} 收集 {@link TextBlockDeltaEvent} 的 delta，
     * 在 {@code doFinally} 异步触发 {@link AgentMemoryService#extractAndStore}。
     *
     * <p>跳过条件：上下文缺失 / userId 或 agentId 为空。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(input);
        }

        // 创建累积缓冲并暴露给同链路的 AegisMaskMiddleware（doFinally 只读消费），
        // 消除两中间件各自 doOnNext 对同一份 TextBlockDeltaEvent 的重复累积。
        // 即使后续持久化条件不满足，缓冲仍需创建以供 Mask 输出审计使用。
        StringBuilder assistantReply = new StringBuilder();
        taskCtx.setAssistantReplyBuffer(assistantReply);

        boolean persistEnabled = taskCtx.getUserId() != null && taskCtx.getAgentId() != null;
        return next.apply(input)
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta) {
                        String text = delta.getDelta();
                        if (text != null && !text.isEmpty()) {
                            assistantReply.append(text);
                        }
                    }
                })
                // doFinally 在 Flux 任意终止方式下都会触发，被拦截或空回复时跳过持久化
                .doFinally(signalType -> {
                    if (!persistEnabled) {
                        return;
                    }
                    // 被中间件拦截时不持久化（assistantReply 为空且语义不完整）
                    if (taskCtx.isBlocked()) {
                        log.debug("Memory 跳过持久化（请求被拦截）: agentId={}, userId={}",
                                taskCtx.getAgentId(), taskCtx.getUserId());
                        return;
                    }
                    String reply = assistantReply.toString();
                    // 助手回复为空时跳过持久化（避免写入无意义空记忆）
                    if (reply == null || reply.isEmpty()) {
                        log.debug("Memory 跳过持久化（助手回复为空）: agentId={}, userId={}",
                                taskCtx.getAgentId(), taskCtx.getUserId());
                        return;
                    }
                    persistMemoryAsync(taskCtx, reply);

                    // 渐进摘要触发（每 SUMMARY_INTERVAL 轮异步生成，fire-and-forget）。
                    // doFinally 运行在终止信号线程（LLM 客户端 IO 线程），租户上下文未在该线程
                    // 绑定，而 shouldSummary 内 findMaxSeqEnd 查询 res_session_summary（非忽略表）
                    // 在 fail-closed 插件下抛异常、被下方 catch 吞掉——摘要将永不触发。
                    // 显式绑定租户后同步判断，finally 恢复终止线程原有上下文。
                    TenantContext previousContext = TenantContextHolder.get();
                    TenantContextHolder.bind(taskCtx.getTenantId());
                    try {
                        if (sessionSummaryService.shouldSummary(taskCtx.getSessionId(), taskCtx.getTenantId())) {
                            sessionSummaryService.summarizeAsync(
                                    taskCtx.getSessionId(), taskCtx.getTenantId(), taskCtx.getUserId());
                        }
                    } catch (Exception e) {
                        log.debug("Memory 渐进摘要跳过（判断或触发失败）: sessionId={}, error={}",
                                taskCtx.getSessionId(), e.getMessage());
                    } finally {
                        if (previousContext != null) {
                            TenantContextHolder.set(previousContext);
                        } else {
                            TenantContextHolder.clear();
                        }
                    }
                });
    }

    /**
     * 检索历史记忆并注入到系统提示词。
     */
    private String retrieveAndInject(AegisTaskContext ctx, String prompt) {
        List<AgentMemory> memories = agentMemoryService.retrieveMemories(
                ctx.getAgentId(), ctx.getUserId(),
                ctx.getUserMessage(), RETRIEVE_TOP_K, ctx.getTenantId());
        if (memories == null || memories.isEmpty()) {
            log.debug("Memory retrieval empty: agentId={}, userId={}",
                    ctx.getAgentId(), ctx.getUserId());
            return prompt;
        }

        StringBuilder memoryBlock = new StringBuilder("\n\n")
                .append(MEMORY_BLOCK_MARKER).append('\n')
                .append("以下是关于当前用户的已知信息，请参考：\n");
        for (AgentMemory m : memories) {
            String key = m.getMemoryKey() != null ? m.getMemoryKey() : "unknown";
            String value = unwrapJsonValue(m.getMemoryValue());
            memoryBlock.append("- ").append(key).append(": ").append(value).append('\n');
        }

        log.debug("Memory injected: agentId={}, userId={}, count={}",
                ctx.getAgentId(), ctx.getUserId(), memories.size());
        return prompt + memoryBlock.toString();
    }

    /**
     * 将 JSON 字符串字面量解包为纯文本（兼容历史非 JSON 数据）。
     *
     * @param raw 数据库中的 memory_value（JSON 格式或历史纯文本）
     * @return 解包后的纯文本
     */
    private String unwrapJsonValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        try {
            return JSON.parseObject(raw, String.class);
        } catch (Exception e) {
            // 历史数据可能为非 JSON 纯文本，直接返回
            return raw;
        }
    }

    /**
     * 异步抽取并存储记忆，失败不抛异常。
     *
     * <p>使用 {@code Schedulers.boundedElastic()} 避免阻塞响应式线程，
     * 与 {@link AegisAuditLogMiddleware#writeAuditLog} 一致。
     */
    private void persistMemoryAsync(AegisTaskContext ctx, String assistantReply) {
        Mono.fromRunnable(() -> {
            // boundedElastic 线程需显式绑定租户（同 onSystemPrompt）。
            // 当前 insertOrUpdate 显式携带 tenant_id 列（插件跳过自动填充）故未报错，
            // 绑定保证该链路未来新增查询类操作时的上下文安全。
            TenantContext previousContext = TenantContextHolder.get();
            TenantContextHolder.bind(ctx.getTenantId());
            try {
                agentMemoryService.extractAndStore(
                        ctx.getAgentId(), ctx.getUserId(),
                        ctx.getUserMessage(), assistantReply,
                        ctx.getTenantId());
                log.debug("Memory extracted: agentId={}, userId={}, replyLen={}",
                        ctx.getAgentId(), ctx.getUserId(), assistantReply.length());
            } catch (Exception e) {
                log.error("Memory extraction failed: agentId={}, userId={}",
                        ctx.getAgentId(), ctx.getUserId(), e);
            } finally {
                if (previousContext != null) {
                    TenantContextHolder.set(previousContext);
                } else {
                    TenantContextHolder.clear();
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }
}
