package com.aegis.runtime.service.sandbox;

import com.aegis.core.enums.sandbox.IsolationStrategy;
import io.agentscope.harness.agent.IsolationScope;

/**
 * 沙箱就绪请求（T1 沙箱惰性分配）。
 *
 * <p>封装 {@link SandboxReadinessGate#awaitSandboxReady} 所需的全部分配上下文，
 * 由沙箱类工具（{@code AegisExecuteTool}）在调用前构建。
 * 字段与 {@link com.aegis.runtime.service.sandbox.AegisSandboxCoordinator#allocateSlot}
 * 参数对齐，确保门控分配与框架分配语义一致（§3.2 复用 Coordinator）。
 *
 * @author wang.zhen
 */
public record SandboxReadinessRequest(String sessionId, String slotKey, IsolationScope scope,
                                       Long tenantId, Long userId, Long agentId,
                                       IsolationStrategy strategy, String agentType) {

    /**
     * 构建请求的便捷工厂（strategy 默认 SHARED_PER_SCOPE，与框架默认对齐）。
     */
    public static SandboxReadinessRequest of(String sessionId, String slotKey, IsolationScope scope,
                                              Long tenantId, Long userId, Long agentId, String agentType) {
        return new SandboxReadinessRequest(sessionId, slotKey, scope, tenantId, userId, agentId,
                IsolationStrategy.SHARED_PER_SCOPE, agentType);
    }
}
