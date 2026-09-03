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
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.dto.security.BuiltinToolRiskConfig;
import com.aegis.core.dto.security.ToolRiskInfo;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.integration.ext.TenantSessionKey;
import com.aegis.runtime.integration.skill.AegisSkillRepository;
import io.agentscope.core.middleware.MiddlewareBase;
import com.aegis.runtime.integration.workspace.WorkspaceMaterializer;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import com.aegis.runtime.integration.sandbox.AegisSandboxClient;
import com.aegis.runtime.integration.sandbox.AegisSandboxFilesystemSpec;
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
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.runtime.integration.security.AegisPermissionRuleLoader;
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
 *   <li>所有类型尾部追加 {@code :v{agentVersion}}（P1-2）：会话钉住版本参与分池，
 *       智能体升级后新老版本实例互不复用</li>
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
    /** Aegis 自研中间件列表（Spring 自动注入所有 MiddlewareBase bean），构建 Agent 时直接注入 Builder */
    private final List<MiddlewareBase> standaloneMiddlewares;
    private final ISandboxBackend sandboxBackend;
    private final SandboxSnapshotSpec snapshotSpec;
    /** 技能仓库，注册到 Builder 后技能中间件自动将可见技能注入系统提示词 */
    private final AegisSkillRepository skillRepository;
    /** MinIO 快照客户端，沙箱反序列化时重新绑定远程快照 */
    private final MinioSnapshotClient minioSnapshotClient;

    /** AgentScope PermissionRule 加载器，从 sec_tool_policy 表加载策略矩阵映射为 PermissionBehavior */
    @Autowired
    private AegisPermissionRuleLoader permissionRuleLoader;

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

    /** P2 周期2：框架驱动沙箱灰度开关，默认 false 走 RemoteFS（现状零差异），true 走 AegisSandboxFilesystemSpec */
    @Value("${aegis.runtime.sandbox.framework-drive.enabled:false}")
    private boolean sandboxFrameworkDriveEnabled;

    /** P2 周期2：Aegis 沙箱客户端（桥接 admin 池 allocator），灰度开启时用于构建 SandboxFilesystemSpec */
    @Autowired
    private AegisSandboxClient aegisSandboxClient;

    /** 实例池，读操作无锁并发，结构变更用写锁互斥 */
    private final ConcurrentHashMap<String, AgentEntry> pool = new ConcurrentHashMap<>();

    /** 读写锁：读锁保护查询/复用路径，写锁保护构建/驱逐/清理路径 */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** 后台清理线程 */
    private ScheduledExecutorService cleaner;

    public AegisAgentInstanceManager(DistributedStore distributedStore,
                                     WorkspaceMaterializer workspaceMaterializer,
                                     AegisToolBridge toolBridge,
                                     List<MiddlewareBase> standaloneMiddlewares,
                                     ISandboxBackend sandboxBackend,
                                     SandboxSnapshotSpec snapshotSpec,
                                     MinioSnapshotClient minioSnapshotClient,
                                     AegisSkillRepository skillRepository) {
        this.distributedStore = distributedStore;
        this.workspaceMaterializer = workspaceMaterializer;
        this.toolBridge = toolBridge;
        this.standaloneMiddlewares = standaloneMiddlewares;
        this.sandboxBackend = sandboxBackend;
        this.snapshotSpec = snapshotSpec;
        this.minioSnapshotClient = minioSnapshotClient;
        this.skillRepository = skillRepository;
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
        log.info("AegisAgentInstanceManager 已初始化: maxSize={}, idleTimeout={}min, cleanInterval={}min",
                maxSize, idleTimeoutMinutes, cleanIntervalMinutes);
        // 沙箱池已移交 AgentScope SandboxManager + SandboxLifecycleMiddleware，本管理器仅负责 Agent 实例池 LRU/TTL
        log.info("沙箱配置状态: sandboxEnabled={}, sandboxBackend={}",
                sandboxEnabled,
                sandboxBackend != null ? sandboxBackend.getClass().getSimpleName() : "null");
    }

    /**
     * 获取或构建 Agent 实例（核心入口）。
     *
     * <p>设计要点：读路径（复用已有实例）无锁并发，写路径（新建实例）加写锁。
     *
     * <p>指纹比对 3 种结果：
     * <ul>
     *   <li>指纹一致 → 直接复用已有实例</li>
     *   <li>指纹不一致 → 懒刷新工具链后复用</li>
     *   <li>池中不存在 → 新建实例</li>
     * </ul>
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID
     * @param userId    用户ID
     * @param agentType 智能体类型：UNIVERSAL / APPLICATION / SYSTEM
     * @param agentId   智能体ID
     * @param agentVersion 智能体版本（P1-2：会话钉住版本，参与池键隔离新旧版本实例）
     * @param sysPrompt 智能体系统提示词（null 时使用默认值）
     * @param bindings  动态绑定列表（来自模板，null 时 buildAgent 内部从 DB 加载）
     * @param modelTier 模型档位（STANDARD/LIGHT/STRONG）
     * @param isolationStrategy 沙箱隔离策略（为 null 时使用默认 SHARED_PER_SCOPE）
     * @param sessionMcpServiceIds 会话级临时选择的 MCP 服务ID列表（可选）
     * @return HarnessAgent 实例
     */
    public HarnessAgent acquireOrBuild(String sessionId, long tenantId, long userId,
                                       String agentType, long agentId, String agentVersion, String sysPrompt,
                                       List<AgentBinding> bindings,
                                       List<Tool> preloadedTools,
                                       String modelTier,
                                       IsolationStrategy isolationStrategy,
                                       List<Long> sessionMcpServiceIds,
                                       com.aegis.runtime.service.agent.AssemblyResourceContext resources,
                                       AgentConfig agentConfig) {
        // P1-7：会话级 MCP 资源不得进入共享池实例——使用会话专属池键，
        // 使其天然与会话绑定、不与其他会话/用户共享（框架无 per-request toolkit overlay，
        // 唯一完全正确的并发隔离方案）。
        boolean hasSessionMcp = sessionMcpServiceIds != null && !sessionMcpServiceIds.isEmpty();
        // 1. 计算 poolKey（SYSTEM/APPLICATION→agentId 共享，UNIVERSAL→userId 独立；
        //    P1-2：键含版本——老会话钉住旧版本、新会话用新版本，两者天然分池，互不串染；
        //    P1-7：会话级 MCP 存在时键含 sessionId——专属实例，不污染共享池）
        String poolKey = computePoolKey(agentType, agentId, userId, tenantId, agentVersion,
                hasSessionMcp ? sessionId : null);

        // 2. 计算当前绑定指纹，用于池命中后判定是否需要懒刷新
        //    （P1-8：版本参与哈希，与池键共同保证版本语义的一致性判定）
        //    UNIVERSAL 订阅盲区修复：指纹纳入订阅/自建资源签名 + 会话级 MCP，
        //    使订阅变化即指纹变化 → 触发懒刷新 → Toolkit 重建加载新订阅工具
        List<String> dynamicParts = computeDynamicResourceParts(agentType, tenantId, userId, sessionMcpServiceIds);
        String currentBindingFp = BindingFingerprinter.fingerprint(agentVersion, bindings, dynamicParts);

        // 读路径持有读锁，防止驱逐/清理在 get 与 return 之间关闭 Agent
        rwLock.readLock().lock();
        try {
            AgentEntry entry = pool.get(poolKey);
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis();
                // 指纹一致 → 直接复用，跳过工具/工作区重建
                if (currentBindingFp.equals(entry.bindingFingerprint)) {
                    log.debug("复用 Agent 实例(读锁): poolKey={}, agentType={}, agentId={}, fp一致, poolSize={}",
                            poolKey, agentType, agentId, pool.size());
                    return entry.agent;
                }
                // P1-5：指纹不一致 → 先用旧工具兜底返回，锁外异步刷新（避免 MCP 网络 I/O 阻塞读锁）
                log.info("fingerprint mismatch, fallback to stale tools + async refresh: poolKey={}, agentId={}, oldFp={}, newFp={}",
                        poolKey, agentId, entry.bindingFingerprint, currentBindingFp);
                triggerAsyncRefresh(entry, agentId, preloadedTools, tenantId, userId,
                        sessionMcpServiceIds, agentType, resources, currentBindingFp, bindings);
                return entry.agent;
            }
        } finally {
            rwLock.readLock().unlock();
        }

        // 写路径加写锁 —— 新建/驱逐
        // 写锁内仅收集待关闭实例，锁释放后再执行 closeAgent（I/O 移出锁外不阻塞）
        List<AgentEntry> toCloseAfterLock = new ArrayList<>();
        rwLock.writeLock().lock();
        try {
            // 双检：获取写锁后可能其他线程已构建完成
            AgentEntry entry = pool.get(poolKey);
            if (entry != null) {
                entry.lastUsedAt = System.currentTimeMillis();
                if (currentBindingFp.equals(entry.bindingFingerprint)) {
                    return entry.agent;
                }
                // P1-5：双检窗口期指纹仍不一致，同样锁外异步刷新
                triggerAsyncRefresh(entry, agentId, preloadedTools, tenantId, userId,
                        sessionMcpServiceIds, agentType, resources, currentBindingFp, bindings);
                return entry.agent;
            }

            // 池满时驱逐最旧实例（仅从池中移除，关闭延后到锁外）
            while (pool.size() >= maxSize) {
                AgentEntry evicted = evictOldest();
                if (evicted != null) {
                    toCloseAfterLock.add(evicted);
                }
            }

            AgentEntry newEntry = buildAgent(poolKey, sessionId, tenantId, userId, agentType, agentId,
                    sysPrompt, bindings, preloadedTools, modelTier, isolationStrategy, sessionMcpServiceIds,
                    currentBindingFp, resources, agentConfig);
            pool.put(poolKey, newEntry);
            log.info("构建 Agent 实例: poolKey={}, agentType={}, agentId={}, tenantId={}, sessionMcp={}, poolSize={}",
                    poolKey, agentType, agentId, tenantId, hasSessionMcp, pool.size());
            return newEntry.agent;
        } finally {
            rwLock.writeLock().unlock();
            // 写锁释放后关闭被驱逐的实例（I/O 移出锁外避免阻塞）
            for (AgentEntry e : toCloseAfterLock) {
                closeAgent(e);
            }
        }
    }

    /**
     * P1-5：锁外异步刷新工具链（per-entry 锁 + CAS 指纹更新）。
     *
     * <p>指纹不一致时不再在读锁内同步执行 MCP listTools（可 block 60s），
     * 而是：当前请求先用旧版工具集兜底返回，刷新在 boundedElastic 线程异步执行。
     * per-entry 锁保证同一实例的多个并发刷新请求串行化（避免重复网络 I/O）；
     * CAS 式指纹比对确保只有首个检测到不一致的请求实际执行刷新。
     *
     * <p>刷新完成后更新 entry.bindingFingerprint（volatile），后续请求即可命中新指纹直接复用。
     */
    private void triggerAsyncRefresh(AgentEntry entry, long agentId, List<Tool> preloadedTools,
                                     long tenantId, long userId, List<Long> sessionMcpServiceIds,
                                     String agentType,
                                     com.aegis.runtime.service.agent.AssemblyResourceContext resources,
                                     String newFingerprint, List<AgentBinding> bindings) {
        reactor.core.publisher.Mono.fromRunnable(() -> {
                    // per-entry 锁：同一实例的并发刷新串行化
                    synchronized (entry.refreshLock) {
                        // CAS 双检：可能已有其他线程完成了刷新
                        if (newFingerprint.equals(entry.bindingFingerprint)) {
                            return;
                        }
                        try {
                            refreshToolkit(entry, agentId, preloadedTools, tenantId, userId,
                                    sessionMcpServiceIds, agentType, resources);
                            materializeWorkspace(agentType, agentId, userId, bindings);
                            entry.bindingFingerprint = newFingerprint;
                            log.info("async refresh completed: poolKey={}, agentId={}, newFp={}",
                                    entry.poolKey, agentId, newFingerprint);
                        } catch (Exception e) {
                            log.error("async refresh failed, stale tools remain until next request: poolKey={}, agentId={}",
                                    entry.poolKey, agentId, e);
                        }
                    }
                })
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(v -> {}, e -> log.error("async refresh scheduler error: poolKey={}", entry.poolKey, e));
    }

    /**
     * 计算实例池 key（决定共享粒度）。
     *
     * <ul>
     *   <li>SYSTEM → agentId（全局唯一，所有用户共享同一实例）</li>
     *   <li>APPLICATION → agentId（智能体唯一，所有用户共享）</li>
     *   <li>UNIVERSAL → userId（每用户一个独立实例）</li>
     * </ul>
     *
     * <p>key 前缀带 tenantId，保证不同租户不会撞 key 共享实例（含权限上下文/工具链）。
     *
     * <p>P1-2：key 尾部带 agentVersion——同一智能体升级后，老会话（钉住旧版本）与
     * 新会话（最新版本）落入不同池条目，各自的 config/bindings/工具链严格同源，
     * 消除「半新半旧」实例复用；老版本条目随 TTL/事件驱逐自然回收。
     *
     * <p>P1-7：当请求携带会话级 MCP 资源时，key 追加 sessionId 前缀——
     * 使该实例专属于此会话，会话级 MCP 工具注册于独立 Toolkit，
     * 不污染共享池实例（跨用户/跨会话零泄漏）。
     *
     * @param sessionScopeId 会话级隔离 ID（sessionId），null 表示无会话级资源（走共享池）
     */
    private String computePoolKey(String agentType, long agentId, long userId, long tenantId,
                                  String agentVersion, String sessionScopeId) {
        // 加租户前缀，从结构上保证同池实例必同租户，防止跨租户撞 key
        String tenantPart = "T" + tenantId + ":";
        // P1-7：会话级专属前缀（仅会话级 MCP 资源存在时）
        String sessionPart = (sessionScopeId != null && !sessionScopeId.isEmpty())
                ? "S:" + sessionScopeId + ":" : "";
        // 版本后缀（P1-2）：版本为空时退化为无版本键（防御异常数据）
        String versionPart = (agentVersion != null && !agentVersion.isEmpty())
                ? ":v" + agentVersion : "";
        return switch (agentType) {
            case "SYSTEM" -> tenantPart + sessionPart + "SYS:" + agentId + versionPart;
            case "APPLICATION" -> tenantPart + sessionPart + "APP:" + agentId + versionPart;
            case "UNIVERSAL" -> tenantPart + sessionPart + "UNI:" + userId + versionPart;
            default -> tenantPart + sessionPart + "APP:" + agentId + versionPart;
        };
    }

    // 绑定指纹计算已抽至 BindingFingerprinter，与工作区物化器共享同一实现

    /**
     * 计算 UNIVERSAL 动态资源签名部件（订阅/自建 MCP + Skill + 会话级 MCP）。
     *
     * <p>这些资源不在 agent_binding 中（UNIVERSAL 模板绑定通常为空），但实际加载到 Toolkit。
     * 纳入指纹后，订阅/自建变化即指纹变化 → 池命中时指纹不一致 → 触发 P1-5 懒刷新。
     * 非 UNIVERSAL 类型仅纳入会话级 MCP（sessionMcpServiceIds），因为订阅/自建仅对 UNIVERSAL 开放。
     *
     * <p>查询发生在请求线程（TenantContextScope.bound 已在位），租户上下文安全。
     * 如果 ThreadLocal 丢失（异步刷新路径），查询非忽略表会抛 IllegalStateException，
     * 此处 catch 后返回空列表——指纹退化为仅 agentVersion+bindings，
     * 与修复前行为一致，不会比原来更差（异步刷新路径已由 refreshToolkit 内的 TenantContextScope 修复保护）。
     */
    private List<String> computeDynamicResourceParts(String agentType, long tenantId, long userId,
                                                      List<Long> sessionMcpServiceIds) {
        List<String> parts = new ArrayList<>();
        try {
            if ("UNIVERSAL".equals(agentType)) {
                // 订阅 MCP
                for (Long id : resourceQueryService.findSubscribedMcpServiceIds(tenantId, userId)) {
                    parts.add("SUBMCP:" + id);
                }
                // 订阅 Skill
                for (Long id : resourceQueryService.findSubscribedSkillIds(tenantId, userId)) {
                    parts.add("SUBSKILL:" + id);
                }
                // 自建 Skill（含 DRAFT，对齐技能指令轨语义）
                for (var s : resourceQueryService.findOwnedSkills(tenantId, userId)) {
                    parts.add("OWNSKILL:" + s.getId() + ":" + s.getLifeStatus());
                }
                // 自建 MCP（PUBLISHED+ACTIVE）
                for (var m : resourceQueryService.findOwnedActiveMcpServices(userId)) {
                    parts.add("OWNMCP:" + m.getId());
                }
            }
        } catch (Exception e) {
            log.warn("computeDynamicResourceParts: 查询动态资源异常（可能租户上下文缺失），指纹退化为仅绑定: agentType={}, error={}",
                    agentType, e.getMessage());
        }
        // 会话级 MCP（所有类型）
        if (sessionMcpServiceIds != null) {
            List<Long> sorted = new ArrayList<>(sessionMcpServiceIds);
            java.util.Collections.sort(sorted);
            for (Long id : sorted) {
                parts.add("SESMCP:" + id);
            }
        }
        return parts;
    }

    /**
     * 懒刷新工具链。
     *
     * <p>当池命中但绑定指纹不一致（管理员修改了绑定配置）时，不走重建整个 Agent 的开销，
     * 而是直接操作已有 Toolkit：先逐个移除旧工具，再重新加载。
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
        // BUILTIN 基础工具与 loadToolkit 同步加载（所有类型公共能力基座）
        toolBridge.resolveBuiltinTools(toolkit);
        // UNIVERSAL 分支：异步刷新在 boundedElastic 新线程执行，ThreadLocal 不跨线程传播，
        // res_skill / res_skill_subscription 非租户忽略表 → 查询会 fail-closed 抛异常。
        // 用 TenantContextScope.bound 恢复租户上下文，保证刷新路径与冷启动路径行为一致。
        if ("UNIVERSAL".equals(agentType)) {
            try (var scope = com.aegis.core.common.tenant.TenantContextScope.bound(tenantId)) {
                toolBridge.resolveMcpToolsForSubscriptions(toolkit, tenantId, userId);
                toolBridge.resolveGlobalSkillAsTools(toolkit);
                toolBridge.resolveSubscribedSkillAsTools(toolkit, tenantId, userId);
                // 与 loadToolkit 保持一致：自建技能 + 自建 MCP 同步加载
                toolBridge.resolveOwnedSkillAsTools(toolkit, tenantId, userId);
                toolBridge.resolveOwnedMcpTools(toolkit, userId);
            }
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
     * <p>集成 WorkspaceMaterializer（资源物化）、AegisToolBridge（工具桥接），
     * 沙箱语义由 AgentScope SandboxManager 原生承载（Phase 2 减法后走 RemoteFS）。
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
                                    com.aegis.runtime.service.agent.AssemblyResourceContext resources,
                                    AgentConfig agentConfig) {
        // 1. 物化工作区（RedisStore）
        materializeWorkspace(agentType, agentId, userId, bindings);

        // 2. 加载工具到 Toolkit（仅 UNIVERSAL 加载用户订阅 MCP；会话级 MCP 对所有类型保留）
        Toolkit toolkit = loadToolkit(agentId, preloadedTools, tenantId, userId,
                sessionMcpServiceIds, agentType, resources);

        // 3. 构建权限上下文（必须在 loadToolkit 之后构建，才能扫描 Toolkit 中的动态工具
        //    注册 ALLOW 规则，避免 DONT_ASK 模式下 PermissionEngine 对无规则工具默认 DENY）
        PermissionContextState permissionContext = buildPermissionContext(agentId, tenantId, toolkit, agentConfig);

        // 4. 装配 Builder 基础属性
        IsolationScope isolationScope = resolveIsolationScope(agentType);
        HarnessAgent.Builder builder = configureAgentBuilder(agentId, sysPrompt, toolkit, isolationScope, tenantId, modelTier, permissionContext, agentConfig);

        // 4. 配置文件系统（沙箱 or Remote）：传递 sessionId 派生命名空间、agentType 供池路由
        FilesystemConfig fsConfig = configureFilesystem(builder, isolationScope, tenantId, userId, agentId,
                sessionId, isolationStrategy, agentType);

        HarnessAgent agent = builder.build();
        // ★ Phase 1.2: AgentEntry 携带 poolKey + bindingFingerprint，用于池命中后的懒刷新判定
        return new AgentEntry(agent, poolKey, sessionId, agentType, agentId, fsConfig.sandboxRequired,
                tenantId, userId, fsConfig.slotKey, isolationScope, bindingFingerprint);
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
     *   <li><b>所有类型</b>：绑定工具 + <b>BUILTIN 平台内置工具</b>（系统内置资源，
     *       不依赖绑定即可用）+ 会话级 MCP</li>
     *   <li>UNIVERSAL 额外加载：<b>用户订阅 MCP</b>（{@code resolveMcpToolsForSubscriptions}）、
     *       GLOBAL 系统技能、订阅技能、自建技能、自建 MCP</li>
     *   <li>APPLICATION/SYSTEM 不加载用户订阅/自建资源
     *       （用户订阅资源注入会造成越权，资源仅来自 agent_binding 审核通过项）</li>
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

        // BUILTIN 平台内置工具：所有类型的公共能力基座（"系统内置资源"语义）。
        // APPLICATION/SYSTEM 未绑定工具时不再裸奔；用户订阅/草稿资源仍仅 UNIVERSAL 加载
        int builtinToolCount = toolBridge.resolveBuiltinTools(toolkit);
        if (builtinToolCount > 0) {
            log.info("loadToolkit: BUILTIN基础工具加载成功: agentId={}, agentType={}, count={}",
                    agentId, agentType, builtinToolCount);
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
            // 自建技能注册为 Tool（含 DRAFT，对齐技能指令轨"自建自测"语义）：
            // 防自订阅机制使作者无法订阅自己的技能 → 订阅轨不覆盖自建 → 需独立分轨
            int ownedSkillToolCount = toolBridge.resolveOwnedSkillAsTools(toolkit, tenantId, userId);
            if (ownedSkillToolCount > 0) {
                log.info("loadToolkit: 自建技能工具注册成功: agentId={}, count={}", agentId, ownedSkillToolCount);
            }
            // 自建 MCP 加载（PUBLISHED+ACTIVE，按 createBy 识别归属）：
            // 作者创建的 MCP 服务无需手动订阅即可在 UNIVERSAL 中使用
            int ownedMcpToolCount = toolBridge.resolveOwnedMcpTools(toolkit, userId);
            if (ownedMcpToolCount > 0) {
                log.info("loadToolkit: 自建MCP工具加载成功: agentId={}, count={}", agentId, ownedMcpToolCount);
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
                                                        PermissionContextState permissionContext,
                                                        AgentConfig agentConfig) {
        String effectiveSysPrompt = (sysPrompt != null && !sysPrompt.isEmpty())
                ? sysPrompt : defaultSysPrompt;

        // 动态模型 ID：格式 aegis:{tier}:{tenantId}，由 AegisModelProvider 从 DB 路由解析
        String effectiveTier = (modelTier != null && !modelTier.isEmpty()) ? modelTier : "STANDARD";
        String modelId = "aegis:" + effectiveTier.toLowerCase() + ":" + tenantId;

        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name("AegisAgent-" + agentId)
                .sysPrompt(effectiveSysPrompt)
                .model(modelId)
                .toolkit(toolkit)
                .distributedStore(distributedStore)
                // Phase 1.2 收敛：启用框架内置 ShellExecuteTool("execute") + FilesystemTool("filesystem")。
                // framework-drive.enabled=true 时文件系统是 SandboxBackedFilesystem（implements AbstractSandboxFilesystem），
                // 框架 HarnessAgent.Builder 会自动注册这两个工具；
                // 它们的 execute/read_file/write_file/list_files 全部走 SandboxLifecycleMiddleware -> AegisSandboxClient -> K8s Pod，
                // 沙箱生命周期（acquire/release/session 复用）由框架原生管理。
                // 自建 AegisExecuteTool / SandboxTrigger / SandboxToolHandler 已删除（与框架工具重复造轮子）。
                // framework-drive.enabled=false 走 RemoteFilesystemSpec（CompositeFilesystem），框架本来就不会注册 shell/fs 工具，此处无副作用。
                // 不调用 .disableShellTool() / .disableFilesystemTools()
                // 注册 Aegis 技能仓库 + 启用技能中间件，
                // HarnessSkillMiddleware 会自动把可见技能注入系统提示词的 <available_skills> 段落。
                .skillRepository(skillRepository)
                .skillsEnabled(true)
                // Phase 3：maxIters 优先取 AgentConfig.maxTurns，null 回退全局 @Value 默认
                .maxIters(agentConfig != null && agentConfig.getMaxTurns() != null
                        ? agentConfig.getMaxTurns() : maxIters)
                .agentId(String.valueOf(agentId))
                // 中间件链由 AgentScope 内核按 order 降序驱动（Phase 2 精简后直接注入 List）
                .middlewares(standaloneMiddlewares)
                // Phase 3：压缩配置由 AgentConfig.compactionThreshold / memoryFlushStrategy 驱动
                .compaction(buildCompactionConfig(agentConfig))
                .toolResultEviction(ToolResultEvictionConfig.defaults())
                .maxRetries(3)
                .maxContextTokens(100_000)
                .permissionContext(permissionContext);
        // Phase 3：memoryFlushStrategy != NONE 时启用 AS 内置记忆钩子；
        // 否则禁用（跨会话记忆由 AegisMemoryMiddleware 在应用层异步处理）
        if (!shouldEnableMemory(agentConfig)) {
            builder.disableMemoryHooks();
        }
        // Phase 3：enablePlanMode 由 AgentConfig 驱动（默认关闭）
        if (agentConfig != null && Boolean.TRUE.equals(agentConfig.getEnablePlanMode())) {
            builder.enablePlanMode();
        }
        return builder;
    }

    /** Phase 3：根据 AgentConfig 构建上下文压缩配置。 */
    private CompactionConfig buildCompactionConfig(AgentConfig agentConfig) {
        Integer threshold = (agentConfig != null) ? agentConfig.getCompactionThreshold() : null;
        if (threshold == null || threshold <= 0) {
            return CompactionConfig.builder()
                    .triggerMessages(Integer.MAX_VALUE)
                    .keepMessages(Integer.MAX_VALUE)
                    .triggerTokens(Integer.MAX_VALUE)
                    .flushBeforeCompact(false)
                    .offloadBeforeCompact(true)
                    .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                            .maxArgLength(2000)
                            .truncationText("... [truncated] ...")
                            .build())
                    .build();
        }
        return CompactionConfig.builder()
                .triggerMessages(threshold)
                .keepMessages(Math.max(5, threshold / 4))
                .triggerTokens(120_000)
                .flushBeforeCompact("PROGRESSIVE".equalsIgnoreCase(agentConfig.getMemoryFlushStrategy()))
                .offloadBeforeCompact(true)
                .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                        .maxArgLength(2000)
                        .truncationText("... [truncated] ...")
                        .build())
                .build();
    }

    /** Phase 3：判断是否启用 AS 内置记忆钩子（memoryFlushStrategy 非 NONE 即启用）。 */
    private boolean shouldEnableMemory(AgentConfig agentConfig) {
        if (agentConfig == null) {
            return false;
        }
        String strategy = agentConfig.getMemoryFlushStrategy();
        return strategy != null && !"NONE".equalsIgnoreCase(strategy);
    }

    /** 文件系统配置结果（供 buildAgent 组装 AgentEntry） */
    private record FilesystemConfig(boolean sandboxRequired, String slotKey) {}

    /**
     * 按运行时配置选择文件系统：沙箱模式 or Remote 模式。
     *
     * <p>distributedStore 已自动装配 baseStore/sandboxSnapshotSpec/sandboxExecutionGuard，
     * 此处仅需配置文件系统模式和 isolationScope。
     *
     * <p>P1-3：workspaceRoot 改为会话无关的 pool 键派生路径——
     * UNI={@code /workspace/{tenantId}/{agentId}/{userId}}，
     * APP/SYS={@code /workspace/{tenantId}/{agentId}}。
     * 池化实例被不同会话复用时 workspace 指向同一稳定路径，
     * 不再因 sessionId 不可变导致串扰。
     * 会话级隔离由 P1-7 会话专属池键保证（有会话级 MCP 时走独立实例）。
     *
     * @return 文件系统配置（含 sandboxRequired 标志与 slotKey，供 AgentEntry 追踪）
     */
    private FilesystemConfig configureFilesystem(HarnessAgent.Builder builder,
                                                  IsolationScope isolationScope,
                                                  long tenantId, long userId, long agentId,
                                                  String sessionId,
                                                  IsolationStrategy isolationStrategy,
                                                  String agentType) {
        // P2 周期2：sandbox.framework-drive.enabled=true 时走 AegisSandboxFilesystemSpec，
        // 框架自动从 spec 构建 SandboxManager + SandboxLifecycleMiddleware 装入 HarnessAgent；
        // 否则走 RemoteFilesystemSpec（现状零差异）。
        if (sandboxFrameworkDriveEnabled) {
            AegisSandboxFilesystemSpec sandboxFsSpec = AegisSandboxFilesystemSpec.forContext(
                    aegisSandboxClient, snapshotSpec, agentType,
                    tenantId, userId, agentId, sessionId);
            builder.filesystem(sandboxFsSpec);
            String slotKey = sandboxFsSpec.getIsolationScope() != null
                    ? "aegis:" + tenantId + ":" + (isolationScope == IsolationScope.USER
                            ? "user:" + userId : "agent:" + agentId) : null;
            log.info("文件系统配置(框架驱动沙箱): agentId={}, scope={}, agentType={}, slotKey={}",
                    agentId, isolationScope, agentType, slotKey);
            return new FilesystemConfig(true, slotKey);
        }

        // 现状路径：纯 RemoteFS（sandboxFrameworkDriveEnabled=false 默认）
        log.info("文件系统配置(纯 RemoteFS): agentId={}, scope={}, agentType={}, sandboxEnabled={}",
                agentId, isolationScope, agentType, sandboxEnabled);
        RemoteFilesystemSpec fsSpec = new RemoteFilesystemSpec()
                .isolationScope(isolationScope);
        builder.filesystem(fsSpec);
        return new FilesystemConfig(false, null);
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
     *   <li>HITL 审批由 {@code AegisPermissionRuleLoader} 从 sec_tool_policy 表统一映射为
 *       PermissionBehavior（ALLOW/ASK/DENY），Phase 2 精简后不再依赖独立 HitlRuleLoader</li>
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
    private PermissionContextState buildPermissionContext(long agentId, long tenantId, Toolkit toolkit,
                                                           AgentConfig agentConfig) {
        PermissionMode mode = (agentConfig != null && "DONT_ASK".equalsIgnoreCase(agentConfig.getPermissionMode()))
                ? PermissionMode.DONT_ASK
                : PermissionMode.DEFAULT;
        // PermissionMode.DONT_ASK：对已注册规则的工具按规则评估；对无规则工具默认 DENY
        // （因为无人回答审批请求）。此模式下必须确保 Toolkit 中每个工具都有对应规则，
        // 否则动态工具（skill_creator、订阅技能、MCP 工具）会被 PermissionEngine 默认拒绝。
        // 与 BYPASS 的区别：DONT_ASK 保留显式注册的 ASK/DENY 规则对高风险工具的管控能力，
        // 而 BYPASS 会让所有无规则工具自动 ALLOW（包括 http_request POST 这类需审批的操作）。
        PermissionContextState.Builder permBuilder = PermissionContextState.builder()
                .mode(mode)
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

        // 2. 枚举全部内置工具，装配期按资源等级直映生成规则（HITL 审批已由 AegisPermissionRuleLoader 统一处理）
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

            SecurityLevel toolLevel = mapToolLevel(risk);
            int levelNum = toolLevel == SecurityLevel.L1 ? 1 : toolLevel == SecurityLevel.L2 ? 2 : toolLevel == SecurityLevel.L3 ? 3 : 4;

            PermissionBehavior behavior = permissionRuleLoader.evaluateBehavior(
                    tenantId, tier.name(), risk.getToolType(), levelNum);
            switch (behavior) {
                case ALLOW -> {
                    permBuilder.addAllowRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "aegis-db-policy"));
                    allowCount++;
                }
                case ASK -> {
                    permBuilder.addAskRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ASK, "aegis-db-policy"));
                    askCount++;
                }
                case DENY -> {
                    permBuilder.addDenyRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.DENY, "aegis-db-policy"));
                    askCount++;
                }
                default -> {
                    permBuilder.addAllowRule(toolName, new PermissionRule(toolName, null, PermissionBehavior.ALLOW, "aegis-db-policy-default"));
                    allowCount++;
                }
            }
        }

        // 3. 动态工具扫描：Toolkit 中不在内置工具列表 + 不在特殊排除集合中的工具，
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
                // 为动态工具注册 ALLOW 规则
                permBuilder.addAllowRule(toolName, new PermissionRule(
                        toolName, null, PermissionBehavior.ALLOW, "aegis-dynamic-tool"));
                dynamicAllowCount++;
            }
        }

        log.info("buildPermissionContext: agentId={}, tier={}, builtinAllow={}, builtinAskOrDeny={}, dynamicAllow={}",
                agentId, tier, allowCount, askCount, dynamicAllowCount);
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
     * P0-2：触发空闲实例驱逐（HITL/安全策略变更后调用）。
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
     * 精准驱逐指定用户的 UNIVERSAL 实例（无视空闲阈值）。
     *
     * <p>订阅变更事件（MCP/Skill subscribe/unsubscribe）触发时，
     * 该用户的 UNIVERSAL 实例需立即失效——下次请求走 buildAgent 重建 Toolkit，
     * 加载最新订阅资源。与 {@link #evictIdleInstances()}（仅驱逐空闲 >30min）互补：
     * 用户"订阅后马上回来用"场景下实例非空闲，evictIdle 不驱逐，导致旧工具集复用。
     *
     * <p>仅驱逐 UNIVERSAL 类型 + userId 匹配的条目；APPLICATION/SYSTEM 实例不受影响。
     *
     * @param userId 用户ID
     */
    public void evictUniversalForUser(long userId) {
        List<AgentEntry> toClose = new ArrayList<>();
        rwLock.writeLock().lock();
        try {
            List<String> toEvict = new ArrayList<>();
            for (var e : pool.entrySet()) {
                AgentEntry entry = e.getValue();
                if ("UNIVERSAL".equals(entry.agentType) && entry.userId == userId) {
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
        for (AgentEntry entry : toClose) {
            closeAgent(entry);
        }
        if (!toClose.isEmpty()) {
            log.info("精准驱逐用户 UNIVERSAL 实例: userId={}, evicted={}, poolSize={}",
                    userId, toClose.size(), pool.size());
        }
    }

    /**
     * 关闭 Agent 实例（触发 AgentState 落盘）。
     *
     * <p>Phase 2 减法：自建沙箱池已删除，沙箱 release 语义由 AgentScope
     * SandboxManager + SandboxLifecycleMiddleware 原生处理，此处仅关闭 Agent 并记录日志占位。
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
        // 2. 沙箱释放占位：已移交 AgentScope SandboxManager，无需手动 releaseSlot / 清理 ReadinessGate / IdleTracker
        log.info("沙箱释放占位(已移交 SandboxManager): poolKey={}, agentId={}",
                entry.poolKey, entry.agentId);
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
        final long userId;          // 订阅事件精准驱逐用
        final String slotKey;
        final IsolationScope isolationScope;
        // P1-5：per-entry 锁，保证同一实例的异步刷新串行化（避免重复 MCP 网络 I/O）
        final Object refreshLock = new Object();

        AgentEntry(HarnessAgent agent, String poolKey, String sessionId,
                   String agentType, long agentId,
                   boolean sandboxRequired, long tenantId, long userId,
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
            this.userId = userId;
            this.slotKey = slotKey;
            this.isolationScope = isolationScope;
        }
    }

    /**
     * 实例池统计信息。
     */
    public record PoolStats(int currentSize, int maxSize, int idleTimeoutMinutes) {}
}
