package com.aegis.runtime.infrastructure.sandbox.client;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

/**
 * Kubernetes 沙箱客户端选项。
 *
 * <p>继承 AgentScope {@link SandboxClientOptions}，定义 K8s 后端特有的配置项。
 * 与 AgentScope 官方 {@code DockerSandboxClientOptions} 对应，用于在
 * {@link com.aegis.runtime.infrastructure.sandbox.client.K8sSandboxClient} 中
 * 创建和恢复 K8s Pod 沙箱实例。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code namespace} — K8s 命名空间，默认 aegis-sbx-t{tenantId}-{poolType}</li>
 *   <li>{@code cpu} — CPU 配额（核），默认 1.0</li>
 *   <li>{@code memoryMb} — 内存配额（MB），默认 2048</li>
 *   <li>{@code workspaceRoot} — 容器内工作区路径，默认 /workspace</li>
 *   <li>{@code image} — 基础镜像，默认 python:3.11-slim</li>
 *   <li>{@code serviceAccount} — K8s ServiceAccount 名称</li>
 *   <li>{@code tenantId / isolationScope / slotKey} — 隔离上下文，用于 Coordinator 分配</li>
 * </ul>
 *
 * @author wang.zhen
 */
public class K8sSandboxClientOptions extends SandboxClientOptions {

    /** K8s 命名空间（如 aegis-sbx-t0-light） */
    private String namespace = "aegis-sbx";

    /** CPU 配额（核） */
    private double cpu = 1.0;

    /** 内存配额（MB） */
    private int memoryMb = 2048;

    /** 基础镜像 */
    private String image = "python:3.11-slim";

    /** K8s ServiceAccount */
    private String serviceAccount = "aegis-sandbox";

    /** 容器内工作区根路径 */
    private String workspaceRoot = "/workspace";

    // === 隔离上下文（Coordinator 分配所需）===

    /** 租户 ID */
    private Long tenantId;

    /** 用户 ID（USER scope 必填） */
    private Long userId;

    /** 智能体 ID（AGENT scope 必填） */
    private Long agentId;

    /** 隔离作用域 */
    private IsolationScope isolationScope;

    /** 沙箱槽位键 */
    private String slotKey;

    @Override
    public String getType() {
        return "k8s";
    }

    @Override
    public SandboxClient<K8sSandboxClientOptions> createClient() {
        return new K8sSandboxClient();
    }

    @Override
    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public double getCpu() {
        return cpu;
    }

    public void setCpu(double cpu) {
        this.cpu = cpu;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(int memoryMb) {
        this.memoryMb = memoryMb;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    public void setServiceAccount(String serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAgentId() {
        return agentId;
    }

    public void setAgentId(Long agentId) {
        this.agentId = agentId;
    }

    public IsolationScope getIsolationScope() {
        return isolationScope;
    }

    public void setIsolationScope(IsolationScope isolationScope) {
        this.isolationScope = isolationScope;
    }

    public String getSlotKey() {
        return slotKey;
    }

    public void setSlotKey(String slotKey) {
        this.slotKey = slotKey;
    }
}