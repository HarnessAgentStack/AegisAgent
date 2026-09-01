package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.domain.sandbox.SandboxAllocationContext;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.spi.ISandboxBackend;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Aegis 沙箱客户端：实现 AgentScope {@link SandboxClient} 契约。
 *
 * <p>负责创建（{@link #create}）和恢复（{@link #resume}）{@link AegisSandbox} 实例，
 * 通过 {@link ISandboxBackend} 桥接 Aegis 沙箱池。
 *
 * <h3>P0 改造：统一沙箱分配入口</h3>
 * <p>create() 不再直接构建 AegisSandbox，而是先通过 {@link AegisSandboxCoordinator#allocateSlot}
 * 完成沙箱实例分配（含 slotKey 复用、配额校验、sbx_instance 记录写入），
 * 再用返回的 instanceId 构建 AegisSandbox。
 * 这消除了双重沙箱分配问题（SB-01），确保所有沙箱实例统一管理。
 *
 * <h3>create 流程</h3>
 * <ol>
 *   <li>从 options 提取 tenantId / isolationScope / slotKey</li>
 *   <li>调用 {@link AegisSandboxCoordinator#allocateSlot} 分配沙箱实例</li>
 *   <li>构建 {@link AegisSandboxState}，填充 instanceId / slotKey / isolationScope</li>
 *   <li>返回 {@link AegisSandbox}（尚未调用 start()，由 AgentScope 框架驱动）</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
public class AegisSandboxClient implements SandboxClient<AegisSandboxClientOptions> {

    private final ObjectMapper objectMapper;
    private final ISandboxBackend sandboxBackend;
    private final AegisSandboxCoordinator coordinator;
    private final MinioSnapshotClient minioSnapshotClient;
    /** A5：资源装载器（可选，旧配置兼容为 null） */
    private final com.aegis.runtime.service.sandbox.SandboxResourceLoader sandboxResourceLoader;
    /** A5：最近一次 create() 的选项（recreate 时复用装载上下文） */
    private volatile AegisSandboxClientOptions lastOptions;

    /**
     * 默认构造函数（用于 {@link AegisSandboxClientOptions#createClient()} 反射创建）。
     *
     * <p>sandboxBackend 和 coordinator 为 null，实际使用时需通过
     * {@link #AegisSandboxClient(ISandboxBackend, AegisSandboxCoordinator)} 注入。
     */
    public AegisSandboxClient() {
        this(null, null, null, null);
    }

    /**
     * 仅注入 backend 的构造函数（向后兼容，coordinator 为 null 时退化为直接创建）。
     */
    public AegisSandboxClient(ISandboxBackend sandboxBackend) {
        this(sandboxBackend, null, null, null);
    }

    /**
     * P0 构造函数：注入 backend + coordinator。
     *
     * <p>coordinator 非 null 时，create() 走统一分配路径；
     * coordinator 为 null 时，create() 退化为直接 backend.create()（仅测试使用）。
     */
    public AegisSandboxClient(ISandboxBackend sandboxBackend, AegisSandboxCoordinator coordinator) {
        this(sandboxBackend, coordinator, null, null);
    }

    /**
     * P0-08 构造函数：注入 backend + coordinator + minioSnapshotClient。
     *
     * <p>minioSnapshotClient 用于在反序列化时重新绑定 RemoteSnapshotClient，
     * 避免 RemoteSandboxSnapshot 因 client 为 null 抛出异常。
     */
    public AegisSandboxClient(ISandboxBackend sandboxBackend, AegisSandboxCoordinator coordinator,
                               MinioSnapshotClient minioSnapshotClient) {
        this(sandboxBackend, coordinator, minioSnapshotClient, null);
    }

    /**
     * A5 构造函数：注入 backend + coordinator + minioSnapshotClient + 资源装载器。
     *
     * <p>资源装载器非 null 时，create()/recreateSandbox() 分配成功后异步触发
     * KB/SKILL/MCP 清单装载（不阻塞 Agent 构建）。
     */
    public AegisSandboxClient(ISandboxBackend sandboxBackend, AegisSandboxCoordinator coordinator,
                               MinioSnapshotClient minioSnapshotClient,
                               com.aegis.runtime.service.sandbox.SandboxResourceLoader sandboxResourceLoader) {
        this.sandboxBackend = sandboxBackend;
        this.coordinator = coordinator;
        this.minioSnapshotClient = minioSnapshotClient;
        this.sandboxResourceLoader = sandboxResourceLoader;
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new HarnessSandboxJacksonModule());
        mapper.registerSubtypes(AegisSandboxState.class);
        this.objectMapper = mapper;
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec,
                          SandboxSnapshotSpec snapshotSpec,
                          AegisSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();

        String image = options != null && options.getImage() != null
                ? options.getImage() : "python:3.11-slim";

        AegisSandboxState state = new AegisSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(image);
        state.setContainerOwned(true);
        state.setWorkspaceRootReady(false);

        // P5-2：会话级隔离字段
        IsolationScope scope = options != null ? options.getIsolationScope() : null;
        String slotKey = options != null ? options.getSlotKey() : null;
        Long tenantId = options != null ? options.getTenantId() : null;
        state.setIsolationScope(scope);
        state.setSlotKey(slotKey);
        state.setTenantId(tenantId);
        // P0-2：智能体类型（池路由决策上下文，随状态持久化供 recreate 使用）
        String agentType = options != null ? options.getAgentType() : null;
        state.setAgentType(agentType);

        if (options != null && options.getSessionId() != null && !options.getSessionId().isEmpty()) {
            state.setSessionId(options.getSessionId());
        }
        if (options != null && options.getIsolationStrategy() != null) {
            state.setIsolationStrategy(options.getIsolationStrategy());
        }
        Long agentId = options != null ? options.getAgentId() : null;
        state.setAgentId(agentId);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        if (sandboxBackend == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "ISandboxBackend 未注入，AegisSandboxClient 无法创建沙箱");
        }

        // 强制要求走 Coordinator 统一分配路径（禁止静默降级到进程内/直连模式）
        if (coordinator == null || scope == null || slotKey == null || tenantId == null) {
            String reason = coordinator == null ? "Coordinator 未注入"
                    : scope == null ? "isolationScope 缺失"
                    : slotKey == null ? "slotKey 缺失"
                    : "tenantId 缺失";
            log.error("[sandbox-aegis] 沙箱分配条件不满足: {}, 强制走 Coordinator 路径, 拒绝降级", reason);
            throw new SandboxException.SandboxConfigurationException(
                    "沙箱分配条件不满足: " + reason + "。所有环境必须通过 Coordinator 走 K8s 沙箱池。");
        }

        // 通过 Coordinator 统一分配沙箱实例
        Long userId = options != null ? options.getUserId() : null;
        IsolationStrategy strategy = options != null ? options.getIsolationStrategy() : null;
        SandboxAllocationContext allocationResult = coordinator.allocateSlot(scope, slotKey, tenantId,
                userId, agentId, sessionId,
                strategy != null ? strategy : IsolationStrategy.SHARED_PER_SCOPE, agentType);
        state.setInstanceId(allocationResult.getInstanceId());
        state.setPodName(allocationResult.getPodName());
        state.setNamespace(allocationResult.getNamespace());
        log.info("[sandbox-aegis] Coordinator 分配沙箱: sessionId={}, instanceId={}, podName={}, namespace={}, scope={}, slotKey={}, agentType={}",
                sessionId, allocationResult.getInstanceId(), allocationResult.getPodName(),
                allocationResult.getNamespace(), scope, slotKey, agentType);

        // A5：分配成功后异步触发资源装载（不阻塞 Agent 构建，与首 Token 并行）
        this.lastOptions = options;
        triggerResourceLoading(state, userId,
                options != null ? options.getSandboxResourceLoader() : sandboxResourceLoader, strategy);

        log.debug("[sandbox-aegis] 创建沙箱: sessionId={}, image={}", sessionId, image);
        return new AegisSandbox(state, sandboxBackend, coordinator);
    }

    /**
     * A5：异步触发沙箱资源装载（KB/SKILL/MCP 清单物化到 Pod 工作区）。
     *
     * <p>装载器缺失时静默跳过（旧配置兼容）；装载完成后回写
     * {@code resourceFingerprint} 到沙箱状态（随 DistributedStore 序列化）。
     */
    private void triggerResourceLoading(AegisSandboxState state, Long userId,
                                        com.aegis.runtime.service.sandbox.SandboxResourceLoader loader,
                                        IsolationStrategy strategy) {
        if (loader == null) {
            return;
        }
        try {
            com.aegis.runtime.service.sandbox.SandboxResourceLoader.LoadingContext ctx =
                    new com.aegis.runtime.service.sandbox.SandboxResourceLoader.LoadingContext(
                            state.getTenantId(), userId, state.getAgentId(), state.getSessionId(),
                            state.getAgentType(), state.getInstanceId(), state.getK8sResourceId(),
                            strategy != null ? strategy : IsolationStrategy.SHARED_PER_SCOPE);
            loader.loadAsync(ctx)
                    .thenAccept(outcome -> {
                        if (outcome != null && outcome.fingerprint() != null) {
                            state.setResourceFingerprint(outcome.fingerprint());
                        }
                    });
            log.debug("[sandbox-aegis][A5] 资源装载已异步触发: instanceId={}", state.getInstanceId());
        } catch (Exception e) {
            log.warn("[sandbox-aegis][A5] 触发资源装载失败（不影响沙箱创建）: instanceId={}, error={}",
                    state.getInstanceId(), e.getMessage());
        }
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof AegisSandboxState aegisState)) {
            throw new IllegalArgumentException(
                    "期望 AegisSandboxState 但收到: " + state.getClass().getName());
        }
        if (sandboxBackend == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "ISandboxBackend 未注入，AegisSandboxClient 无法恢复沙箱");
        }
        log.debug("[sandbox-aegis] 恢复沙箱: sessionId={}, instanceId={}, podName={}, namespace={}",
                aegisState.getSessionId(), aegisState.getInstanceId(),
                aegisState.getPodName(), aegisState.getNamespace());
        
        // P2 补丁：补充沙箱状态中缺失的 podName 和 namespace
        if (coordinator != null) {
            coordinator.enrichSandboxState(aegisState);
        }
        
        // 关键检查：如果补充后仍然没有有效的 K8s 资源标识，说明是旧格式状态
        if (!aegisState.hasValidK8sResource()) {
            log.warn("[sandbox-aegis] 沙箱状态缺少有效的 K8s 资源标识 (podName/namespace)，" +
                    "需要重新创建沙箱: instanceId={}", aegisState.getInstanceId());
            return recreateSandbox(aegisState);
        }

        // ★ Pod 存活检查：验证 K8s 中 Pod 是否真正存在
        String k8sResourceId = aegisState.getK8sResourceId();
        if (aegisState.getTenantId() != null && k8sResourceId != null) {
            try {
                boolean alive = sandboxBackend.probeAlive(aegisState.getTenantId(), k8sResourceId);
                if (!alive) {
                    log.warn("[sandbox-aegis] Pod 不存在于 K8s，需要重新创建: " +
                            "instanceId={}, podName={}, namespace={}",
                            aegisState.getInstanceId(), aegisState.getPodName(), aegisState.getNamespace());
                    // Pod 不存在，直接重新创建沙箱（不用抛异常让框架回退）
                    return recreateSandbox(aegisState);
                }
                log.info("[sandbox-aegis] 沙箱状态验证通过: sessionId={}, instanceId={}, podName={}, namespace={}",
                        aegisState.getSessionId(), aegisState.getInstanceId(),
                        aegisState.getPodName(), aegisState.getNamespace());
            } catch (Exception e) {
                log.warn("[sandbox-aegis] Pod 探活异常，需要重新创建: " +
                        "instanceId={}, error={}", aegisState.getInstanceId(), e.getMessage());
                // 探活异常，也需要重新创建
                return recreateSandbox(aegisState);
            }
        } else {
            // 没有足够信息进行探活，重新创建沙箱
            log.warn("[sandbox-aegis] 缺少必要信息进行 Pod 探活，需要重新创建: " +
                    "instanceId={}, tenantId={}",
                    aegisState.getInstanceId(), aegisState.getTenantId());
            return recreateSandbox(aegisState);
        }

        // A5：resume 复用实例时按指纹判断是否需要（重）装载
        // （initialized != 2 或清单指纹变化时触发增量装载；指纹一致则内部跳过。
        //  userId 由 Loader 从 sbx_instance 占用记录自愈补全）
        triggerResourceLoading(aegisState, null,
                lastOptions != null && lastOptions.getSandboxResourceLoader() != null
                        ? lastOptions.getSandboxResourceLoader() : sandboxResourceLoader,
                aegisState.getIsolationStrategy());

        return new AegisSandbox(aegisState, sandboxBackend, coordinator);
    }

    /**
     * 重新创建沙箱：当 Pod 不存在或状态无效时调用。
     *
     * <p>复用 create() 流程，通过 coordinator.allocateSlot() 分配新的沙箱实例。
     * 保持原有的 sessionId、tenantId、agentId 等上下文信息。
     *
     * @param aegisState 原沙箱状态（已失效）
     * @return 新的 AegisSandbox 实例
     */
    private AegisSandbox recreateSandbox(AegisSandboxState aegisState) {
        if (coordinator == null) {
            log.error("[sandbox-aegis] Coordinator 未注入，无法重新创建沙箱");
            throw new SandboxException.SandboxConfigurationException(
                    "Coordinator 未注入，无法重新创建沙箱。请检查配置。");
        }

        // 保存原有的上下文信息
        String sessionId = aegisState.getSessionId();
        IsolationScope scope = aegisState.getIsolationScope();
        String slotKey = aegisState.getSlotKey();
        Long tenantId = aegisState.getTenantId();
        Long agentId = aegisState.getAgentId();
        IsolationStrategy strategy = aegisState.getIsolationStrategy();
        String agentType = aegisState.getAgentType();

        // 清除旧的 K8s 资源标识
        aegisState.setInstanceId(null);
        aegisState.setPodName(null);
        aegisState.setNamespace(null);

        // 如果缺少必要的分配信息，无法重新创建
        if (scope == null || slotKey == null || tenantId == null) {
            log.error("[sandbox-aegis] 缺少必要的分配信息，无法重新创建: " +
                    "scope={}, slotKey={}, tenantId={}", scope, slotKey, tenantId);
            throw new SandboxException.SandboxConfigurationException(
                    "缺少必要的沙箱分配信息 (scope/slotKey/tenantId)，无法重新创建。");
        }

        // 通过 coordinator 重新分配沙箱实例
        log.info("[sandbox-aegis] 重新创建沙箱: sessionId={}, scope={}, slotKey={}, tenantId={}, agentType={}",
                sessionId, scope, slotKey, tenantId, agentType);
        SandboxAllocationContext allocationResult = coordinator.allocateSlot(
                scope, slotKey, tenantId, null, agentId, sessionId,
                strategy != null ? strategy : IsolationStrategy.SHARED_PER_SCOPE, agentType);

        // 更新状态信息
        aegisState.setInstanceId(allocationResult.getInstanceId());
        aegisState.setPodName(allocationResult.getPodName());
        aegisState.setNamespace(allocationResult.getNamespace());
        // A5：重建后工作区为空，旧装载指纹失效
        aegisState.setResourceFingerprint(null);

        // A5：重建分配成功后异步重装载（userId 取自最近一次 create 的选项）
        Long recreateUserId = lastOptions != null ? lastOptions.getUserId() : null;
        triggerResourceLoading(aegisState, recreateUserId,
                lastOptions != null && lastOptions.getSandboxResourceLoader() != null
                        ? lastOptions.getSandboxResourceLoader() : sandboxResourceLoader,
                strategy);

        // 如果没有 sessionId，生成新的
        if (sessionId == null || sessionId.isEmpty()) {
            aegisState.setSessionId(java.util.UUID.randomUUID().toString());
        }

        log.info("[sandbox-aegis] 沙箱重新创建成功: sessionId={}, instanceId={}, podName={}, namespace={}",
                aegisState.getSessionId(), allocationResult.getInstanceId(),
                allocationResult.getPodName(), allocationResult.getNamespace());

        return new AegisSandbox(aegisState, sandboxBackend, coordinator);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // 清理由 AegisSandbox.shutdown() 处理（调用 ISandboxBackend.destroy）
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "序列化 Aegis 沙箱状态失败", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            SandboxState state = objectMapper.readValue(json, SandboxState.class);
            // P0-08: 使用内置的 minioSnapshotClient 重新绑定
            rebindRemoteSnapshot(state, null);
            return state;
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "反序列化 Aegis 沙箱状态失败", e);
        }
    }

    /**
     * P0-08: 带 snapshotSpec 参数的反序列化，用于恢复会话时重新绑定 RemoteSnapshotClient。
     *
     * <p>参考 {@code DockerSandboxClient#deserializeState(String, SandboxSnapshotSpec)} 实现，
     * 在反序列化后重新绑定 {@link RemoteSnapshotClient}，避免
     * {@link RemoteSandboxSnapshot} 因 client 为 null 抛出异常。
     */
    @Override
    public SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) {
        try {
            SandboxState state = objectMapper.readValue(json, SandboxState.class);
            // 优先使用传入的 snapshotSpec，否则使用内置的 minioSnapshotClient
            SandboxSnapshotSpec effectiveSpec = snapshotSpec;
            if (effectiveSpec == null && minioSnapshotClient != null) {
                effectiveSpec = new RemoteSnapshotSpec(minioSnapshotClient);
            }
            rebindRemoteSnapshot(state, effectiveSpec);
            return state;
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "反序列化 Aegis 沙箱状态失败", e);
        }
    }

    /**
     * P0-08: 重新绑定 RemoteSnapshotClient 到 RemoteSandboxSnapshot。
     *
     * <p>反序列化后 {@link RemoteSandboxSnapshot} 的 client 字段为 null（不可序列化），
     * 需要通过此方法重新绑定，避免 {@code RemoteSnapshotClient is not bound to snapshot id} 异常。
     *
     * @param state       沙箱状态
     * @param snapshotSpec 快照规范（用于获取 RemoteSnapshotClient）
     */
    private void rebindRemoteSnapshot(SandboxState state, SandboxSnapshotSpec snapshotSpec) {
        if (state == null) {
            return;
        }

        // 优先使用传入的 snapshotSpec
        if (snapshotSpec != null && snapshotSpec instanceof RemoteSnapshotSpec remoteSpec) {
            SandboxSnapshot snapshot = state.getSnapshot();
            if (snapshot instanceof RemoteSandboxSnapshot remoteSnapshot) {
                log.debug("P0-08: 重新绑定 RemoteSnapshotClient: snapshotId={}", remoteSnapshot.getId());
                state.setSnapshot(new RemoteSandboxSnapshot(remoteSpec.getClient(), remoteSnapshot.getId()));
            }
            return;
        }

        // 否则使用内置的 minioSnapshotClient
        if (minioSnapshotClient != null) {
            SandboxSnapshot snapshot = state.getSnapshot();
            if (snapshot instanceof RemoteSandboxSnapshot remoteSnapshot) {
                log.debug("P0-08: 使用内置客户端重新绑定 RemoteSnapshotClient: snapshotId={}", remoteSnapshot.getId());
                state.setSnapshot(new RemoteSandboxSnapshot(minioSnapshotClient, remoteSnapshot.getId()));
            }
        }
    }
}