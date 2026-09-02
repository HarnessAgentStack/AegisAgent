package com.aegis.runtime.integration.context;

import io.agentscope.core.agent.RuntimeContext;

/**
 * 统一构建 AgentScope RuntimeContext + 注入 Aegis 业务元数据。
 *
 * <p>所有 per-call 元数据通过 typedAttributes 注入，中间件/工具/权限检查统一读取入口。
 * 替代旧 TenantIsolationMiddleware 的 ThreadLocal 注入方式。
 */
public final class AegisRuntimeContextFactory {

    private AegisRuntimeContextFactory() {
    }

    /**
     * 构建 RuntimeContext 并注入 AegisTenant + AegisAgentMeta + AegisGovernance。
     */
    public static RuntimeContext create(Long tenantId, Long agentId, String agentType,
                                         Long userId, String sessionId,
                                         String governanceTier, String modelTier) {
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId(sessionId != null ? sessionId : "")
                .userId(userId != null ? String.valueOf(userId) : "")
                .build();
        ctx.put(AegisTenant.class, new AegisTenant(tenantId));
        ctx.put(AegisAgentMeta.class, new AegisAgentMeta(agentId, agentType));
        ctx.put(AegisGovernance.class, new AegisGovernance(
                governanceTier != null ? governanceTier : "STANDARD",
                modelTier != null ? modelTier : "STANDARD"));
        return ctx;
    }

    public static AegisTenant tenantOf(RuntimeContext ctx) {
        if (ctx == null) return null;
        return ctx.get(AegisTenant.class);
    }

    public static AegisAgentMeta agentOf(RuntimeContext ctx) {
        if (ctx == null) return null;
        return ctx.get(AegisAgentMeta.class);
    }

    public static AegisGovernance governanceOf(RuntimeContext ctx) {
        if (ctx == null) return null;
        return ctx.get(AegisGovernance.class);
    }

    public static Long tenantIdOf(RuntimeContext ctx) {
        AegisTenant t = tenantOf(ctx);
        return t != null ? t.tenantId() : null;
    }
}
