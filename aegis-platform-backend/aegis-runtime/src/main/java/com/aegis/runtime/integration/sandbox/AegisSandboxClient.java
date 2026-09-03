package com.aegis.runtime.integration.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxPool;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import com.aegis.runtime.service.sandbox.AegisSandboxAllocator;
import com.alibaba.fastjson2.JSON;
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
 * <h3>create 语义（非框架默认的"随机 sessionId 新建 Pod"）</h3>
 * <p>框架 {@code KubernetesSandboxClient.create} 走随机 sessionId + 新建 Pod，绕过 admin 池。
 * 本类的 {@link #create} 改为调 {@link AegisSandboxAllocator#allocate} 命中池四级退化：
 * 同槽位复用 → SYSTEM 常驻 → 干净 IDLE → 池内扩容。
 * <b>不重复造轮子</b>：K8s 操作能力仍经 ISandboxBackend（allocator 内委托），
 * 不绕过 admin 池的审计/容量闸门/Reconcile 纳管。</p>
 *
 * <h3>resume 语义（跨节点续跑）</h3>
 * <p>从 {@link AegisSandboxState} 反查 {@code sbx_instance}，若仍 OCCUPIED/RESIDENT 且探活通过，
 * 直接返回 {@link AegisSandbox}；若已 IDLE 则重新 allocate 并 hydrateWorkspace 从 MinIO 恢复。</p>
 *
 * <h3>与框架 SandboxManager 协作</h3>
 * <ul>
 *   <li>{@code SandboxManager.acquire} 优先级 3（持久化 state resume）→ 本类 {@link #resume}</li>
 *   <li>{@code SandboxManager.acquire} 优先级 4（新 create）→ 本类 {@link #create}</li>
 *   <li>{@code SandboxManager.release} → 调 sandbox.stop+shutdown（AegisSandbox no-op 不杀 Pod）</li>
 * </ul>
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
     * 创建沙箱：调 allocator 四级退化分配池内实例，包成 {@link AegisSandbox}。
     *
     * <p><b>不建新 Pod</b>（除非池内扩容兜底）：优先复用同槽位 OCCUPIED/常驻/IDLE，
     * 与 admin 池的容量闸门、审计、Reconcile 纳管高度联动。</p>
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
        // 但 SandboxClient.create 签名不含 RuntimeContext，需由 SandboxManager 经 options 传递
        // 这里从 options 提取（周期 2 装配时由 AegisAgentInstanceManager 填充）
        Long tenantId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getTenantId() : 1L;
        Long userId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getUserId() : 1L;
        Long agentId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getAgentId() : 0L;
        String sessionId = options instanceof AegisSandboxClientOptionsExt ext ? ext.getSessionId() : null;

        SandboxInstance inst = allocator.allocate(tenantId, userId, agentId, sessionId, agentType);
        SandboxPool pool = allocator.findPool(tenantId);
        String poolCode = pool != null ? pool.getPoolCode() : "UNKNOWN";
        AegisSandboxState state = new AegisSandboxState(inst, poolCode, workspaceSpec);
        log.info("[aegis-sandbox-client] create: instanceId={}, pod={}, slotKey={}, pool={}",
                inst.getInstanceId(), inst.getPodName(), inst.getSlotKey(), poolCode);
        return new AegisSandbox(inst, state, allocator, allocator.getBackend(), minioSnapshotClient);
    }

    /**
     * 从持久化 state resume：反查 sbx_instance，探活通过则直接复用，否则重新 allocate。
     */
    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof AegisSandboxState aegisState)) {
            throw new IllegalArgumentException("Expected AegisSandboxState but got: " + state.getClass());
        }
        // 反查实例（state 中存了 instanceId/podName/namespace）
        // 简化：直接用 state 中的标识构造 SandboxInstance 内存对象，探活决定复用/重建
        SandboxInstance inst = new SandboxInstance();
        inst.setInstanceId(aegisState.getInstanceId());
        inst.setPodName(aegisState.getPodName());
        inst.setNamespace(aegisState.getNamespace());
        inst.setSlotKey(aegisState.getSlotKey());
        inst.setTenantId(aegisState.getTenantId());
        inst.setUserId(aegisState.getUserId());
        inst.setAgentId(aegisState.getAgentId());
        inst.setSessionId(aegisState.getSessionId());

        if (allocator.probeAlive(inst.getTenantId(), inst)) {
            log.info("[aegis-sandbox-client] resume hit alive pod: instanceId={}, pod={}",
                    inst.getInstanceId(), inst.getPodName());
            return new AegisSandbox(inst, aegisState, allocator, allocator.getBackend(), minioSnapshotClient);
        }

        // Pod 异常（节点重启/回收）→ 重新 allocate + hydrateWorkspace 从 MinIO 恢复
        log.warn("[aegis-sandbox-client] resume probe failed, re-allocate + hydrate: instanceId={}",
                inst.getInstanceId());
        SandboxInstance fresh = allocator.allocate(inst.getTenantId(), inst.getUserId(),
                inst.getAgentId(), inst.getSessionId(),
                aegisState.getSlotKey().contains(":user:") ? "UNIVERSAL" : "APPLICATION");
        SandboxPool pool = allocator.findPool(inst.getTenantId());
        AegisSandboxState freshState = new AegisSandboxState(fresh,
                pool != null ? pool.getPoolCode() : "UNKNOWN", aegisState.getWorkspaceSpec());
        return new AegisSandbox(fresh, freshState, allocator, allocator.getBackend(), minioSnapshotClient);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // 框架 delete 语义：销毁沙箱。Aegis 语义：释放归 allocator.release（IDLE 复用不杀 Pod）
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
