package com.aegis.runtime.integration.sandbox;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Aegis 沙箱文件系统 Spec（框架 {@link SandboxFilesystemSpec} 子类，周期 2 装配接缝）。
 *
 * <p>核心使命：把 Aegis 的 {@link AegisSandboxClient} + admin 池 SnapshotSpec + AegisSandboxClientOptions
 * 经框架 {@link SandboxFilesystemSpec#toSandboxContext} 组装为 {@link io.agentscope.harness.agent.sandbox.SandboxContext}，
 * 由 {@code HarnessAgent.Builder.filesystem(this)} 触发框架自动构建 {@link io.agentscope.harness.agent.sandbox.SandboxManager}
 * + {@link io.agentscope.harness.agent.middleware.SandboxLifecycleMiddleware}，
 * 实现"框架管生命周期机制 + admin 池管分配权威"的职责分工。</p>
 *
 * <h3>装配链路</h3>
 * <pre>
 *   AegisAgentInstanceManager.configureFilesystem
 *     → new AegisSandboxFilesystemSpec(aegisSandboxClient, snapshotSpec, optionsExt)
 *         .isolationScope(USER/AGENT)
 *     → builder.filesystem(this)
 *
 *   HarnessAgent.Builder.build() 内部：
 *     → this.toSandboxContext() → SandboxContext{client, clientOptions, snapshotSpec, workspaceSpec, isolationScope}
 *     → new SandboxManager(client, stateStore, agentId, guard)
 *     → new SandboxLifecycleMiddleware(sandboxManager, sandboxFs)
 *     → 装入 HarnessAgent（defaultSandboxContext + sandboxLifecycleMw）
 * </pre>
 *
 * <h3>灰度开关</h3>
 * <p>{@code aegis.sandbox.framework-drive.enabled=false}（默认）时，
 * AegisAgentInstanceManager 走 RemoteFilesystemSpec（现状零差异）；
 * {@code =true} 时走本 spec，框架接管沙箱生命周期。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@RequiredArgsConstructor
public class AegisSandboxFilesystemSpec extends SandboxFilesystemSpec {

    /** Aegis 沙箱客户端（Spring 注入的 @Component，内桥接 admin 池 allocator） */
    private final AegisSandboxClient aegisSandboxClient;

    /** 快照 spec（SnapshotConfig 的 @Primary MinIO RemoteSnapshotSpec） */
    private final SandboxSnapshotSpec snapshotSpec;

    /** 客户端选项（承载 agentType + tenantId/userId/agentId/sessionId 供 allocator 用） */
    private final AegisSandboxClientOptionsExt options;

    @Override
    protected SandboxClient<?> createClient() {
        return aegisSandboxClient;
    }

    @Override
    protected SandboxClientOptions clientOptions() {
        return options;
    }

    @Override
    protected SandboxSnapshotSpec snapshotSpec() {
        return snapshotSpec;
    }

    @Override
    protected WorkspaceSpec workspaceSpec() {
        // 默认 /workspace，由框架在 toSandboxContext 时合并 workspaceProjection
        return new WorkspaceSpec();
    }

    /**
     * 便捷工厂：按 agentType + 运行时上下文构建 spec。
     *
     * @param aegisSandboxClient 已注入的 Aegis 客户端
     * @param snapshotSpec       MinIO 快照 spec
     * @param agentType          智能体类型（UNIVERSAL/APPLICATION/SYSTEM）
     * @param tenantId           租户 ID
     * @param userId             用户 ID
     * @param agentId            智能体 ID
     * @param sessionId          会话 ID
     * @return 已配 isolationScope 的 AegisSandboxFilesystemSpec
     */
    public static AegisSandboxFilesystemSpec forContext(
            AegisSandboxClient aegisSandboxClient,
            SandboxSnapshotSpec snapshotSpec,
            String agentType, Long tenantId, Long userId, Long agentId, String sessionId) {
        AegisSandboxClientOptionsExt opts = new AegisSandboxClientOptionsExt(
                agentType, tenantId, userId, agentId, sessionId);
        IsolationScope scope = switch (agentType != null ? agentType : "UNIVERSAL") {
            case "UNIVERSAL" -> IsolationScope.USER;
            case "APPLICATION", "SYSTEM" -> IsolationScope.AGENT;
            default -> IsolationScope.USER;
        };
        AegisSandboxFilesystemSpec spec = new AegisSandboxFilesystemSpec(
                aegisSandboxClient, snapshotSpec, opts);
        spec.isolationScope(scope);
        log.info("[aegis-sandbox-fs] forContext: agentType={}, scope={}, tenantId={}, agentId={}",
                agentType, scope, tenantId, agentId);
        return spec;
    }
}
