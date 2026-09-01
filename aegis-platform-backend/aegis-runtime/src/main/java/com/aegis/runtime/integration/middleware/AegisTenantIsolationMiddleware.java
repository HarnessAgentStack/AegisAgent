package com.aegis.runtime.integration.middleware;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.function.Function;

/**
 * 租户隔离中间件（AgentScope onAgent 触发点实现）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>preCall（next 之前）：校验 tenantId 非空（防匿名访问），注入
 *       {@link TenantContextHolder}（供 MyBatis-Plus 多租户插件读取）</li>
 *   <li>postCall（doOnComplete/doOnError）：清理 ThreadLocal，防止线程池复用泄漏</li>
 * </ul>
 *
 * <h3>执行顺序</h3>
 * <p>order=80，中间件链首道，确保后续中间件与业务逻辑全程租户隔离。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisTenantIsolationMiddleware implements MiddlewareBase, OrderedMiddleware {

    @Override
    public int order() {
        // AgentScope 2.0.2 按降序执行（值越大越先执行），租户隔离须为 preCall 首道，故取最大值 80
        return 80;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            // 安全中间件 fail-closed，taskCtx 缺失时拒绝执行（不透传）
            log.warn("AegisTenantIsolation: AegisTaskContext 未注入 RuntimeContext，fail-closed 拒绝: agentId={}",
                    agent != null ? agent.getAgentId() : "null");
            return Flux.<AgentEvent>empty();
        }

        Long tenantId = taskCtx.getTenantId();
        if (tenantId == null || tenantId <= 0) {
            taskCtx.setBlocked(true);
            taskCtx.setBlockReason("租户ID缺失，拒绝执行");
            log.warn("Tenant isolation blocked: tenantId missing");
            return Flux.<AgentEvent>empty();
        }

        // 注入 TenantContextHolder（供同步 JDBC 访问）
        TenantContext tenantCtx = TenantContext.builder()
                .tenantId(tenantId)
                .build();
        TenantContextHolder.set(tenantCtx);
        log.debug("Tenant isolation passed: tenantId={}", tenantId);

        return next.apply(input)
                .doOnError(e -> TenantContextHolder.clear())
                .doFinally(signalType -> TenantContextHolder.clear());
    }
}
