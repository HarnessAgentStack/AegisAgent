package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Kubernetes 沙箱客户端。
 *
 * <p>实现 AgentScope {@link SandboxClient} SPI，将沙箱生命周期委托给
 * {@link ISandboxBackend}（Aegis 自定义后端协议）和
 * {@link AegisSandboxCoordinator}（沙箱资源协调器）。
 *
 * <h3>与 AegisSandboxClient 的关系</h3>
 * <p>{@code AegisSandboxClient} 是当前生产使用的实现，已与 AegisSandboxState 深度耦合。
 * 本类是对齐 AgentScope SPI 的标准化实现，使用 K8s 语义命名。
 * 后续将逐步迁移 AegisSandboxClient 的内部实现到本类，最终 AegisSandboxClient
 * 将退化为 K8sSandboxClient 的类型别名或直接被替换。</p>
 *
 * <h3>create 流程</h3>
 * <ol>
 *   <li>从 options 提取隔离上下文（tenantId / isolationScope / slotKey）</li>
 *   <li>通过 {@link AegisSandboxCoordinator#allocateSlot} 分配沙箱实例</li>
 *   <li>构建 {@link AegisSandboxState}，填充 instanceId / slotKey / isolationScope</li>
 *   <li>创建 {@link AegisSandbox} 实例</li>
 * </ol>
 *
 * <h3>降级路径</h3>
 * <p>当 coordinator 或隔离上下文缺失时，退化为直接通过 ISandboxBackend.create()
 * 创建 Pod（仅测试场景使用）。</p>
 *
 * @author wang.zhen
 * @see AegisSandboxClient
 * @see ISandboxBackend
 * @see AegisSandboxCoordinator
 */
@Slf4j
public class K8sSandboxClient implements SandboxClient<K8sSandboxClientOptions> {

    private final ObjectMapper objectMapper;
    private final ISandboxBackend sandboxBackend;
    private final AegisSandboxCoordinator coordinator;

    /**
     * 默认构造函数（用于反射创建，coordinator 为 null 时走降级路径）。
     */
    public K8sSandboxClient() {
        this(null, null);
    }

    /**
     * 注入后端和协调器。
     *
     * @param sandboxBackend ISandboxBackend 实现（K8s/Docker/Process）
     * @param coordinator    AegisSandboxCoordinator 实例
     */
    public K8sSandboxClient(ISandboxBackend sandboxBackend, AegisSandboxCoordinator coordinator) {
        this.sandboxBackend = sandboxBackend;
        this.coordinator = coordinator;
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new HarnessSandboxJacksonModule());
        mapper.registerSubtypes(AegisSandboxState.class);
        this.objectMapper = mapper;
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec,
                          SandboxSnapshotSpec snapshotSpec,
                          K8sSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();

        String image = options != null ? options.getImage() : "python:3.11-slim";
        String workspaceRoot = options != null ? options.getWorkspaceRoot() : "/workspace";

        AegisSandboxState state = new AegisSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(image);
        state.setWorkspaceRoot(workspaceRoot);
        state.setContainerOwned(true);
        state.setWorkspaceRootReady(false);

        // 从 options 提取隔离上下文
        IsolationScope scope = options != null ? options.getIsolationScope() : null;
        String slotKey = options != null ? options.getSlotKey() : null;
        Long tenantId = options != null ? options.getTenantId() : null;
        state.setIsolationScope(scope);
        state.setSlotKey(slotKey);
        state.setTenantId(tenantId);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        if (sandboxBackend == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "ISandboxBackend 未注入，K8sSandboxClient 无法创建沙箱");
        }

        // 通过 Coordinator 统一分配沙箱实例
        if (coordinator != null && scope != null && slotKey != null && tenantId != null) {
            Long userId = options != null ? options.getUserId() : null;
            Long agentId = options != null ? options.getAgentId() : null;
            var allocationResult = coordinator.allocateSlot(scope, slotKey, tenantId, userId, agentId, sessionId);
            state.setInstanceId(allocationResult.getInstanceId());
            state.setPodName(allocationResult.getPodName());
            state.setNamespace(allocationResult.getNamespace());
            log.info("[sandbox-k8s] Coordinator 分配沙箱: sessionId={}, instanceId={}, podName={}, namespace={}, scope={}, slotKey={}",
                    sessionId, allocationResult.getInstanceId(), allocationResult.getPodName(),
                    allocationResult.getNamespace(), scope, slotKey);
        } else {
            // 降级路径：直接创建 Pod
            log.warn("[sandbox-k8s] Coordinator 未注入或上下文缺失，退化为直接创建（仅测试适用）");
            try {
                String instanceId = sandboxBackend.create(tenantId, image,
                        options != null ? options.getCpu() : 1.0,
                        options != null ? options.getMemoryMb() : 2048);
                state.setInstanceId(instanceId);
                log.info("[sandbox-k8s] 退化路径直接创建沙箱: sessionId={}, instanceId={}",
                        sessionId, instanceId);
            } catch (Exception e) {
                throw new SandboxException.SandboxConfigurationException(
                        "退化路径创建沙箱失败: " + e.getMessage(), e);
            }
        }

        log.debug("[sandbox-k8s] 创建沙箱: sessionId={}, image={}", sessionId, image);
        return new AegisSandbox(state, sandboxBackend, coordinator);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof AegisSandboxState aegisState)) {
            throw new IllegalArgumentException(
                    "期望 AegisSandboxState 但收到: " + state.getClass().getName());
        }
        if (sandboxBackend == null) {
            throw new SandboxException.SandboxConfigurationException(
                    "ISandboxBackend 未注入，K8sSandboxClient 无法恢复沙箱");
        }

        // 检查 Pod 是否仍然存活
        String instanceId = aegisState.getInstanceId();
        String k8sResourceId = aegisState.getK8sResourceId();

        if (k8sResourceId != null && aegisState.getTenantId() != null) {
            try {
                boolean alive = sandboxBackend.probeAlive(aegisState.getTenantId(), k8sResourceId);
                if (!alive) {
                    log.warn("恢复沙箱失败: Pod 不存在，需要重新创建: instanceId={}, k8sResourceId={}",
                            instanceId, k8sResourceId);
                    // Pod 不存在时，清除 instanceId 让上层检测到并走新创建路径
                    aegisState.setInstanceId(null);
                    aegisState.setPodName(null);
                    aegisState.setNamespace(null);
                } else {
                    log.info("恢复沙箱: sessionId={}, instanceId={}, pod 存活",
                            aegisState.getSessionId(), instanceId);
                }
            } catch (Exception e) {
                log.warn("恢复沙箱时探活异常，标记为需要重建: instanceId={}, error={}",
                        instanceId, e.getMessage());
                aegisState.setInstanceId(null);
                aegisState.setPodName(null);
                aegisState.setNamespace(null);
            }
        } else {
            log.info("恢复沙箱: sessionId={}, instanceId={}（无 K8s 资源标识，将走新创建路径）",
                    aegisState.getSessionId(), instanceId);
        }

        return new AegisSandbox(aegisState, sandboxBackend, coordinator);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // 清理由 AegisSandbox.shutdown() 处理
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "序列化沙箱状态失败", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "反序列化沙箱状态失败", e);
        }
    }
}