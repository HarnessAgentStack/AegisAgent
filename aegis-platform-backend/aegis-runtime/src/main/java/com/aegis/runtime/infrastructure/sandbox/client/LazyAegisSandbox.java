package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 懒沙箱实现（T1 沙箱惰性分配）。
 *
 * <p>继承 {@link AegisSandbox}，在 {@code instanceId==null}（占位未分配）时，
 * 将 {@code doSetupWorkspace/doPersistWorkspace/doHydrateWorkspace/doExec/doDestroyWorkspace}
 * 转为 no-op 或抛 {@link AegisSandbox.WorkspaceUnavailableException}，避免框架在 reply 期间
 * 调 {@code doSetupWorkspace} 因 {@code requireValidK8sResourceId} 抛
 * {@code IllegalStateException} 中断 SSE 流。
 *
 * <p>真实沙箱类工具执行不经过框架 sandbox 对象（{@code AegisExecuteTool} 走
 * {@code sandboxBackend.exec} + {@code SandboxReadinessGate} 独立分配），
 * 故占位 sandbox 的 doExec 不会被业务工具触发；框架内部的 workspace 生命周期方法
 * （setup/persist/hydrate）在未分配时 no-op 即可。
 *
 * @author wang.zhen
 */
@Slf4j
public class LazyAegisSandbox extends AegisSandbox {

    /** 子类自持 state 引用（父类 state 为 private） */
    private final AegisSandboxState lazyState;

    public LazyAegisSandbox(AegisSandboxState state, ISandboxBackend sandboxBackend,
                             AegisSandboxCoordinator coordinator) {
        super(state, sandboxBackend, coordinator);
        this.lazyState = state;
    }

    /** 是否处于未分配占位态 */
    private boolean isPlaceholder() {
        return lazyState.getInstanceId() == null || lazyState.getInstanceId().isEmpty();
    }

    @Override
    protected void doSetupWorkspace() throws Exception {
        if (isPlaceholder()) {
            log.debug("[sandbox-lazy] 占位沙箱 doSetupWorkspace no-op(未分配): sessionId={}",
                    lazyState.getSessionId());
            return;
        }
        super.doSetupWorkspace();
    }

    @Override
    protected java.io.InputStream doPersistWorkspace() throws Exception {
        if (isPlaceholder()) {
            log.debug("[sandbox-lazy] 占位沙箱 doPersistWorkspace no-op(未分配): sessionId={}",
                    lazyState.getSessionId());
            return new java.io.ByteArrayInputStream(new byte[0]);
        }
        return super.doPersistWorkspace();
    }

    @Override
    protected void doHydrateWorkspace(java.io.InputStream archive) throws Exception {
        if (isPlaceholder()) {
            log.debug("[sandbox-lazy] 占位沙箱 doHydrateWorkspace no-op(未分配): sessionId={}",
                    lazyState.getSessionId());
            return;
        }
        super.doHydrateWorkspace(archive);
    }

    @Override
    protected ExecResult doExec(RuntimeContext ctx, String command, int timeoutSeconds) throws Exception {
        if (isPlaceholder()) {
            log.debug("[sandbox-lazy] 占位沙箱 doExec no-op(未分配): sessionId={}, command={}",
                    lazyState.getSessionId(), command == null ? "null" : (command.length() > 60 ? command.substring(0, 60) : command));
            return new ExecResult(0, "", "", false);
        }
        return super.doExec(ctx, command, timeoutSeconds);
    }

    @Override
    protected void doDestroyWorkspace() throws Exception {
        if (isPlaceholder()) {
            return;
        }
        super.doDestroyWorkspace();
    }
}
