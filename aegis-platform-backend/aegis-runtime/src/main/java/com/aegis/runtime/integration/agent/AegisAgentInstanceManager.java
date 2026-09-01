package com.aegis.runtime.integration.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.dto.security.BuiltinToolRiskConfig;
import com.aegis.core.dto.security.ToolRiskInfo;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.integration.ext.TenantSessionKey;
import com.aegis.runtime.integration.middleware.AegisMiddlewareChain;
import com.aegis.runtime.integration.skill.AegisSkillRepository;
import com.aegis.runtime.integration.workspace.WorkspaceMaterializer;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import com.aegis.runtime.service.sandbox.IdleReleaseTracker;
import com.aegis.runtime.service.sandbox.SandboxReadinessGate;
import com.aegis.runtime.infrastructure.sandbox.client.AegisSandboxFilesystemSpec;
import com.aegis.runtime.infrastructure.sandbox.client.LazySandboxFilesystemSpec;
import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import com.aegis.runtime.service.sandbox.SandboxResourceLoader;
import com.aegis.runtime.service.sandbox.SlotKeyParser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.aegis.runtime.integration.workspace.BindingFingerprinter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import com.aegis.runtime.integration.middleware.AegisHitlRuleLoader;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.dto.security.SecurityPolicyContext;
import com.aegis.runtime.service.policy.AegisSecurityPolicyEngine;
import com.aegis.runtime.service.agent.ResourceQueryService;

