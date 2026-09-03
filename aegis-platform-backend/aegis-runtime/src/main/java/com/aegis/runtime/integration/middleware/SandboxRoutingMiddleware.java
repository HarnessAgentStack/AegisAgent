package com.aegis.runtime.integration.middleware;

import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.sandbox.SandboxExecutor;
import com.aegis.runtime.service.sandbox.SandboxPolicyResolver;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 沙箱路由中间件（order=85，位于 SandboxLifecycleMiddleware 内部、AgentTraceMiddleware 外部）。
 *
 * <p>在 onActing 阶段判定每个即将执行的工具的沙箱策略：
 * <ul>
 *   <li>策略强制进沙箱 ({@code sandbox_execution=true}) 且工具自身有沙箱执行路径 → INFO 审计</li>
 *   <li>策略强制进沙箱 但工具自身<strong>无</strong>沙箱执行路径 → <strong>WARN 审计</strong>
 *       （"策略白配" — 运营配了强制进但工具没实现沙箱路径）</li>
 *   <li>策略明确不进 ({@code sandbox_execution=false}) 或未配置 → DEBUG 默认决策</li>
 * </ul>
 *
 * <p>当前仅做判定 + 审计日志（fail-open），不拦截执行。
 * 后续可升级为：策略强制进沙箱但工具无能力时 deny（fail-closed）。
 *
 * <p>注：实际沙箱执行由框架 SandboxLifecycleMiddleware（per-call acquire/release）驱动，
 * 本中间件不直接调 Sandbox —— 保持与 AgentScope 2.0.2 深度集成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxRoutingMiddleware implements MiddlewareBase {

    private final SandboxPolicyResolver policyResolver;
    private final SandboxExecutor sandboxExecutor;

    @Override
    public int order() {
        return 85;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput actingInput,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(actingInput);
        }
        Long tenantId = taskCtx.getTenantId();
        List<ToolUseBlock> toolCalls = actingInput.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return next.apply(actingInput);
        }

        // 判定每个工具的沙箱路由策略并分级审计
        for (ToolUseBlock tub : toolCalls) {
            String toolName = tub.getName();
            Boolean policy = policyResolver.resolve(tenantId, toolName);
            boolean hasCapability = sandboxExecutor.toolHasSandboxCapability(toolName);
            boolean shouldSandbox = sandboxExecutor.shouldUseSandbox(tenantId, toolName);

            if (Boolean.TRUE.equals(policy)) {
                if (hasCapability) {
                    // ✅ 策略强制进 + 工具自身有沙箱能力 — 正常执行路径
                    log.info("[sandbox-routing] 策略命中(强制进沙箱): tool={}, policy=true, capability=true, tenantId={}",
                            toolName, tenantId);
                } else {
                    // ⚠️ 策略强制进但工具无沙箱能力 — "策略白配"告警
                    // 典型场景: http_request 配了强制进但当前还是裸跑 HTTP 客户端（Phase 2 改造成 delegate）
                    log.warn("[sandbox-routing] 策略白配! 配置强制进沙箱但工具自身无沙箱执行路径: tool={}, policy=true, capability=false, tenantId={}",
                            toolName, tenantId);
                }
            } else if (Boolean.FALSE.equals(policy)) {
                log.debug("[sandbox-routing] 策略明确不进沙箱: tool={}, policy=false, tenantId={}",
                        toolName, tenantId);
            } else {
                // policy=null — 未配置策略，用默认决策
                log.debug("[sandbox-routing] 策略未配置，走默认决策: tool={}, shouldSandbox={}, tenantId={}",
                        toolName, shouldSandbox, tenantId);
            }
        }

        // 继续正常执行（框架 SandboxLifecycleMiddleware 自动管沙箱 acquire/release）
        return next.apply(actingInput);
    }
}