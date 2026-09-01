package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import com.aegis.runtime.service.sandbox.SandboxResourceLoader;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * 懒沙箱客户端（T1 沙箱惰性分配）。
 *
 * <p>继承 {@link AegisSandboxClient}，覆写 {@link #create}：当
 * {@link AegisSandboxClientOptions#isLazy()} 为 true 时，构造 {@code instanceId=null} 的
 * 占位 {@link AegisSandbox}（保留 slotKey/scope/tenantId 等上下文，但不触发
 * {@link AegisSandboxCoordinator#allocateSlot}），从而把 Pod 占用时机从构建期推迟到
 * 首次沙箱工具调用（经 {@code SandboxReadinessGate.awaitSandboxReady}）。
 *
 * <h3>懒 → 真 切换</h3>
 * <p>占位 state 的 {@code instanceId/podName/namespace} 为 null。工具不通过框架 sandbox
 * 对象执行（{@code AegisExecuteTool} 走 {@code sandboxBackend.exec} + 独立分配），
 * 故占位 sandbox 不会被 {@code doExec} 触发；真实分配由 {@code awaitSandboxReady} 完成，
 * 句柄直接交工具使用，无需回填框架 sandbox 的 state（§4.1.1 路径 A 简化）。
 *
 * <h3>回滚兼容</h3>
 * <p>{@code options.isLazy()==false} 时，直接走父类 {@link AegisSandboxClient#create}
 * 的 fail-closed + allocateSlot 原路径，行为与回滚模式完全一致（§13）。
 *
 * @author wang.zhen
 */
@Slf4j
public class LazyAegisSandboxClient extends AegisSandboxClient {

    private final ISandboxBackend backendRef;
    private final AegisSandboxCoordinator coordinatorRef;

    public LazyAegisSandboxClient(ISandboxBackend sandboxBackend, AegisSandboxCoordinator coordinator,
                                   MinioSnapshotClient minioSnapshotClient,
                                   SandboxResourceLoader resourceLoader) {
        super(sandboxBackend, coordinator, minioSnapshotClient, resourceLoader);
        this.backendRef = sandboxBackend;
        this.coordinatorRef = coordinator;
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          AegisSandboxClientOptions options) {
        if (options != null && options.isLazy()) {
            return createLazyPlaceholder(workspaceSpec, options);
        }
        return super.create(workspaceSpec, snapshotSpec, options);
    }

    /**
     * 覆写 resume：占位态（instanceId==null）直接返回占位 LazyAegisSandbox，
     * 不触发父类 recreateSandbox → allocateSlot。
     *
     * <p>框架在每轮 reply 时调 sandbox.resume() 恢复沙箱状态。若不覆写，父类 resume
     * 发现 instanceId==null 会走 recreateSandbox → allocateSlot，导致纯聊天也触发分配，
     * 完全绕过 T1 懒分配。本覆写确保真实分配只由 {@code SandboxReadinessGate.awaitSandboxReady}
     * 在沙箱工具调用时触发，框架 resume 对占位沙箱直接复用，零 Pod 占用。
     *
     * <p>已分配态（instanceId!=null，如快照恢复后）走父类 resume 正常探活/复用逻辑。
     */
    @Override
    public Sandbox resume(SandboxState state) {
        if (state instanceof AegisSandboxState aegisState
                && (aegisState.getInstanceId() == null || aegisState.getInstanceId().isEmpty())) {
            log.info("[sandbox-lazy] 恢复占位沙箱(不触发 recreate/allocateSlot): sessionId={}, slotKey={}",
                    aegisState.getSessionId(), aegisState.getSlotKey());
            return new LazyAegisSandbox(aegisState, backendRef, coordinatorRef);
        }
        return super.resume(state);
    }

    /**
     * 构建懒占位沙箱：填充上下文字段，instanceId=null，不调 allocateSlot。
     */
    private Sandbox createLazyPlaceholder(WorkspaceSpec workspaceSpec, AegisSandboxClientOptions options) {
        String image = options.getImage() != null ? options.getImage() : "python:3.11-slim";
        String sessionId = options.getSessionId() != null ? options.getSessionId() : UUID.randomUUID().toString();

        AegisSandboxState state = new AegisSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(image);
        state.setContainerOwned(true);
        state.setWorkspaceRootReady(false);
        if (options.getIsolationScope() != null) {
            state.setIsolationScope(options.getIsolationScope());
        }
        if (options.getSlotKey() != null) {
            state.setSlotKey(options.getSlotKey());
        }
        if (options.getTenantId() != null) {
            state.setTenantId(options.getTenantId());
        }
        if (options.getAgentId() != null) {
            state.setAgentId(options.getAgentId());
        }
        if (options.getAgentType() != null) {
            state.setAgentType(options.getAgentType());
        }
        IsolationStrategy strategy = options.getIsolationStrategy() != null
                ? options.getIsolationStrategy() : IsolationStrategy.SHARED_PER_SCOPE;
        state.setIsolationStrategy(strategy);
        // instanceId / podName / namespace 保持 null —— 占位未分配标志
        log.info("[sandbox-lazy] 构建懒占位沙箱(不触发 allocateSlot): slotKey={}, sessionId={}, agentType={}",
                options.getSlotKey(), sessionId, options.getAgentType());
        return new LazyAegisSandbox(state, backendRef, coordinatorRef);
    }
}
