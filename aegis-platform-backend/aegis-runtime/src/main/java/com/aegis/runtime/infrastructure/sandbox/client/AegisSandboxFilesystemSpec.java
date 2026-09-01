package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.SandboxFilesystemSpec;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.extern.slf4j.Slf4j;

/**
 * Aegis 自定义沙箱后端 Spec。
 *
 * <p>继承 {@link SandboxFilesystemSpec}，将 AgentScope 沙箱操作桥接到 Aegis
 * {@link ISandboxBackend} + sbx_pool 资源池。
 *
 * <h3>P0 改造：统一沙箱分配</h3>
 * <p>新增 {@link AegisSandboxCoordinator} 注入，通过 {@link #coordinator} 方法设置。
 * Coordinator 非 null 时，{@link AegisSandboxClient#create} 走统一分配路径，
 * 通过 {@link AegisSandboxCoordinator#allocateSlot} 完成沙箱实例分配。
 *
 * <h3>P5-2 改造：会话级命名空间隔离</h3>
 * <p>新增 {@link #sessionId} + {@link #isolationStrategy} 设置方法，
 * 由上层在 buildAgent 时写入 options，确保每个会话的工作区被路由到独立子目录，
 * 解决共享 Pod 模式下的文件污染与快照串扰问题。
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * AegisSandboxFilesystemSpec spec = new AegisSandboxFilesystemSpec(sandboxBackend)
 *     .coordinator(coordinator)
 *     .isolationScope(IsolationScope.USER)
 *     .tenantContext(tenantId, userId, agentId, isolationScope, slotKey)
 *     .sessionId(sessionId)
 *     .isolationStrategy(IsolationStrategy.SHARED_PER_SCOPE)
 *     .snapshotSpec(new RemoteSnapshotSpec(minioSnapshotClient));
 *
 * HarnessAgent.builder()
 *     .filesystem(spec)
 *     .distributedStore(store)
 *     .build();
 * }</pre>
 *
 * @author wang.zhen
 */
@Slf4j
public class AegisSandboxFilesystemSpec extends SandboxFilesystemSpec {

    private final AegisSandboxClientOptions options = new AegisSandboxClientOptions();
    private SandboxClient<?> client;
    private SandboxSnapshotSpec snapshotSpec = new NoopSnapshotSpec();
    private WorkspaceSpec defaultWorkspaceSpec = new WorkspaceSpec();

    /**
     * 构造 Aegis 沙箱 Spec。
     *
     * @param sandboxBackend Aegis 沙箱后端 SPI（Docker/Process）
     */
    public AegisSandboxFilesystemSpec(ISandboxBackend sandboxBackend) {
        this.client = new AegisSandboxClient(sandboxBackend);
    }

    /**
     * P0 构造函数：注入 backend + coordinator。
     */
    public AegisSandboxFilesystemSpec(ISandboxBackend sandboxBackend,
                                       AegisSandboxCoordinator coordinator) {
        this.client = new AegisSandboxClient(sandboxBackend, coordinator);
    }

    /**
     * P0-08 构造函数：注入 backend + coordinator + minioSnapshotClient。
     *
     * <p>minioSnapshotClient 用于 AegisSandboxClient 在反序列化时重新绑定 RemoteSnapshotClient。
     */
    public AegisSandboxFilesystemSpec(ISandboxBackend sandboxBackend,
                                       AegisSandboxCoordinator coordinator,
                                       MinioSnapshotClient minioSnapshotClient) {
        this(sandboxBackend, coordinator, minioSnapshotClient, null);
    }

    /**
     * A5 构造函数：注入 backend + coordinator + minioSnapshotClient + 资源装载器。
     *
     * <p>资源装载器同步写入 {@link AegisSandboxClientOptions}，
     * create()/recreateSandbox() 分配成功后异步触发 KB/SKILL/MCP 装载。
     */
    public AegisSandboxFilesystemSpec(ISandboxBackend sandboxBackend,
                                       AegisSandboxCoordinator coordinator,
                                       MinioSnapshotClient minioSnapshotClient,
                                       com.aegis.runtime.service.sandbox.SandboxResourceLoader resourceLoader) {
        this.client = new AegisSandboxClient(sandboxBackend, coordinator, minioSnapshotClient, resourceLoader);
        options.setSandboxResourceLoader(resourceLoader);
    }

    /** 无参构造（用于测试或延迟注入 sandboxBackend） */
    public AegisSandboxFilesystemSpec() {
        // client 延迟设置，调用方需通过 client() 方法注入
    }

    public AegisSandboxFilesystemSpec client(SandboxClient<?> client) {
        this.client = client;
        return this;
    }

    public AegisSandboxFilesystemSpec image(String image) {
        options.setImage(image);
        return this;
    }

    public AegisSandboxFilesystemSpec workspaceRoot(String workspaceRoot) {
        options.setWorkspaceRoot(workspaceRoot);
        return this;
    }

    public AegisSandboxFilesystemSpec cpu(double cpu) {
        options.setCpu(cpu);
        return this;
    }

    public AegisSandboxFilesystemSpec memoryMb(int memoryMb) {
        options.setMemoryMb(memoryMb);
        return this;
    }

    public AegisSandboxFilesystemSpec snapshotSpec(SandboxSnapshotSpec snapshotSpec) {
        this.snapshotSpec = snapshotSpec != null ? snapshotSpec : new NoopSnapshotSpec();
        return this;
    }

    public AegisSandboxFilesystemSpec workspaceSpec(WorkspaceSpec workspaceSpec) {
        this.defaultWorkspaceSpec = workspaceSpec != null ? workspaceSpec : new WorkspaceSpec();
        return this;
    }

    /**
     * P0：设置隔离作用域（同时写入 options 供 client 读取）。
     */
    public AegisSandboxFilesystemSpec isolationScope(IsolationScope scope) {
        options.setIsolationScope(scope);
        return this;
    }

    /**
     * P0：一次性设置租户上下文。
     *
     * @param tenantId       租户 ID
     * @param userId         用户 ID
     * @param agentId        智能体 ID
     * @param isolationScope 隔离作用域
     * @param slotKey        沙箱槽位键
     */
    public AegisSandboxFilesystemSpec tenantContext(Long tenantId, Long userId, Long agentId,
                                                     IsolationScope isolationScope, String slotKey) {
        options.setTenantId(tenantId);
        options.setUserId(userId);
        options.setAgentId(agentId);
        options.setIsolationScope(isolationScope);
        options.setSlotKey(slotKey);
        return this;
    }

    /**
     * P5-2：设置会话 ID（用于派生 workspaceRoot 子目录）。
     */
    public AegisSandboxFilesystemSpec sessionId(String sessionId) {
        options.setSessionId(sessionId);
        return this;
    }

    /**
     * P5-2：设置隔离策略（决定 workspaceRoot 派生规则与释放语义）。
     */
    public AegisSandboxFilesystemSpec isolationStrategy(IsolationStrategy strategy) {
        options.setIsolationStrategy(strategy);
        return this;
    }

    /**
     * P0-2：设置智能体类型（供 Coordinator 池路由决策：UNIVERSAL→LIGHT / SYSTEM→HEAVY / 其他→STANDARD）。
     */
    public AegisSandboxFilesystemSpec agentType(String agentType) {
        options.setAgentType(agentType);
        return this;
    }

    /**
     * P0-08：获取客户端选项（用于设置 MinioSnapshotClient 等依赖）。
     */
    public AegisSandboxClientOptions getClientOptions() {
        return options;
    }

    @Override
    protected SandboxClient<?> createClient() {
        return client;
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
        return defaultWorkspaceSpec;
    }
}