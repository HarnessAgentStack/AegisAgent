package com.aegis.runtime.integration.middleware;

import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.dto.security.SecurityPolicyContext;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.policy.AegisSecurityPolicyEngine;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 脱敏中间件，对流式输出进行实时脱敏。
 *
 * <p>在 AgentScope 洋葱链的 order=10 位置，拦截模型输出流中的敏感信息，
 * 调用 {@link AegisSecurityPolicyEngine#evaluateContentPolicy} 对 OUTPUT 内容
 * 进行脱敏评估（MASK 决策），实现流式事件级别的实时脱敏。
 *
 * <h3>拦截点</h3>
 * <ul>
 *   <li>{@code onAgent} — 输出流脱敏（复用 {@link AegisMemoryMiddleware} 在
 *       {@link AegisTaskContext#getAssistantReplyBuffer()} 暴露的累积缓冲，
 *       在流结束时对完整文本进行安全评估）</li>
 * </ul>
 *
 * <h3>脱敏流程</h3>
 * <pre>
 * 模型输出 AgentEvent 流
 *   → AegisMemoryMiddleware doOnNext 累积 TextBlockDeltaEvent（共享缓冲）
 *   → 本中间件 doFinally 读取累积完整文本调用 evaluateContentPolicy(action=OUTPUT)
 *   → MASK 决策：记录审计日志（流已发送，仅记录脱敏后内容用于审计）
 *   → REJECT 决策：设置 blocked 标志，下游拦截
 *   → 其他决策：仅记录审计
 * </pre>
 *
 * <h3>降级策略</h3>
 * <p>脱敏处理失败时透传原内容，不阻塞主链路。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisMaskMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final AegisSecurityPolicyEngine securityPolicyEngine;

    @Override
    public int order() {
        // order=10：最内层，在所有安全控制之后、输出之前执行
        return 10;
    }

    /**
     * onAgent 拦截点：对模型输出流进行脱敏处理。
     *
     * <p>不自行累积 delta——复用 {@link AegisMemoryMiddleware} 写入
     * {@link AegisTaskContext#getAssistantReplyBuffer()} 的共享缓冲，在 {@code doFinally}
     * 中读取累积完整文本进行安全策略评估。由于 AgentScope 2.0 事件流采用 delta 模式且
     * 不可变，脱敏采取 "事后审计 + 阻断标记" 策略：流正常透传，评估结果用于审计追溯
     * 和下游阻断控制。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(input);
        }

        return next.apply(input)
                .doFinally(signalType -> evaluateAccumulatedText(taskCtx))
                .doOnError(e -> log.error("MaskMiddleware 异常", e));
    }

    /**
     * 对流结束时累积的完整文本进行安全评估。
     *
     * <p>评估结果处理：
     * <ul>
     *   <li>MASK：记录脱敏后内容到审计日志（流已发送，仅记录供审计追溯）</li>
     *   <li>REJECT：设置 blocked 标志和阻断原因</li>
     *   <li>ASK/AUDIT_ONLY：记录审计决策</li>
     *   <li>ALLOW：正常放行</li>
     * </ul>
     */
    private void evaluateAccumulatedText(AegisTaskContext taskCtx) {
        StringBuilder buffer = taskCtx.getAssistantReplyBuffer();
        if (buffer == null) {
            return;
        }
        String fullText = buffer.toString();
        if (fullText == null || fullText.isEmpty()) {
            return;
        }

        try {
            SecurityPolicyContext policyCtx = SecurityPolicyContext.builder()
                    .tenantId(taskCtx.getTenantId())
                    .agentId(taskCtx.getAgentId())
                    .governanceTier(taskCtx.getGovernanceTier())
                    .action(SecurityPolicyContext.Action.OUTPUT)
                    .content(fullText)
                    .contentSummary(fullText.length() > 500 ? fullText.substring(0, 500) : fullText)
                    .sessionId(taskCtx.getSessionId())
                    .traceId(taskCtx.getTraceId())
                    .build();

            PolicyDecision decision = securityPolicyEngine.evaluateContentPolicy(policyCtx);

            switch (decision.getDecision()) {
                case MASK:
                    taskCtx.recordPolicyDecision("output_mask", decision);
                    log.info("MaskMiddleware OUTPUT_MASK: sessionId={}, originalLen={}, maskedLen={}, reason={}",
                            taskCtx.getSessionId(),
                            fullText.length(),
                            decision.getMaskedContent() != null ? decision.getMaskedContent().length() : 0,
                            decision.getReason());
                    break;

                case REJECT:
                    taskCtx.setBlocked(true);
                    taskCtx.setBlockReason("输出内容被安全策略阻断: " + decision.getReason());
                    taskCtx.recordPolicyDecision("output_reject", decision);
                    log.warn("MaskMiddleware OUTPUT_REJECT: sessionId={}, reason={}",
                            taskCtx.getSessionId(), decision.getReason());
                    break;

                case ASK:
                    taskCtx.recordPolicyDecision("output_ask", decision);
                    log.info("MaskMiddleware OUTPUT_ASK: sessionId={}, reason={}",
                            taskCtx.getSessionId(), decision.getReason());
                    break;

                case AUDIT_ONLY:
                    taskCtx.recordPolicyDecision("output_audit", decision);
                    break;

                default:
                    // ALLOW - no action needed
                    break;
            }
        } catch (Exception e) {
            log.error("MaskMiddleware evaluateAccumulatedText 异常", e);
        }
    }
}