/**
 * HarnessAgent 实例池管理器。
 *
 * <p>核心职责：按 poolKey 复用 Agent 实例、LRU+TTL 回收、绑定指纹懒刷新、中间件链注册。
 *
 * <h3>poolKey 映射规则</h3>
 * <ul>
 *   <li>SYSTEM / APPLICATION → {@code agentId}：同一智能体多用户共享</li>
 *   <li>UNIVERSAL → {@code userId}：每用户一个独立实例</li>
 * </ul>
 *
 * <h3>绑定指纹懒刷新</h3>
 * <p>池命中时通过 bindingFingerprint 比对当前绑定版本：
 * <ul>
 *   <li>指纹一致 → 直接复用，跳过工具/工作区重建</li>
 *   <li>指纹不一致 → 懒刷新工具链 + 重物化工作区</li>
 * </ul>
 *
 * <h3>回收机制</h3>
 * <ul>
 *   <li>LRU：实例池满时驱逐最久未使用的实例</li>
 *   <li>TTL：空闲超过阈值（默认 30 分钟）自动驱逐</li>
 *   <li>驱逐前调用 {@code agent.close()} 落盘状态并释放沙箱</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class AegisAgentInstanceManager {

    private final DistributedStore distributedStore;
    private final WorkspaceMaterializer workspaceMaterializer;
    private final AegisToolBridge toolBridge;
    /** 中间件链装配器，构建 Agent 时调用 build() 生成中间件列表注入 Builder */
    private final AegisMiddlewareChain middlewareChain;
    private final ISandboxBackend sandboxBackend;
    private final SandboxSnapshotSpec snapshotSpec;
    private final AegisSandboxCoordinator sandboxCoordinator;
    /** 沙箱就绪门控，实例关闭时清理会话绑定，避免命中已释放的沙箱句柄 */
    private final SandboxReadinessGate sandboxReadinessGate;
    /** 空闲释放追踪器，后台周期扫描，长时间未使用的沙箱主动回池 */
    private final IdleReleaseTracker idleReleaseTracker;
    /** 技能仓库，注册到 Builder 后技能中间件自动将可见技能注入系统提示词 */
    private final AegisSkillRepository skillRepository;
    /** MinIO 快照客户端，沙箱反序列化时重新绑定远程快照 */
    private final MinioSnapshotClient minioSnapshotClient;
    /** 沙箱资源装载器，沙箱分配成功后异步物化知识库/技能/MCP 清单到工作区（不阻塞构建） */
    private final SandboxResourceLoader sandboxResourceLoader;
    /** HITL 规则加载器，将数据库审批配置转为权限规则注入权限上下文，运行时由框架权限引擎自动触发审批 */
    private final AegisHitlRuleLoader hitlRuleLoader;

    /** 安全策略引擎，根据资源安全等级生成工具访问决策 */
    @Autowired
    private AegisSecurityPolicyEngine securityPolicyEngine;

    /** 资源查询服务，加载智能体定义获取治理档位 */
    @Autowired
    private ResourceQueryService resourceQueryService;

    @Value("${aegis.runtime.agent-pool.max-size:2000}")
    private int maxSize;

    @Value("${aegis.runtime.agent-pool.idle-timeout-minutes:30}")
    private int idleTimeoutMinutes;

    @Value("${aegis.runtime.agent-pool.clean-interval-minutes:5}")
    private int cleanIntervalMinutes;

    @Value("${aegis.upon.sys-prompt:You are a helpful AI assistant.}")
    private String defaultSysPrompt;

    @Value("${aegis.upon.max-iters:10}")
    private int maxIters;

    @Value("${aegis.runtime.sandbox.enabled:false}")
    private boolean sandboxEnabled;

    /** 沙箱惰性分配开关：开启后构建期不分配 Pod，推迟到首次沙箱工具调用时再分配 */
    @Value("${aegis.runtime.sandbox.lazy-allocation.enabled:true}")
    private boolean lazyAllocationEnabled;

    /** 实例池，读操作无锁并发，结构变更用写锁互斥 */
    private final ConcurrentHashMap<String, AgentEntry> pool = new ConcurrentHashMap<>();

    /** 读写锁：读锁保护查询/复用路径，写锁保护构建/驱逐/清理路径 */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** 后台清理线程 */
    private ScheduledExecutorService cleaner;

    public AegisAgentInstanceManager(DistributedStore distributedStore,
                                     WorkspaceMaterializer workspaceMaterializer,
                                     AegisToolBridge toolBridge,
                                     AegisMiddlewareChain middlewareChain,
                                     ISandboxBackend sandboxBackend,
                                     SandboxSnapshotSpec snapshotSpec,
                                     AegisSandboxCoordinator sandboxCoordinator,
                                     MinioSnapshotClient minioSnapshotClient,
                                     AegisHitlRuleLoader hitlRuleLoader,
                                     AegisSkillRepository skillRepository,
                                     SandboxResourceLoader sandboxResourceLoader,
                                     SandboxReadinessGate sandboxReadinessGate,
                                     IdleReleaseTracker idleReleaseTracker) {
        this.distributedStore = distributedStore;
        this.workspaceMaterializer = workspaceMaterializer;
        this.toolBridge = toolBridge;
        this.middlewareChain = middlewareChain;
        this.sandboxBackend = sandboxBackend;
        this.snapshotSpec = snapshotSpec;
        this.sandboxCoordinator = sandboxCoordinator;
        this.minioSnapshotClient = minioSnapshotClient;
        this.hitlRuleLoader = hitlRuleLoader;
        this.skillRepository = skillRepository;
        this.sandboxResourceLoader = sandboxResourceLoader;
        this.sandboxReadinessGate = sandboxReadinessGate;
        this.idleReleaseTracker = idleReleaseTracker;
    }

    /**
     * 初始化后台清理线程。
     */
    @PostConstruct
    public void init() {
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aegis-agent-pool-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanExpired,
                cleanIntervalMinutes, cleanIntervalMinutes, TimeUnit.MINUTES);
        // T1：复用 cleaner 周期，扫描空闲超阈值沙箱主动回池（§4.5.3）
        cleaner.scheduleAtFixedRate(() -> {
            try {
                if (idleReleaseTracker != null) {
                    idleReleaseTracker.scanAndRelease();
                }
            } catch (Exception e) {
                log.warn("IdleReleaseTracker 周期扫描异常: {}", e.getMessage());
            }
        }, cleanIntervalMinutes, cleanIntervalMinutes, TimeUnit.MINUTES);
        log.info("AegisAgentInstanceManager 已初始化: maxSize={}, idleTimeout={}min, cleanInterval={}min",
                maxSize, idleTimeoutMinutes, cleanIntervalMinutes);
        // 验证沙箱配置状态
        log.info("沙箱配置状态: sandboxEnabled={}, sandboxBackend={}, sandboxCoordinator={}",
                sandboxEnabled,
                sandboxBackend != null ? sandboxBackend.getClass().getSimpleName() : "null",
                sandboxCoordinator != null ? "available" : "null");
    }

    /**
     * 获取或构建 Agent 实例。
     *
     * <p>读路径(复用)无锁并发，写路径(构建)加写锁。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param agentType 智能体类型：UNIVERSAL / APPLICATION / SYSTEM
     * @param agentId   智能体ID
     * @param sysPrompt 智能体系统提示词（null 时使用默认值）
     * @param bindings  动态绑定列表（来自模板，null 时 buildAgent 内部从 DB 加载）
     * @param modelTier 模型档位（STANDARD/LIGHT/STRONG）
     * @param isolationStrategy 沙箱隔离策略（为 null 时使用默认 SHARED_PER_SCOPE）
     * @param sessionMcpServiceIds 会话级临时选择的 MCP 服务ID列表（可选）
     * @return HarnessAgent 实例
     */
    public HarnessAgent acquireOrBuild(String sessionId, long tenantId, long userId,
                                       String agentType, long agentId, String sysPrompt,
                                       List<AgentBinding> bindings,
                                       List<Tool> preloadedTools,
                                       String modelTier,
                                       IsolationStrategy isolationStrategy,
                                       List<Long> sessionMcpServiceIds,
                                       com.aegis.runtime.service.agent.AssemblyResourceContext resources) {
        // ★ Phase 1.1: pool key 对齐 IsolationScope（SYSTEM/APPLICATION→agentId, UNIVERSAL→userId）
        String poolKey = computePoolKey(agentType, agentId, userId, tenantId);

        // ★ Phase 1.2: 计算当前绑定指纹，用于池命中后的懒刷新判定
        String currentBindingFp = BindingFingerprinter.fingerprint(bindings);

        // 读路径持有读锁，防止 evict/cleanExpired 在 get 与 return 之间关闭 Agent
        rwLock.readLock().lock();
        try {
            AgentEntry entry = pool.get(poolKey);
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis();
                // ★ Phase 1.3: fingerprint 比对 — 一致则直接复用，跳过 Toolkit/Workspace 重建
                if (currentBindingFp.equals(entry.bindingFingerprint)) {
                    log.debug("复用 Agent 实例(读锁): poolKey={}, agentType={}, agentId={}, fp一致, poolSize={}",
                            poolKey, agentType, agentId, pool.size());
                    return entry.agent;
                }
                // ★ Phase 1.4: 指纹不一致 → 懒刷新 Toolkit（remove + register）+ 重物化 Workspace
                log.info("fingerprint mismatch, refresh toolkit: poolKey={}, agentId={}, oldFp={}, newFp={}",
                        poolKey, agentId, entry.bindingFingerprint, currentBindingFp);
                refreshToolkit(entry, agentId, preloadedTools, tenantId, userId, sessionMcpServiceIds, agentType, resources);
                materializeWorkspace(agentType, agentId, userId, bindings);
                entry.bindingFingerprint = currentBindingFp;
                return entry.agent;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // 写路径加写锁 —— 构建/驱逐
        // 写锁内仅收集待关闭 entry，写锁释放后再执行 closeAgent（I/O 移出锁）
        List<AgentEntry> toCloseAfterLock = new ArrayList<>();
        rwLock.writeLock().lock();
        try {
            // Double-check after acquiring write lock
            AgentEntry entry = pool.get(poolKey);
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis();
                if (currentBindingFp.equals(entry.bindingFingerprint)) {
                    return entry.agent;
                }
                // 指纹不一致（双检窗口期 admin 修改了 binding）
                refreshToolkit(entry, agentId, preloadedTools, tenantId, userId, sessionMcpServiceIds, agentType, resources);
                materializeWorkspace(agentType, agentId, userId, bindings);
                entry.bindingFingerprint = currentBindingFp;
                return entry.agent;
            }

            // 实例池满时驱逐最旧实例（仅从池中移除，关闭延后到锁外）
            while (pool.size() >= maxSize) {
                AgentEntry evicted = evictOldest();
                if (evicted != null) {
                    toCloseAfterLock.add(evicted);
                }
            }

            AgentEntry newEntry = buildAgent(poolKey, sessionId, tenantId, userId, agentType, agentId,
                    sysPrompt, bindings, preloadedTools, modelTier, isolationStrategy, sessionMcpServiceIds,
                    currentBindingFp, resources);
            pool.put(poolKey, newEntry);
            log.info("构建 Agent 实例: poolKey={}, agentType={}, agentId={}, tenantId={}, poolSize={}",
                    poolKey, agentType, agentId, tenantId, pool.size());
            return newEntry.agent;
        } finally {
            rwLock.writeLock().unlock();
            // 写锁释放后同步关闭被驱逐的 Agent 实例（避免 I/O 阻塞写锁）
            for (AgentEntry e : toCloseAfterLock) {
                closeAgent(e);
            }
        }
    }

    /**
     * 计算实例池 key（对齐 IsolationScope，决定共享粒度）。
     *
     * <ul>
     *   <li>SYSTEM → agentId（全局唯一，所有用户共享同一 HarnessAgent）</li>
     *   <li>APPLICATION → agentId（智能体唯一，所有用户共享）</li>
     *   <li>UNIVERSAL → userId（每用户一个，通用智能体平台单例）</li>
     * </ul>
     *
     * <p>与 AgentScope IsolationScope 一致：SYSTEM→GLOBAL, APPLICATION→AGENT, UNIVERSAL→USER。
     */
    private String computePoolKey(String agentType, long agentId, long userId, long tenantId) {
        // P2-10：poolKey 加 tenantId 前缀，把"同池实例必同租户"从隐式不变量变为结构保证（防御纵深）。
        // 原仅 agentType+(agentId|userId)，跨租户同 agentId 会撞 poolKey 共享 AgentEntry（含 permissionContext/toolkit）。
        String tenantPart = "T" + tenantId + ":";
        return switch (agentType) {
            case "SYSTEM" -> tenantPart + "SYS:" + agentId;
            case "APPLICATION" -> tenantPart + "APP:" + agentId;
            case "UNIVERSAL" -> tenantPart + "UNI:" + userId;
            default -> tenantPart + "APP:" + agentId;
        };
    }

    // 绑定指纹计算已抽至 BindingFingerprinter.fingerprint，与 WorkspaceMaterializer 共享同一实现

    /**
     * ★ Phase 1.4: 懒刷新 Toolkit。
     *
     * <p>当 pool 命中但 bindingFingerprint 不一致（admin 侧修改了 agent_binding）时，
     * 通过 {@link HarnessAgent#getToolkit()} 拿到已装配的 Toolkit，
     * 先 {@link Toolkit#removeTool} 逐个移除旧工具，再通过 toolBridge 重新 resolveTools + resolveMcpTools*，
     * 避免 {@code new HarnessAgent.Builder()} 重建整个 Agent 带来的 500-2000ms 冷启动开销。
     *
     * <p>前提：AgentScope 2.0.2 {@link Toolkit} 默认 {@code allowToolDeletion=true}，
     * {@code removeTool} 不抛异常；且单池入口（同一 IsolationScope 内同一 poolKey 只有一个 entry），
     * 多线程并发修改 Toolkit 的概率可以忽略。
     */
    private void refreshToolkit(AgentEntry entry, long agentId, List<Tool> preloadedTools,
                                 long tenantId, long userId, List<Long> sessionMcpServiceIds,
                                 String agentType,
                                 com.aegis.runtime.service.agent.AssemblyResourceContext resources) {
        Toolkit toolkit = entry.agent.getToolkit();
        // 1. 移除全部旧工具（浅拷贝避免 ConcurrentModification）
        List<String> oldNames = new ArrayList<>(toolkit.getToolNames());
        for (String name : oldNames) {
            toolkit.removeTool(name);
        }
        log.info("refreshToolkit: removed {} old tools from poolKey={}, agentId={}",
                oldNames.size(), entry.poolKey, agentId);

        // 2. 重新加载工具（与 loadToolkit 完全相同的 resolve 逻辑）
        boolean useTemplate = preloadedTools != null && !preloadedTools.isEmpty();
        if (useTemplate) {
            toolBridge.resolveTools(toolkit, preloadedTools);
        } else {
            // T3/T4：从装配期资源上下文加载，替代按 agentId 的 DB 直查
            toolBridge.resolveTools(toolkit, resources);
        }
        if ("UNIVERSAL".equals(agentType)) {
            toolBridge.resolveMcpToolsForSubscriptions(toolkit, tenantId, userId);
            // 与 loadToolkit 保持一致：GLOBAL 系统技能（skill_creator）+ 用户订阅技能同步重新注册
            toolBridge.resolveGlobalSkillAsTools(toolkit);
            toolBridge.resolveSubscribedSkillAsTools(toolkit, tenantId, userId);
        }
        if (sessionMcpServiceIds != null && !sessionMcpServiceIds.isEmpty()) {
            toolBridge.resolveMcpToolsForServiceIds(toolkit, sessionMcpServiceIds, tenantId);
        }
        log.info("refreshToolkit: agentId={}, agentType={}, source={}, mcpRefreshApplied=true",
                agentId, agentType, useTemplate ? "template" : "DB");
    }

    /**
     * 获取实例池统计信息。
     */
    public PoolStats getStats() {
        rwLock.readLock().lock();
        try {
            return new PoolStats(pool.size(), maxSize, idleTimeoutMinutes);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 构建新 HarnessAgent 实例。
     *
     * <p>集成 WorkspaceMaterializer（资源物化）、AegisToolBridge（工具桥接）、
     * AegisSandboxFilesystemSpec（沙箱桥接）。
     *
     * <h3>构建流程</h3>
     * <ol>
     *   <li>{@link #materializeWorkspace} 物化绑定资源到 RedisStore</li>
     *   <li>{@link #loadToolkit} 加载绑定的工具列表到 Toolkit</li>
     *   <li>{@link #configureAgentBuilder} 装配 HarnessAgent.Builder 基础属性</li>
     *   <li>{@link #configureFilesystem} 按智能体类型配置文件系统（沙箱 or Remote）</li>
     * </ol>
     *
     * @param poolKey  Phase 1.1 计算的实例池 key（对齐 IsolationScope）
     * @param sessionId 会话 ID（用于 filesystem workspace 子目录隔离）
     * @param bindingFingerprint Phase 1.2 预计算的绑定资源指纹，写入 AgentEntry 供懒刷新判定
     * @return AgentEntry（含 Agent 实例与沙箱上下文）
     */
    private AgentEntry buildAgent(String poolKey, String sessionId, long tenantId, long userId,
                                    String agentType, long agentId, String sysPrompt,
                                    List<AgentBinding> bindings,
                                    List<Tool> preloadedTools,
                                    String modelTier,
                                    IsolationStrategy isolationStrategy,
                                    List<Long> sessionMcpServiceIds,
                                    String bindingFingerprint,
                                    com.aegis.runtime.service.agent.AssemblyResourceContext resources) {
        // 1. 物化工作区（RedisStore）
        materializeWorkspace(agentType, agentId, userId, bindings);

        // 2. 加载工具到 Toolkit（仅 UNIVERSAL 加载用户订阅 MCP；会话级 MCP 对所有类型保留）
        Toolkit toolkit = loadToolkit(agentId, preloadedTools, tenantId, userId,
                sessionMcpServiceIds, agentType, resources);

        // 3. 构建权限上下文（必须在 loadToolkit 之后构建，才能扫描 Toolkit 中的动态工具
        //    注册 ALLOW 规则，避免 DONT_ASK 模式下 PermissionEngine 对无规则工具默认 DENY）
        PermissionContextState permissionContext = buildPermissionContext(agentId, toolkit);

        // 4. 装配 Builder 基础属性
        IsolationScope isolationScope = resolveIsolationScope(agentType);
        HarnessAgent.Builder builder = configureAgentBuilder(agentId, sysPrompt, toolkit, isolationScope, tenantId, modelTier, permissionContext);

        // 4. 配置文件系统（沙箱 or Remote）：传递 sessionId 派生命名空间、agentType 供池路由
        FilesystemConfig fsConfig = configureFilesystem(builder, isolationScope, tenantId, userId, agentId,
                sessionId, isolationStrategy, agentType);

        HarnessAgent agent = builder.build();
        // ★ Phase 1.2: AgentEntry 携带 poolKey + bindingFingerprint，用于池命中后的懒刷新判定
        return new AgentEntry(agent, poolKey, sessionId, agentType, agentId, fsConfig.sandboxRequired,
                tenantId, fsConfig.slotKey, isolationScope, bindingFingerprint);
    }

    /**
     * 物化绑定资源到 RedisStore。
     *
     * <p>物化失败时仅记录日志，不中断构建（使用空 workspace 继续）。
     */
    private void materializeWorkspace(String agentType, long agentId, long userId,
                                       List<AgentBinding> bindings) {
        try {
            workspaceMaterializer.materialize(agentType, agentId, userId, bindings);
            log.debug("工作区物化完成（RedisStore）: agentId={}, agentType={}", agentId, agentType);
        } catch (Exception e) {
            log.warn("工作区物化失败，使用空 workspace 继续: agentId={}", agentId, e);
        }
    }

    /**
     * 加载工具到 Toolkit（资源装载分轨）。
     *
     * <p>优先使用模板预加载的工具列表（避免重复 DB 查询）；
     * 预加载为空时按 agentId 从 DB 查询绑定工具。
     *
     * <h3>分轨规则</h3>
     * <ul>
     *   <li>UNIVERSAL：绑定工具 + <b>用户订阅 MCP</b>（{@code resolveMcpToolsForSubscriptions}）+ 会话级 MCP</li>
     *   <li>APPLICATION/SYSTEM：绑定工具 + 会话级 MCP；
     *       <b>不加载用户订阅 MCP</b>（应用/系统智能体资源仅来自 agent_binding 审核通过项，
     *       用户订阅资源注入会造成越权）</li>
     *   <li>会话级 MCP 引用对所有类型保留（用户显式行为，非隐式订阅扩散）</li>
     * </ul>
     *
     * @param agentId       智能体ID
     * @param preloadedTools 预加载的工具列表（可为空）
     * @param tenantId      租户ID（用于查询 MCP 订阅）
     * @param userId        用户ID（用于查询 MCP 订阅）
     * @param sessionMcpServiceIds 会话级临时选择的 MCP 服务ID列表（可选）
     * @param agentType     智能体类型（分轨判据）
     * @return 已注册工具的 Toolkit
     */
    private Toolkit loadToolkit(long agentId, List<Tool> preloadedTools,
                                 long tenantId, long userId,
                                 List<Long> sessionMcpServiceIds, String agentType,
                                 com.aegis.runtime.service.agent.AssemblyResourceContext resources) {
        log.info("loadToolkit: 开始加载工具, agentId={}, tenantId={}, userId={}, agentType={}, preloadedTools={}",
                agentId, tenantId, userId, agentType, preloadedTools != null ? preloadedTools.size() : 0);

        Toolkit toolkit = new Toolkit();
        boolean useTemplate = preloadedTools != null && !preloadedTools.isEmpty();
        if (useTemplate) {
            toolBridge.resolveTools(toolkit, preloadedTools);
            log.info("loadToolkit: 使用预加载工具, count={}", preloadedTools.size());
        } else {
            // T3/T4：从装配期资源上下文加载（enabled 绑定 + 批量预载 Skill 实体），
            // 替代 toolBridge.resolveTools(toolkit, agentId) 的 DB 直查
            toolBridge.resolveTools(toolkit, resources);
            log.info("loadToolkit: 从装配期资源上下文加载绑定工具, agentId={}", agentId);
        }

        // 用户订阅 MCP 仅对 UNIVERSAL 开放（应用/系统智能体仅装载审核通过的绑定资源）
        // 防越权：APPLICATION/SYSTEM 智能体不加载用户个人订阅的 MCP 工具，
        // 仅加载 agent_binding 表中审核通过的绑定工具 + 会话级用户显式选择（有审计日志）
        int mcpToolCount = 0;
        if ("UNIVERSAL".equals(agentType)) {
            log.info("loadToolkit: 开始加载用户订阅的MCP工具(UNIVERSAL), tenantId={}, userId={}", tenantId, userId);
            mcpToolCount = toolBridge.resolveMcpToolsForSubscriptions(toolkit, tenantId, userId);
            if (mcpToolCount > 0) {
                log.info("loadToolkit: MCP动态工具加载成功: agentId={}, mcpToolCount={}", agentId, mcpToolCount);
            } else {
                log.info("loadToolkit: 无MCP订阅工具或加载失败: agentId={}, tenantId={}, userId={}", agentId, tenantId, userId);
            }
            // GLOBAL 系统技能（skill_creator）注册为 Tool：
            // 不在任何 agent_binding 中，仅 UNIVERSAL（用户工作台）需要元技能工具化，
            // APPLICATION/SYSTEM 智能体不开放技能创建能力（防越权）
            int globalSkillToolCount = toolBridge.resolveGlobalSkillAsTools(toolkit);
            if (globalSkillToolCount > 0) {
                log.info("loadToolkit: GLOBAL系统技能工具注册成功: agentId={}, count={}", agentId, globalSkillToolCount);
            }
            // U2: 用户订阅技能（res_skill_subscription）注册为 Tool：
            // 打通"订阅后在通用智能体中使用"链路，订阅技能仅 PUBLISHED 状态可装载
            int subscribedSkillToolCount = toolBridge.resolveSubscribedSkillAsTools(toolkit, tenantId, userId);
            if (subscribedSkillToolCount > 0) {
                log.info("loadToolkit: 用户订阅技能工具注册成功: agentId={}, count={}", agentId, subscribedSkillToolCount);
            }
        } else {
            log.info("loadToolkit: A6 分轨跳过用户订阅MCP加载(仅UNIVERSAL允许): agentId={}, agentType={}",
                    agentId, agentType);
        }

        // 加载会话级临时选择的 MCP 服务工具（所有类型保留：用户显式行为）
        if (sessionMcpServiceIds != null && !sessionMcpServiceIds.isEmpty()) {
            log.info("loadToolkit: 开始加载会话级MCP工具, serviceIds={}", sessionMcpServiceIds);
            int sessionMcpCount = toolBridge.resolveMcpToolsForServiceIds(toolkit, sessionMcpServiceIds, tenantId);
            if (sessionMcpCount > 0) {
                log.info("loadToolkit: 会话级MCP工具加载成功: agentId={}, count={}", agentId, sessionMcpCount);
            }
        }

        log.info("loadToolkit: 工具加载完成: agentId={}, agentType={}, source={}, mcpTools={}", agentId,
                agentType, useTemplate ? "template" : "DB", mcpToolCount);
        return toolkit;
    }

    /**
     * 装配 HarnessAgent.Builder 基础属性（名称、提示词、模型、工具、中间件、压缩、权限）。
     *
     * <p>根据 AgentConfig.modelTier 动态选择模型 ID 格式 {@code aegis:{tier}:{tenantId}}，
     * 由 {@link com.aegis.runtime.integration.model.AegisModelProvider} 从 DB 路由解析。
     *
     * @param permissionContext 已构建的权限上下文（由调用方在 loadToolkit 之后构建，确保能扫描动态工具）
     */
    private HarnessAgent.Builder configureAgentBuilder(long agentId, String sysPrompt,
                                                        Toolkit toolkit, IsolationScope isolationScope,
                                                        long tenantId, String modelTier,
                                                        PermissionContextState permissionContext) {
        String effectiveSysPrompt = (sysPrompt != null && !sysPrompt.isEmpty())
                ? sysPrompt : defaultSysPrompt;

        // 动态模型 ID：格式 aegis:{tier}:{tenantId}，由 AegisModelProvider 从 DB 路由解析
        String effectiveTier = (modelTier != null && !modelTier.isEmpty()) ? modelTier : "STANDARD";
        String modelId = "aegis:" + effectiveTier.toLowerCase() + ":" + tenantId;

        return HarnessAgent.builder()
                .name("AegisAgent-" + agentId)
                .sysPrompt(effectiveSysPrompt)
                .model(modelId)
                .toolkit(toolkit)
                .distributedStore(distributedStore)
                // 禁用文件系统工具（list_files/glob_files/grep_files/read_file/write_file），
                // 工作区中不存在知识库文档，避免 LLM 调用文件工具产生无效结果。
                .disableFilesystemTools()
                // 禁用 AgentScope 内置 ShellExecuteTool（execute），强制使用 aegis_execute，
                // 后者集成 AegisSandboxCoordinator 以正确分配后台沙箱池资源。
                .disableShellTool()
                // 注册 Aegis 技能仓库 + 启用技能中间件，
                // HarnessSkillMiddleware 会自动把可见技能注入系统提示词的 <available_skills> 段落。
                .skillRepository(skillRepository)
                .skillsEnabled(true)
                .maxIters(maxIters)
                .agentId(String.valueOf(agentId))
                // 中间件链由 AgentScope 内核驱动
                .middlewares(middlewareChain.build())
                // 启用 AS 压缩链路 + 工具结果驱逐 + 溢出兜底；禁用 LLM 驱动的 flushBeforeCompact，
                // 跨会话记忆持久化已由 AegisMemoryMiddleware 在应用层异步处理。
                .compaction(CompactionConfig.builder()
                        .triggerMessages(100)
                        .keepMessages(15)
                        .triggerTokens(120_000)
                        .flushBeforeCompact(false)
                        .offloadBeforeCompact(true)
                        .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                                .maxArgLength(2000)
                                .truncationText("... [truncated] ...")
                                .build())
                        .build())
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                // 禁用 AS 内置记忆中间件（MemoryFlushMiddleware + MemoryMaintenanceMiddleware），
                // 跨会话记忆已由 AegisMemoryMiddleware 在应用层异步处理。
                .disableMemoryHooks()
                .maxRetries(3)
                .maxContextTokens(100_000)
                .permissionContext(permissionContext);
    }

    /** 文件系统配置结果（供 buildAgent 组装 AgentEntry） */
    private record FilesystemConfig(boolean sandboxRequired, String slotKey) {}

    /**
     * 按运行时配置选择文件系统：沙箱模式 or Remote 模式。
     *
     * <p>distributedStore 已自动装配 baseStore/sandboxSnapshotSpec/sandboxExecutionGuard，
     * 此处仅需配置文件系统模式和 isolationScope。
     *
     * <p>向 sandboxSpec 注入 sessionId 与 isolationStrategy，
     * 使每个会话的工作区被路由到独立子目录，避免并发文件污染。
     *
     * @return 文件系统配置（含 sandboxRequired 标志与 slotKey，供 AgentEntry 追踪）
     */
    private FilesystemConfig configureFilesystem(HarnessAgent.Builder builder,
                                                  IsolationScope isolationScope,
                                                  long tenantId, long userId, long agentId,
                                                  String sessionId,
                                                  IsolationStrategy isolationStrategy,
                                                  String agentType) {
        if (!sandboxEnabled || sandboxBackend == null) {
            // 记录跳过沙箱模式的原因
            log.warn("跳过沙箱模式: sandboxEnabled={}, sandboxBackend={}, agentId={}",
                    sandboxEnabled, sandboxBackend != null ? sandboxBackend.getClass().getSimpleName() : "null", agentId);
            // distributedStore 会通过 injectStoreIfAbsent 自动注入 baseStore
            RemoteFilesystemSpec fsSpec = new RemoteFilesystemSpec()
                    .isolationScope(isolationScope);
            builder.filesystem(fsSpec);
            return new FilesystemConfig(false, null);
        }

        String slotKey = buildSlotKeyForAgent(isolationScope, agentType, tenantId, userId, agentId);
        // T1 沙箱惰性分配：lazy-allocation 开关开启且非 SYSTEM 智能体时注入 LazySandboxFilesystemSpec，
        // 构建期不触发 allocateSlot（SYSTEM 仍常驻 RESIDENT，§2.2 非目标）；回滚关闭即恢复原 spec
        boolean lazySandbox = lazyAllocationEnabled && !"SYSTEM".equals(agentType);
        AegisSandboxFilesystemSpec sandboxSpec = lazySandbox
                ? new LazySandboxFilesystemSpec(sandboxBackend, sandboxCoordinator, minioSnapshotClient, sandboxResourceLoader)
                : new AegisSandboxFilesystemSpec(sandboxBackend, sandboxCoordinator, minioSnapshotClient, sandboxResourceLoader);
        sandboxSpec.isolationScope(isolationScope);
        sandboxSpec.tenantContext(tenantId, userId, agentId, isolationScope, slotKey);
        // 会话级隔离：使用 sessionId 派生 workspaceRoot 子目录
        sandboxSpec.sessionId(sessionId);
        // 默认隔离策略为 SHARED_PER_SCOPE，允许上层后续覆写
        sandboxSpec.isolationStrategy(isolationStrategy != null ? isolationStrategy : IsolationStrategy.SHARED_PER_SCOPE);
        // 传递智能体类型，供 Coordinator 池路由（UNIVERSAL→LIGHT / SYSTEM→HEAVY / 其他→STANDARD）
        sandboxSpec.agentType(agentType);
        // 启用快照功能，并传递 MinioSnapshotClient 用于反序列化时重新绑定
        sandboxSpec.snapshotSpec(this.snapshotSpec);
        // 向 AegisSandboxClientOptions 注入 MinioSnapshotClient
        sandboxSpec.getClientOptions().setMinioSnapshotClient(this.minioSnapshotClient);
        builder.filesystem(sandboxSpec);
        log.info("沙箱模式构建: agentId={}, scope={}, slotKey={}, sessionId={}, snapshotEnabled={}",
                agentId, isolationScope, slotKey, sessionId, this.snapshotSpec != null);
        return new FilesystemConfig(true, slotKey);
    }

    /**
     * 智能体类型 → IsolationScope 映射。
     */
    private IsolationScope resolveIsolationScope(String agentType) {
        return switch (agentType) {
            case "UNIVERSAL" -> IsolationScope.USER;
            case "APPLICATION" -> IsolationScope.AGENT;
            case "SYSTEM" -> IsolationScope.AGENT;
            // SESSION 被 validateScope 拒绝，默认回退为 AGENT
            default -> IsolationScope.AGENT;
        };
    }

    /**
     * 按智能体类型合成 slotKey（系统智能体 RESIDENT 常驻语义）。
     *
     * <p>SYSTEM 智能体在 AgentScope 层仍以 {@code IsolationScope.GLOBAL} 构建（保持框架语义），
     * 但 slotKey 注入 RESIDENT 专用格式 {@code aegis:resident:sys:{agentId}}，
     * 使每个系统智能体绑定一个专属常驻实例（不参与动态分配与回收）。
     * 其余类型与 {@link SlotKeyParser#build} 保持一致。
     */
    private String buildSlotKeyForAgent(IsolationScope isolationScope, String agentType,
                                        long tenantId, long userId, long agentId) {
        if ("SYSTEM".equals(agentType)) {
            String residentKey = SlotKeyParser.buildResident(agentId);
            log.info("A3 系统智能体常驻槽位: agentId={}, residentSlotKey={}", agentId, residentKey);
            return residentKey;
        }
        return SlotKeyParser.build(isolationScope, tenantId, userId, agentId);
    }

    /**
     * 构建权限上下文（装配期单源决策）。
     *
     * <p>治理档位（STANDARD/ENHANCED/STRICT）仅决定沙箱隔离强度，
     * 不参与资源访问决策；资源访问行为由资源安全等级直映
     * （L1/L2 → ALLOW，L3 → ASK，L4 → REJECT）。
     *
     * <p>装配期单源决策，消除运行期"自动放行"双轨判定：
     * <ul>
     *   <li>{@code needApproval=false} → 等级映射 L1/L2 → ALLOW，框架层直接放行</li>
     *   <li>{@code needApproval=true} → 一律映射 L3 → ASK（审批表意与等级直映一致，
     *       避免 browser_use 这类 MEDIUM+needApproval 的矛盾组合被误放行）</li>
     *   <li>HitlNode 显式配置审批的工具<b>不注册 ALLOW</b>（PermissionEngine 评估序为
     *       deny → ask → 工具自检 → allow，ASK 先于 ALLOW，不排除重叠会致同一工具兼具 ASK+ALLOW 产生歧义），其 ASK 规则由
     *       {@link AegisHitlRuleLoader#loadHitlRules} 注入</li>
     *   <li>{@code http_request} 排除在外：其风险随 HTTP 方法动态变化（GET 只读 / POST 写操作），
     *       动态评估依赖 RequireUserConfirmEvent 转换期的 {@code evaluateRisk}，
     *       装配期无条件 ALLOW 会短路方法级拦截</li>
     *   <li>Toolkit 中动态注册的工具（技能类、MCP 类等）→ 统一注册 ALLOW，因为它们是通过
     *       AegisToolBridge 从数据库加载的（已审核过），可以安全放行；同时避免 DONT_ASK
     *       模式下 PermissionEngine 对"无规则工具"默认返回 DENY 导致 LLM 无法调用</li>
     * </ul>
     *
     * @param agentId 智能体 ID
     * @param toolkit 已加载工具的 Toolkit，用于扫描动态工具注册 ALLOW 规则
     * @return 动态构建的 PermissionContextState
     */
    private PermissionContextState buildPermissionContext(long agentId, Toolkit toolkit) {
        // PermissionMode.DONT_ASK：对已注册规则的工具按规则评估；对无规则工具默认 DENY
        // （因为无人回答审批请求）。此模式下必须确保 Toolkit 中每个工具都有对应规则，
        // 否则动态工具（skill_creator、订阅技能、MCP 工具）会被 PermissionEngine 默认拒绝。
        // 与 BYPASS 的区别：DONT_ASK 保留显式注册的 ASK/DENY 规则对高风险工具的管控能力，
        // 而 BYPASS 会让所有无规则工具自动 ALLOW（包括 http_request POST 这类需审批的操作）。
        PermissionContextState.Builder permBuilder = PermissionContextState.builder()
                .mode(PermissionMode.DONT_ASK)
                // SSRF DENY 规则：阻止 http_request 访问内网地址
                .addDenyRule("http_request", new PermissionRule(
                        "http_request", ".*\\.internal\\..*",
                        PermissionBehavior.DENY, "aegis-ssrf-policy"));

        // 1. 治理档位（仅日志参考：档位只影响沙箱分配，不影响工具规则）
        GovernanceTier tier = GovernanceTier.STANDARD;
        try {
            var agentDef = resourceQueryService.findAgentDefById(agentId);
            if (agentDef != null && agentDef.getGovernanceTier() != null) {
                tier = agentDef.getGovernanceTier();
            }
        } catch (Exception e) {
            log.warn("加载 AgentDef 失败，使用默认治理档位 STANDARD: agentId={}", agentId, e);
        }

        // 2. HitlNode 显式配置审批的工具集合：这些工具禁止注册 ALLOW
        //    （PermissionEngine 中 ALLOW 优先于 ASK，ALLOW 会静默覆盖管理员审批配置）
        Set<String> hitlAskTools = hitlRuleLoader.resolveAskToolNames(agentId);

        // 3. 枚举全部内置工具，装配期按资源等级直映生成规则
        int allowCount = 0;
        int askCount = 0;
        for (Map.Entry<String, ToolRiskInfo> entry : BuiltinToolRiskConfig.getAllTools().entrySet()) {
            String toolName = entry.getKey();
            ToolRiskInfo risk = entry.getValue();

            // http_request：风险随 HTTP 方法动态变化，保留 RequireUserConfirmEvent
            // 转换期的参数级评估（GET 放行 / POST 审批），装配期不做静态 ALLOW
            if ("http_request".equals(toolName)) {
                continue;
            }
            // HitlNode 显式配置审批的工具：不注册 ALLOW，ASK 规则由 loadHitlRules 注入
            if (hitlAskTools.contains(toolName)) {
                log.info("内置工具因 HitlNode 审批配置跳过装配期放行: agentId={}, tool={}",
                        agentId, toolName);
                continue;
            }

            SecurityLevel toolLevel = mapToolLevel(risk);

            PolicyDecision decision = securityPolicyEngine.evaluateToolPolicy(
                    SecurityPolicyContext.builder()
                            .action(SecurityPolicyContext.Action.TOOL_CALL)
                            .resourceCode(toolName)
                            .resourceLevel(toolLevel)
                            .build());
            applyDecision(permBuilder, toolName, decision);
            if (decision == null || decision.isAllow()) {
                allowCount++;
            } else {
                askCount++;
            }
        }

        // 4. HITL 规则（DB 动态加载，最后注入保证与枚举逻辑使用同一缓存视图）
        hitlRuleLoader.loadHitlRules(agentId, permBuilder);

        // 5. 动态工具扫描：Toolkit 中不在内置工具列表 + 不在特殊排除集合中的工具，
        //    统一注册 ALLOW 规则。这些工具包括：GLOBAL 系统技能（skill_creator）、
        //    用户订阅技能、MCP 动态工具、会话级 MCP 工具等。
        //    安全前提：它们都是通过 AegisToolBridge 从数据库加载的，已通过审核流程。
        //    关键：DONT_ASK 模式下 PermissionEngine 对无规则工具默认返回 DENY，
        //    必须为 Toolkit 中每个工具注册规则，否则 LLM 无法调用。
        Set<String> builtinToolNames = BuiltinToolRiskConfig.getAllTools().keySet();
        Set<String> specialExclusions = new java.util.HashSet<>();
        specialExclusions.add("http_request");        // 保留动态参数评估（GET 放行 / POST 审批）
        specialExclusions.add("reset_equipped_tools"); // Meta 工具：AgentScope 内置，不需要显式规则

        int dynamicAllowCount = 0;
        if (toolkit != null) {
            for (String toolName : toolkit.getToolNames()) {
                // 跳过已处理的内置工具（它们在步骤 3 中已注册规则）
                if (builtinToolNames.contains(toolName)) {
                    continue;
                }
                // 跳过特殊排除项
                if (specialExclusions.contains(toolName)) {
                    continue;
                }
                // 跳过 HitlNode 显式配置审批的工具（避免覆盖 ASK 规则）
                if (hitlAskTools.contains(toolName)) {
                    log.debug("动态工具因 HitlNode 审批配置跳过 ALLOW: agentId={}, tool={}", agentId, toolName);
                    continue;
                }
                // 为动态工具注册 ALLOW 规则
                permBuilder.addAllowRule(toolName, new PermissionRule(
                        toolName, null, PermissionBehavior.ALLOW, "aegis-dynamic-tool"));
                dynamicAllowCount++;
            }
        }

        log.info("buildPermissionContext: agentId={}, tier={}, builtinAllow={}, builtinAskOrDeny={}, hitlAskTools={}, dynamicAllow={}",
                agentId, tier, allowCount, askCount, hitlAskTools, dynamicAllowCount);
        return permBuilder.build();
    }

    /**
     * 工具风险配置映射为资源安全等级（策略引擎等级直映输入）。
     *
     * <p>{@code needApproval=true} 一律映射 L3（ASK）——保证审批表意与等级直映一致；
     * 否则按风险等级映射：LOW→L1（放行），MEDIUM→L2（放行），HIGH→L3（审批）。
     */
    private SecurityLevel mapToolLevel(ToolRiskInfo risk) {
        if (risk == null) {
            return SecurityLevel.L1;
        }
        if (risk.isNeedApproval()) {
            return SecurityLevel.L3;
        }
        return switch (risk.getRiskLevel() == null ? ToolRiskInfo.RiskLevel.LOW : risk.getRiskLevel()) {
            case HIGH -> SecurityLevel.L3;
            case MEDIUM -> SecurityLevel.L2;
            default -> SecurityLevel.L1;
        };
    }

    /**
     * 将策略决策应用到 PermissionContextState.Builder。
     */
    private void applyDecision(PermissionContextState.Builder permBuilder,
                               String toolCode, PolicyDecision decision) {
        if (decision == null || decision.isAllow()) {
            permBuilder.addAllowRule(toolCode, new PermissionRule(
                    toolCode, null, PermissionBehavior.ALLOW, "aegis-policy-engine"));
        } else if (decision.isAsk()) {
            permBuilder.addAskRule(toolCode, new PermissionRule(
                    toolCode, null, PermissionBehavior.ASK,
                    decision.getReason() != null ? decision.getReason() : "aegis-policy-ask"));
        } else if (decision.isReject()) {
            permBuilder.addDenyRule(toolCode, new PermissionRule(
                    toolCode, null, PermissionBehavior.DENY,
                    decision.getReason() != null ? decision.getReason() : "aegis-policy-reject"));
        } else if (decision.isMask()) {
            permBuilder.addAllowRule(toolCode, new PermissionRule(
                    toolCode, null, PermissionBehavior.ALLOW, "aegis-policy-masked"));
        }
    }

    /**
     * 驱逐最久未使用的实例（LRU）。
     *
     * <p>使用 ConcurrentHashMap 后，LRU 通过遍历 lastUsedAt 找最旧（驱逐频率低，O(n) 可接受）。
     * 仅从池中移除并返回 entry，closeAgent 由调用方在写锁外执行。
     *
     * @return 被移除的 entry，无则返回 null
     */
    private AgentEntry evictOldest() {
        if (pool.isEmpty()) return null;
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, AgentEntry> e : pool.entrySet()) {
            if (e.getValue().lastUsedAt < oldestTime) {
                oldestTime = e.getValue().lastUsedAt;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) {
            AgentEntry entry = pool.remove(oldestKey);
            if (entry != null) {
                log.info("LRU 驱逐 Agent 实例: poolKey={}, agentId={}, agentType={}, poolSize={}",
                        entry.poolKey, entry.agentId, entry.agentType, pool.size());
            }
            return entry;
        }
        return null;
    }

    /**
     * 清理过期实例（TTL）。
     */
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        long threshold = now - Duration.ofMinutes(idleTimeoutMinutes).toMillis();

        // 写锁内仅收集并移除 entry，写锁释放后再执行 closeAgent（I/O 移出锁）
        List<AgentEntry> toClose = new ArrayList<>();
        rwLock.writeLock().lock();
        try {
            List<String> toEvict = new ArrayList<>();
            for (Map.Entry<String, AgentEntry> e : pool.entrySet()) {
                if (e.getValue().lastUsedAt < threshold) {
                    toEvict.add(e.getKey());
                }
            }
            for (String key : toEvict) {
                AgentEntry entry = pool.remove(key);
                if (entry != null) {
                    toClose.add(entry);
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        // 写锁释放后关闭被驱逐的 Agent 实例
        for (AgentEntry entry : toClose) {
            closeAgent(entry);
        }

        if (!toClose.isEmpty()) {
            log.info("TTL 清理过期 Agent 实例: evicted={}, poolSize={}", toClose.size(), pool.size());
        }
    }

    /**
     * P0-2：触发空闲实例驱逐（HITL/安全策略变更后由 SecurityPolicyCacheInvalidator 调用）。
     *
     * <p>复用 TTL 驱逐通道（idleTimeoutMinutes 阈值 + 优雅 closeAgent 落盘 + 沙箱释放），
     * 仅驱逐空闲超阈值的实例——运行中实例（lastUsedAt 近期）不被驱逐，
     * 满足"运行中会话沿用旧规则至会话结束，新会话即刻生效"语义。
     * 新会话再次 buildAgent 时从已清空的 HitlRuleLoader 缓存重载最新规则。
     */
    public void evictIdleInstances() {
        cleanExpired();
    }

    /**
     * 关闭 Agent 实例（触发沙箱回收 + AgentState 落盘）。
     *
     * <p>当 AgentEntry 标记 sandboxRequired 时，通过 sandboxCoordinator
     * 查找 slotKey 对应的 OCCUPIED 沙箱实例，按 isolationScope 决定策略回收。
     */
    private void closeAgent(AgentEntry entry) {
        // 1. 关闭 Agent（触发 AgentState 落盘）
        try {
            entry.agent.close();
            log.debug("Agent 实例已关闭: poolKey={}, agentId={}, agentType={}, sessionId={}",
                    entry.poolKey, entry.agentId, entry.agentType, entry.sessionId);
        } catch (Exception e) {
            log.warn("关闭 Agent 实例失败: poolKey={}, agentId={}", entry.poolKey, entry.agentId, e);
        }

        // 2. 沙箱释放（★ 不回收，只标记 IDLE，回收由 admin 执行）
        if (entry.sandboxRequired && entry.tenantId > 0 && entry.slotKey != null) {
            try {
                SandboxInstance sbxInstance = sandboxCoordinator.findOccupiedBySlotKey(entry.slotKey);
                if (sbxInstance != null) {
                    boolean saveSnapshot = entry.isolationScope != IsolationScope.GLOBAL;
                    sandboxCoordinator.releaseSlot(entry.tenantId,
                            sbxInstance.getInstanceId(), saveSnapshot);
                    log.info("沙箱随实例驱逐已释放: poolKey={}, agentId={}, instanceId={}, saveSnapshot={}",
                            entry.poolKey, entry.agentId, sbxInstance.getInstanceId(), saveSnapshot);
                } else {
                    // T1：懒分配场景下 sbxInstance 可能为 null（全程未触发沙箱工具），跳过释放
                    log.debug("沙箱驱逐跳过释放(未分配或已释放): poolKey={}, slotKey={}",
                            entry.poolKey, entry.slotKey);
                }
            } catch (Exception e) {
                log.warn("沙箱释放失败: poolKey={}, slotKey={}",
                        entry.poolKey, entry.slotKey, e);
            }
        }
        // T1：清理沙箱就绪门控的会话绑定，避免下次工具调用命中 stale handle（已释放实例）
        if (entry.sessionId != null && sandboxReadinessGate != null) {
            sandboxReadinessGate.clear(entry.sessionId);
        }
        // T1：清理空闲释放追踪表（会话结束，无需再追踪）
        if (entry.sessionId != null && idleReleaseTracker != null) {
            idleReleaseTracker.remove(entry.sessionId);
        }
    }

    /**
     * 优雅关闭：驱逐所有实例。
     */
    @PreDestroy
    public void shutdown() {
        log.info("AegisAgentInstanceManager 正在关闭，活跃实例数: {}", pool.size());
        if (cleaner != null) {
            cleaner.shutdownNow();
        }
        // 写锁内仅清空池并收集 entry，写锁释放后再执行 closeAgent（I/O 移出锁）
        List<AgentEntry> toClose = new ArrayList<>();
        rwLock.writeLock().lock();
        try {
            toClose.addAll(pool.values());
            pool.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
        for (AgentEntry entry : toClose) {
            closeAgent(entry);
        }
        log.info("AegisAgentInstanceManager 已关闭");
    }

    /**
     * Agent 实例条目。
     *
     * <p>Phase 1 新增 poolKey / agentType / agentId / bindingFingerprint：
     * <ul>
     *   <li>{@code poolKey}：由 {@link #computePoolKey} 计算（对齐 IsolationScope），
     *       替代原 sessionId 作为实例池 Map 的 key。SYSTEM/APPLICATION→agentId，UNIVERSAL→userId。</li>
     *   <li>{@code agentType} / {@code agentId}：日志追踪字段，供 LRU 驱逐和 closeAgent 定位。</li>
     *   <li>{@code sessionId}：保留给 filesystem workspace 子目录隔离用，
     *       不再是池 key，但每个会话仍需要独立的 sessionId 工作区。</li>
     *   <li>{@code bindingFingerprint}：Phase 1.2 新增 —— 池命中时与请求携带的 binding 指纹比对，
     *       一致则跳过 Toolkit/Workspace 重建直接复用；不一致走 {@link #refreshToolkit} 懒刷新。</li>
     * </ul>
     *
     * <p>沙箱上下文(sandboxRequired/tenantId/slotKey/isolationScope)沿用原设计，
     * 供 closeAgent 在实例驱逐时回收沙箱。
     */
    private static class AgentEntry {
        final HarnessAgent agent;
        final String poolKey;          // ★ Phase 1.1: 实例池 Map 的 key
        final String sessionId;        // 保留给 filesystem workspace 子目录隔离用
        final String agentType;        // ★ Phase 1.1: 日志追踪 + fingerprint 判定辅助
        final long agentId;            // ★ Phase 1.1: 日志追踪 + fingerprint 判定辅助
        volatile long lastUsedAt;
        // ★ Phase 1.2: 绑定资源指纹，池命中时比对决定是否懒刷新 Toolkit
        volatile String bindingFingerprint;
        // 沙箱上下文
        final boolean sandboxRequired;
        final long tenantId;
        final String slotKey;
        final IsolationScope isolationScope;

        AgentEntry(HarnessAgent agent, String poolKey, String sessionId,
                   String agentType, long agentId,
                   boolean sandboxRequired, long tenantId,
                   String slotKey, IsolationScope isolationScope,
                   String bindingFingerprint) {
            this.agent = agent;
            this.poolKey = poolKey;
            this.sessionId = sessionId;
            this.agentType = agentType;
            this.agentId = agentId;
            this.lastUsedAt = System.currentTimeMillis();
            this.bindingFingerprint = bindingFingerprint;
            this.sandboxRequired = sandboxRequired;
            this.tenantId = tenantId;
            this.slotKey = slotKey;
            this.isolationScope = isolationScope;
        }
    }

    /**
     * 实例池统计信息。
     */
    public record PoolStats(int currentSize, int maxSize, int idleTimeoutMinutes) {}
}
