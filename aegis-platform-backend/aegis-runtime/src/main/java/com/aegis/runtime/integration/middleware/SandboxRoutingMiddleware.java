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
 * 沙箱路由中间件（order=85，位于 Trace(95) 内、RAG(70) 外）。
 * <p>
 * onActing 判定每个工具是否配置了沙箱路由策略：
 * <ul>
 *   <li>策略配置：sec_sandbox_policy 表（后台运营配置，实时生效）</li>
 *   <li>判定结果写入日志 + trace meta，支撑审计与可观测</li>
 *   <li>实际执行仍走工具自身（如 AegisExecuteTool）的双路径逻辑，本中间件仅做"判定 + 审计"</li>
 * </ul>
 * <p>
 * 后续演进：当所有工具的沙箱执行都收敛到 SandboxExecutor 时，可在本中间件短路执行。
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

        // 判定每个工具的沙箱路由策略并记录
        for (ToolUseBlock tub : toolCalls) {
            String toolName = tub.getName();
            Boolean policy = policyResolver.resolve(tenantId, toolName);
            boolean shouldSandbox = sandboxExecutor.shouldUseSandbox(tenantId, toolName);
            log.info("SandboxRoutingMiddleware: tool=[{}], tenantId={}, policy={}, shouldSandbox={}",
                    toolName, tenantId, policy, shouldSandbox);
        }

        // 继续正常执行（工具自身处理沙箱）
        return next.apply(actingInput);
    }
}
