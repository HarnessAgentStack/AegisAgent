package com.aegis.runtime.integration.sandbox;

import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import com.aegis.runtime.service.sandbox.AegisSandboxAllocator;
import com.aegis.runtime.service.sandbox.SandboxSessionHolder;
import com.alibaba.fastjson2.JSON;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Aegis 沙箱客户端（框架 {@link SandboxClient} 适配实现，桥接 admin 池）。
 *
 * <p>核心使命：把框架 {@code SandboxManager} 的 {@code create/resume/delete/serialize/deserialize}
 * 适配到 Aegis admin 池的 {@link AegisSandboxAllocator}（四级退化 + slotKey 隔离 + 乐观锁），
 * 实现"框架管生命周期机制 + admin 池管分配权威"的职责分工。</p>
 *
 * <h3>惰性语义（v2：真·按需物化）</h3>
 * <p>框架 {@code SandboxLifecycleMiddleware.acquireForCall} 在每轮调用开始（LLM 前）即 acquire，
 * 若直接分配则纯聊天也占池。本类的 {@link #create}/{@link #resume} 只返回
 * {@link LazyAegisSandbox} 代理（<b>零分配、零 DB 写</b>），真实分配/重绑推迟到第一次
 * {@code exec/persistWorkspace/hydrateWorkspace}（代码执行工具真正调用时）。</p>
 *
 * <h3>create 轨道（框架优先级 4）</h3>
 * <p>物化时走 allocator 四级退化：同槽位复用 → SYSTEM 常驻 → 干净 IDLE → 池内扩容。
 * <b>不重复造轮子</b>：K8s 操作能力仍经 ISandboxBackend（allocator 内委托），
 * 不绕过 admin 池的审计/容量闸门/Reconcile 纳管。</p>
 *
 * <h3>resume 轨道（框架优先级 3，跨轮/跨节点粘性）</h3>
 * <p>物化时从 {@link AegisSandboxState} 反查 {@code sbx_instance}（补全 DB 主键/版本），
 * 槽位一致且探活通过则 rebind 复用（IDLE→OCCUPIED）；否则重新 allocate 并 hydrateWorkspace
 * 从 MinIO 恢复。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisSandboxClient implements SandboxClient<AegisSandboxClientOptions> {

    private final AegisSandboxAllocator allocator;
    private final MinioSnapshotClient minioSnapshotClient;
    /**
     * 会话持有器（释放记账层）。物化成功后 register 登记，
     * 任务终态/会话关闭时 releaseOnSessionEnd 真正释放（OCCUPIED→IDLE）。
     */
    private final SandboxSessionHolder sessionHolder;

    /**
     * 创建沙箱：返回惰性代理，零分配（物化时机见 {@link LazyAegisSandbox}）。
     *
     * @param workspaceSpec 工作区配置（root/entries/environment）
     * @param snapshotSpec  快照 spec（MinIO/Redis）
     * @param options       Aegis 选项（含 agentType 决定 slotKey）
     */
    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          AegisSandboxClientOptions options) {
        String agentType = options != null && options.getAgentType() != null
                ? options.getAgentType() : "UNIVERSAL";
        // allocate 需要 tenantId/userId/agentId/sessionId，这些来自 SandboxContext.runtimeContext
        // 但 SandboxClient.create 签名不含 RuntimeContext，由 AegisSandboxFilesystemSpec 经
        // options（AegisSandboxClientOptionsExt）传递
        Long tenantId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getTenantId() : 1L;
        Long userId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getUserId() : 1L;
        Long agentId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getAgentId() : 0L;
        String sessionId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getSessionId() : null;

        log.info("[aegis-sandbox-client] create → lazy proxy(零分配,首次exec物化): "
                + "sessionId={}, agentType={}, tenantId={}, userId={}",
                sessionId, agentType, tenantId, userId);
        return LazyAegisSandbox.forCreate(allocator, allocator.getBackend(), minioSnapshotClient,
                sessionHolder, tenantId, userId, agentId, sessionId, agentType, workspaceSpec);
    }

    /**
     * 从持久化 state resume：返回惰性代理，零重绑（物化时走 DB 反查 + 探活 rebind）。
     */
    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof AegisSandboxState aegisState)) {
            throw new IllegalArgumentException("Expected AegisSandboxState but got: " + state.getClass());
        }
        log.info("[aegis-sandbox-client] resume → lazy proxy(零重绑,首次exec物化): "
                + "instanceId={}, sessionId={}, slotKey={}",
                aegisState.getInstanceId(), aegisState.getSessionId(), aegisState.getSlotKey());
        return LazyAegisSandbox.forResume(allocator, allocator.getBackend(), minioSnapshotClient,
                sessionHolder, aegisState);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // 框架 delete 语义：销毁沙箱。Aegis 语义：释放归 allocator.release（IDLE 复用不杀 Pod）。
        // 惰性代理未物化时无资源可释放（纯聊天轮次零占用）。
        if (sandbox instanceof LazyAegisSandbox lazy) {
            AegisSandbox materialized = lazy.getDelegateIfMaterialized();
            if (materialized != null) {
                allocator.release(materialized.getInstance());
            }
            return;
        }
        if (sandbox instanceof AegisSandbox aegis) {
            allocator.release(aegis.getInstance());
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        return JSON.toJSONString(state);
    }

    @Override
    public SandboxState deserializeState(String json) {
        return JSON.parseObject(json, AegisSandboxState.class);
    }
}
