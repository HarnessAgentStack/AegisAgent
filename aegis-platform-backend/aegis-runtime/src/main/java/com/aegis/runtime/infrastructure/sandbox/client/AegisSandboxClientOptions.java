package com.aegis.runtime.infrastructure.sandbox.client;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.integration.agent.AegisAgentInstanceManager;

/**
 * Aegis 沙箱客户端选项。
 *
 * <p>继承 {@link SandboxClientOptions}，type 标识为 {@code "aegis"}，
 * 由 {@link AegisSandboxFilesystemSpec#clientOptions()} 返回。
 * {@link #createClient()} 工厂方法返回 {@link AegisSandboxClient} 实例。
 *
 * <p>与 DockerSandboxClientOptions 不同，Aegis 的容器创建参数（image/cpu/memory）
 * 由 {@link com.aegis.core.spi.ISandboxBackend} 内部配置决定，无需在此重复声明。
 *
 * <h3>P0 改造：携带租户上下文</h3>
 * <p>create() 时由 {@link AegisSandboxClient} 读取这些字段，
 * 调用 {@link AegisSandboxCoordinator#allocateSlot} 完成统一沙箱分配。
 *
 * <h3>P5-2 改造：携带会话级上下文</h3>
 * <p>新增 {@code sessionId}、{@code isolationStrategy}、{@code agentId} 字段，
 * 供 {@link AegisSandboxClient} 在创建沙箱时构造基于 sessionId 的 workspaceRoot。
 *
 * <h3>P0-08 改造：携带 MinioSnapshotClient</h3>
 * <p>新增 {@code minioSnapshotClient} 字段，供
 * {@link AegisSandboxClient#deserializeState} 在恢复会话时重新绑定 RemoteSnapshotClient。
 *
 * @author wang.zhen
 */
public class AegisSandboxClientOptions extends SandboxClientOptions {

    /** MinIO 快照客户端（P0-08: 用于反序列化时重新绑定 RemoteSnapshotClient） */
    private MinioSnapshotClient minioSnapshotClient;

    /** 容器内工作区根路径，默认 /workspace（P5-2: 作为基路径，实际路径追加 sessionId） */
    private String workspaceRoot = "/workspace";

    /** Docker 镜像名，传给 ISandboxBackend.create */
    private String image = "python:3.11-slim";

    /** CPU 核心数，传给 ISandboxBackend.create */
    private double cpu = 1.0;

    /** 内存上限（MB），传给 ISandboxBackend.create */
    private int memoryMb = 2048;

    // === P0：租户上下文（由 AegisAgentInstanceManager.buildAgent() 设置）===

    /** 租户 ID */
    private Long tenantId;

    /** 用户 ID（USER scope 必填） */
    private Long userId;

    /** 智能体 ID（AGENT scope 必填） */
    private Long agentId;

    /** 隔离作用域 */
    private IsolationScope isolationScope;

    /** 沙箱槽位键（由 SlotKeyParser.build() 预计算） */
    private String slotKey;

    // === P5-2：会话级隔离字段 ===

    /** 会话 ID，用于派生 workspaceRoot 子目录 */
    private String sessionId;

    /** 隔离策略，决定 workspaceRoot 派生规则与释放语义 */
    private IsolationStrategy isolationStrategy = IsolationStrategy.SHARED_PER_SCOPE;

    /** P0-2：智能体类型（UNIVERSAL/APPLICATION/SYSTEM），用于 Coordinator 池路由决策 */
    private String agentType;

    /**
     * A5：资源装载器。create() 分配成功后异步触发装载
     * （不阻塞 Agent 构建），由 AegisAgentInstanceManager 注入。
     */
    private com.aegis.runtime.service.sandbox.SandboxResourceLoader sandboxResourceLoader;

    /**
     * T1 沙箱惰性分配：lazy=true 时 {@link AegisSandboxClient#create} 构建占位沙箱
     * （instanceId=null），不触发 allocateSlot，将 Pod 占用推迟到首次沙箱工具调用。
     */
    private boolean lazy;

    @Override
    public String getType() {
        return "aegis";
    }

    /**
     * P0-08: 创建 AegisSandboxClient 实例，注入 MinioSnapshotClient。
     *
     * <p>当 minioSnapshotClient 不为 null 时，AegisSandboxClient 会在反序列化时
     * 使用它重新绑定 RemoteSnapshotClient，避免 RemoteSnapshotClient is not bound 异常。
     */
    @Override
    public SandboxClient<AegisSandboxClientOptions> createClient() {
        return new AegisSandboxClient(null, null, minioSnapshotClient);
    }

    public MinioSnapshotClient getMinioSnapshotClient() {
        return minioSnapshotClient;
    }

    public void setMinioSnapshotClient(MinioSnapshotClient minioSnapshotClient) {
        this.minioSnapshotClient = minioSnapshotClient;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public IsolationStrategy getIsolationStrategy() {
        return isolationStrategy;
    }

    public void setIsolationStrategy(IsolationStrategy isolationStrategy) {
        this.isolationStrategy = isolationStrategy;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    /** T1：是否懒模式（构建期不分配沙箱，由 LazyAegisSandboxClient 识别） */
    public boolean isLazy() {
        return lazy;
    }

    /** T1：设置懒模式标志 */
    public void setLazy(boolean lazy) {
        this.lazy = lazy;
    }

    public com.aegis.runtime.service.sandbox.SandboxResourceLoader getSandboxResourceLoader() {
        return sandboxResourceLoader;
    }

    public void setSandboxResourceLoader(
            com.aegis.runtime.service.sandbox.SandboxResourceLoader sandboxResourceLoader) {
        this.sandboxResourceLoader = sandboxResourceLoader;
    }
}