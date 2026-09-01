package com.aegis.runtime.infrastructure.sandbox.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxState;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.spi.ISandboxBackend;

/**
 * Aegis 沙箱可序列化状态。
 *
 * <p>继承 {@link SandboxState}，扩展 Aegis 侧业务字段（instanceId / tenantId / slotKey），
 * 持久化到 RedisSession 以支持跨调用恢复。容器 ID 即 Aegis 的 instanceId，
 * 由 {@link com.aegis.core.spi.ISandboxBackend#create} 返回。
 *
 * <p>P5-2 扩展：引入 {@code sessionId} + {@code isolationStrategy}，
 * 使共享 Pod 模式下的工作区按 sessionId 子目录路由，解决并发会话文件污染。
 * 工作区根路径按策略动态派生：
 * <ul>
 *   <li>SHARED_PER_SCOPE：{@code /workspace/{tenantId}/{agentId}/{sessionId}}</li>
 *   <li>DEDICATED_PER_SESSION：{@code /workspace/{tenantId}/{sessionId}}</li>
 *   <li>SHARED_WITH_QUOTA：{@code /workspace/{tenantId}/{sessionId}}</li>
 * </ul>
 *
 * @author wang.zhen
 */
@JsonTypeName("aegis")
@JsonIgnoreProperties(ignoreUnknown = true)
public class AegisSandboxState extends SandboxState {

    /** Aegis 沙箱实例 ID（UUID 格式，业务主键） */
    private String instanceId;

    /** K8s Pod 名称（Kubernetes 后端必需） */
    private String podName;

    /** K8s 命名空间（Kubernetes 后端必需） */
    private String namespace;

    /** 租户 ID，用于 ISandboxBackend 所有操作的租户隔离 */
    private Long tenantId;

    /** 沙箱槽位键，由 IsolationScope + 业务主键合成 */
    private String slotKey;

    /** 隔离作用域（P0 新增：用于 shutdown 时决定回收策略） */
    private IsolationScope isolationScope;

    /** Docker 镜像名，用于容器重建 */
    private String image;

    /**
     * 容器内工作区根路径。
     *
     * <p>P5-2：允许上层显式设置；若未设置则按 sessionId + strategy 自动派生。
     */
    private String workspaceRoot;

    /** 是否由 Aegis 管理容器生命周期（true=管理，false=外部注入） */
    private boolean containerOwned = true;

    /**
     * P5-2：隔离策略，决定 workspaceRoot 的派生规则与释放语义。
     */
    private IsolationStrategy isolationStrategy = IsolationStrategy.SHARED_PER_SCOPE;

    /**
     * P5-2：Agent ID，用于构造 SHARED_PER_SCOPE 下的 workspace 子路径。
     */
    private Long agentId;

    /**
     * P0-2：智能体类型（UNIVERSAL/APPLICATION/SYSTEM），用于沙箱重建（recreate）时
     * 的池路由决策。随状态序列化，缺失时（旧状态）由 Coordinator 按默认池路由。
     */
    private String agentType;

    /**
     * A5：资源装载指纹（装载清单 SHA-256）。
     *
     * <p>SandboxResourceLoader 装载成功后写入；resume/复用同一实例时据此
     * 判断资源集是否变化，一致则跳过重新装载。随状态序列化到 DistributedStore。
     */
    private String resourceFingerprint;

    public boolean isContainerOwned() {
        return containerOwned;
    }

    public void setContainerOwned(boolean containerOwned) {
        this.containerOwned = containerOwned;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * 获取 K8s 格式的实例标识：namespace/podName。
     *
     * <p>KubernetesSandboxBackend 需要此格式来定位 Pod。
     * 当 podName 或 namespace 缺失时，返回 null 表示状态无效，
     * 上层应检测到并强制走新创建路径。
     *
     * @return K8s 资源标识，格式为 {@code namespace/podName}，或 null 表示状态无效
     */
    public String getK8sResourceId() {
        if (namespace != null && podName != null && !namespace.isEmpty() && !podName.isEmpty()) {
            return namespace + "/" + podName;
        }
        return null; // 状态无效，强制上层走新创建路径
    }
    
    /**
     * 检查沙箱状态是否包含有效的 K8s 资源标识。
     *
     * @return true 表示 podName 和 namespace 都已设置
     */
    public boolean hasValidK8sResource() {
        return namespace != null && podName != null && !namespace.isEmpty() && !podName.isEmpty();
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getSlotKey() {
        return slotKey;
    }

    public void setSlotKey(String slotKey) {
        this.slotKey = slotKey;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    public void setIsolationScope(IsolationScope isolationScope) {
        this.isolationScope = isolationScope;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public IsolationStrategy getIsolationStrategy() {
        return isolationStrategy;
    }

    public void setIsolationStrategy(IsolationStrategy isolationStrategy) {
        this.isolationStrategy = isolationStrategy;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getResourceFingerprint() {
        return resourceFingerprint;
    }

    public void setResourceFingerprint(String resourceFingerprint) {
        this.resourceFingerprint = resourceFingerprint;
    }

    /**
     * P5-2：按策略派生工作区根路径。
     *
     * <p>若外部已显式设置 {@link #workspaceRoot}，直接返回；否则按策略派生：
     * <ul>
     *   <li>SHARED_PER_SCOPE：/workspace/{tenantId}/{agentId}/{sessionId}</li>
     *   <li>DEDICATED_PER_SESSION / SHARED_WITH_QUOTA：/workspace/{tenantId}/{sessionId}</li>
     * </ul>
     *
     * @return 容器内工作区根路径
     */
    public String resolveWorkspaceRoot() {
        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
            return workspaceRoot;
        }
        String base = "/workspace";
        String sessionId = getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return base;
        }
        IsolationStrategy strategy = isolationStrategy != null ? isolationStrategy : IsolationStrategy.SHARED_PER_SCOPE;
        String tenantPart = tenantId != null ? String.valueOf(tenantId) : "default";
        return switch (strategy) {
            case SHARED_PER_SCOPE -> {
                String agentPart = agentId != null ? String.valueOf(agentId) : "default";
                yield base + "/" + tenantPart + "/" + agentPart + "/" + sessionId;
            }
            case DEDICATED_PER_SESSION, SHARED_WITH_QUOTA -> base + "/" + tenantPart + "/" + sessionId;
        };
    }

    @Override
    public String toString() {
        return "AegisSandboxState{instanceId='" + instanceId + "', slotKey='" + slotKey
                + "', sessionId='" + getSessionId() + "', strategy=" + isolationStrategy
                + ", workspaceRootReady=" + isWorkspaceRootReady() + '}';
    }
}