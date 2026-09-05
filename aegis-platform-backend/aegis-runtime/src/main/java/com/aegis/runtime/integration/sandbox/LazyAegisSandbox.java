package com.aegis.runtime.integration.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import com.aegis.runtime.service.sandbox.AegisSandboxAllocator;
import com.aegis.runtime.service.sandbox.SandboxSessionHolder;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.List;

/**
 * 惰性沙箱代理（真·按需物化：零工具调用 = 零沙箱占用）。
 *
 * <p><b>问题</b>：框架 {@code SandboxLifecycleMiddleware.acquireForCall} 在
 * {@code ReActAgent.beforeAgentExecution()}（每轮调用开始、LLM 响应之前）即触发
 * {@code SandboxManager.acquire}。若 {@code AegisSandboxClient.create/resume} 直接分配，
 * 纯聊天（"你好"）也会走完整分配链——首个用户首轮闲聊即占池，违背"沙箱只服务代码执行"的设计。</p>
 *
 * <p><b>方案</b>：create/resume 只返回本代理（零分配、零 DB 写），把真实物化推迟到
 * <b>第一次真正需要 Pod 的入口</b>（{@link #exec}/{@link #persistWorkspace}/{@link #hydrateWorkspace}）。
 * 框架所有 Pod 访问都经 {@code SandboxBackedFilesystem → sandbox.exec}（源码已核），
 * 因此代理拦截点完备，无框架旁路。</p>
 *
 * <h3>未物化轮次（纯聊天）的框架协作</h3>
 * <ul>
 *   <li>{@code sandbox.start()} → 本类 no-op（真实 start 在物化时补调）</li>
 *   <li>{@code SandboxManager.persistState} 对 {@code getState()==null} 直接跳过 →
 *       Redis 保留上一次物化轮次的 state，供后续代码执行轮次 resume</li>
 *   <li>{@code SandboxManager.release}（stop+shutdown）→ no-op，Pod 生命周期归 admin 池</li>
 *   <li>{@code SandboxSessionHolder} 未登记 → 任务终态 {@code releaseOnSessionEnd} 幂等空转</li>
 * </ul>
 *
 * <h3>物化路径</h3>
 * <ul>
 *   <li>create 轨道：会话缓存命中复用 → allocator 四级退化分配 → register 登记</li>
 *   <li>resume 轨道：DB 反查补全 + 槽位防抢占 + 探活 rebind（IDLE→OCCUPIED）→ register；
 *       探活失败重新分配 + MinIO hydrate</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
public class LazyAegisSandbox implements Sandbox {

    private final AegisSandboxAllocator allocator;
    private final ISandboxBackend backend;
    private final MinioSnapshotClient snapshotClient;
    private final SandboxSessionHolder sessionHolder;

    // ---- create 轨道参数（resumeState == null 时生效） ----
    private final Long tenantId;
    private final Long userId;
    private final Long agentId;
    private final String sessionId;
    private final String agentType;

    // ---- resume 轨道参数（非 null 表示从 Redis 持久化 state 恢复） ----
    private final AegisSandboxState resumeState;

    /** 物化时使用的 WorkspaceSpec（create 由装配传入；resume 由框架 acquire 前覆盖为最新） */
    private final WorkspaceSpec workspaceSpec;

    /** 已物化的真实沙箱（null = 未物化） */
    private volatile AegisSandbox delegate;

    private LazyAegisSandbox(AegisSandboxAllocator allocator, ISandboxBackend backend,
                             MinioSnapshotClient snapshotClient, SandboxSessionHolder sessionHolder,
                             Long tenantId, Long userId, Long agentId, String sessionId,
                             String agentType, AegisSandboxState resumeState,
                             WorkspaceSpec workspaceSpec) {
        this.allocator = allocator;
        this.backend = backend;
        this.snapshotClient = snapshotClient;
        this.sessionHolder = sessionHolder;
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = agentId;
        this.sessionId = sessionId;
        this.agentType = agentType;
        this.resumeState = resumeState;
        this.workspaceSpec = workspaceSpec;
    }

    /** create 轨道工厂（零分配，物化时走四级退化） */
    public static LazyAegisSandbox forCreate(AegisSandboxAllocator allocator, ISandboxBackend backend,
                                             MinioSnapshotClient snapshotClient,
                                             SandboxSessionHolder sessionHolder,
                                             Long tenantId, Long userId, Long agentId,
                                             String sessionId, String agentType,
                                             WorkspaceSpec workspaceSpec) {
        return new LazyAegisSandbox(allocator, backend, snapshotClient, sessionHolder,
                tenantId, userId, agentId, sessionId, agentType, null, workspaceSpec);
    }

    /** resume 轨道工厂（零分配，物化时走 DB 反查 + 探活 rebind） */
    public static LazyAegisSandbox forResume(AegisSandboxAllocator allocator, ISandboxBackend backend,
                                             MinioSnapshotClient snapshotClient,
                                             SandboxSessionHolder sessionHolder,
                                             AegisSandboxState resumeState) {
        return new LazyAegisSandbox(allocator, backend, snapshotClient, sessionHolder,
                resumeState.getTenantId(), resumeState.getUserId(), resumeState.getAgentId(),
                resumeState.getSessionId(),
                resumeState.getSlotKey() != null && resumeState.getSlotKey().contains(":user:")
                        ? "UNIVERSAL" : "APPLICATION",
                resumeState, resumeState.getWorkspaceSpec());
    }

    // =========================================================================
    // 框架 Sandbox 生命周期（未物化全部零开销）
    // =========================================================================

    @Override
    public void start() throws Exception {
        // 惰性：不物化。真实 start（running 标记）在物化时补调
        AegisSandbox d = delegate;
        if (d != null) {
            d.start();
        }
    }

    @Override
    public void stop() throws Exception {
        // no-op 语义与 AegisSandbox 对齐（释放归 allocator.release，任务终态触发）
        AegisSandbox d = delegate;
        if (d != null) {
            d.stop();
        }
    }

    @Override
    public void shutdown() throws Exception {
        // no-op：Pod 生命周期归 admin 池 Reconcile
    }

    @Override
    public void close() throws Exception {
        stop();
        shutdown();
    }

    @Override
    public boolean isRunning() {
        AegisSandbox d = delegate;
        return d != null && d.isRunning();
    }

    @Override
    public SandboxState getState() {
        // 未物化返回 null：框架 SandboxManager.persistState 对 null state 跳过持久化，
        // 纯聊天轮次既不分配也不产生新 state，Redis 保留上一次物化轮次的 state 供 resume
        AegisSandbox d = delegate;
        return d != null ? d.getState() : null;
    }

    // =========================================================================
    // 真实 Pod 访问入口（物化点）
    // =========================================================================

    /**
     * 系统提示词上下文文件（框架 WorkspaceContextMiddleware.onSystemPrompt 每轮读取，
     * 路径固定，且其内容源在 RedisStore/宿主层而非 Pod——未物化时 Pod 内本就不存在）。
     */
    private static final List<String> CONTEXT_READ_FILES =
            List.of("AGENTS.md", "MEMORY.md", "KNOWLEDGE.md");

    /**
     * 框架目录枚举 glob 前缀（WorkspaceManager.listKnowledgeFiles / listSubagents 等系统
     * 提示词构建路径，命令固定为 {@code find '<dir>' -type f -name ...}）。
     */
    private static final List<String> FRAMEWORK_GLOB_DIRS =
            List.of("knowledge", "subagents", "skills");

    /**
     * 惰性读拦截：未物化时对"框架管理路径探测"类 Pod 访问短路，避免纯聊天轮次物化沙箱。
     *
     * <p>拦截三类（源码已核，AgentScope 2.0.2，E2E 堆栈实测）：</p>
     * <ol>
     *   <li>read 探测（固定以 {@code if [ ! -f 'path' ]...} 开头，write 是 {@code if [ -e } 前缀互不干扰）
     *       ——readAgentsMd/readMemoryMd/readKnowledgeMd + WorkspaceTaskRepository 任务文件读；</li>
     *   <li>框架目录枚举（{@code find 'knowledge'|'subagents'|'skills' -type f}）
     *       ——listKnowledgeFiles/listSubagents 系统提示词构建；</li>
     *   <li>总线存在性探测（{@code test -e '.agentscope/bus/...'}）
     *       ——AsyncToolRegistry/MessageBus 的注册探测。</li>
     * </ol>
     *
     * <p>语义依据：未物化 = Pod 从未启动，工作区投影与 MinIO 快照恢复均未发生，
     * 框架管理路径（上下文文件/总线/任务/子代理定义）在 Pod 内一律不存在。
     * 短路返回"不存在/空/no"与真实未物化状态完全一致，零功能损失；
     * 框架随后走宿主本地兜底（readWithOverride 两层读的第二层）。</p>
     *
     * <p>用户路径的 read/write/execute/glob 一律正常物化（resume 轨道从 MinIO 恢复工作区）。</p>
     */
    private boolean isFrameworkProbe(String command) {
        if (delegate != null || command == null) {
            return false; // 已物化不拦截
        }
        // ① read 探测：仅限框架管理路径（上下文文件/.agentscope 总线/tasks 任务文件）
        if (command.startsWith("if [ ! -f ")) {
            String head = command.length() > 220 ? command.substring(0, 220) : command;
            return CONTEXT_READ_FILES.stream().anyMatch(head::contains)
                    || head.contains(".agentscope/")
                    || head.contains("/tasks/");
        }
        // ② 框架目录枚举 glob
        if (command.startsWith("find '")) {
            return FRAMEWORK_GLOB_DIRS.stream().anyMatch(d -> command.startsWith("find '" + d + "' "));
        }
        // ③ 总线存在性探测
        return command.startsWith("test -e '.agentscope/");
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception {
        if (isFrameworkProbe(command)) {
            log.debug("[lazy-sandbox] 未物化框架探测短路（零沙箱）: {}", command.substring(0,
                    Math.min(90, command.length())));
            if (command.startsWith("if [ ! -f ")) {
                return new ExecResult(0, "__NOT_FOUND__", "", false); // read 的不存在哨兵值
            }
            if (command.startsWith("test -e ")) {
                return new ExecResult(0, "no", "", false); // 存在性探测答"不存在"
            }
            return new ExecResult(0, "", "", false); // glob 无匹配 = 空列表
        }
        return materializeFor(command).exec(runtimeContext, command, timeoutSeconds);
    }

    /** 物化触发命令记录（运维可见：什么命令第一次真正需要 Pod）。 */
    private AegisSandbox materializeFor(String command) {
        log.info("[lazy-sandbox] 物化触发命令: sessionId={}, cmd={}", sessionId,
                command != null && command.length() > 120
                        ? command.substring(0, 120) + "..." : command);
        return materialize();
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return materialize().persistWorkspace();
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        materialize().hydrateWorkspace(archive);
    }

    // =========================================================================
    // 物化与暴露
    // =========================================================================

    /** 已物化的真实沙箱（未物化返回 null，供 delete 释放记账判断） */
    public AegisSandbox getDelegateIfMaterialized() {
        return delegate;
    }

    /** 物化：双检锁保证并发下仅一次分配/重绑。 */
    private AegisSandbox materialize() {
        AegisSandbox d = delegate;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            if (delegate == null) {
                delegate = resumeState != null ? materializeFromResume() : materializeFromCreate();
                SandboxInstance inst = delegate.getInstance();
                log.info("[lazy-sandbox] 物化: sessionId={}, instanceId={}, pod={}, resume={}",
                        sessionId, inst.getInstanceId(), inst.getPodName(), resumeState != null);
            }
            return delegate;
        }
    }

    /** create 轨道物化：会话缓存快速路径 → 四级退化分配 → 登记。 */
    private AegisSandbox materializeFromCreate() {
        // ① 会话缓存快速路径：同会话本轮已物化过（并发工具调用）直接复用
        AegisSandbox cached = sessionHolder.getCurrentSandbox(sessionId);
        if (cached != null) {
            log.info("[lazy-sandbox] create 物化命中会话登记复用: sessionId={}, instanceId={}",
                    sessionId, cached.getInstance().getInstanceId());
            return cached;
        }
        // ② 四级退化分配（同槽位复用 → SYSTEM 常驻 → 干净 IDLE → 池内扩容）
        SandboxInstance inst = allocator.allocate(tenantId, userId, agentId, sessionId, agentType);
        SandboxPool pool = allocator.findPool(tenantId);
        AegisSandboxState state = new AegisSandboxState(inst,
                pool != null ? pool.getPoolCode() : "UNKNOWN", workspaceSpec);
        AegisSandbox sandbox = new AegisSandbox(inst, state, allocator, backend, snapshotClient);
        try {
            sandbox.start();
        } catch (Exception e) {
            log.warn("[lazy-sandbox] 物化 start 失败（继续执行）: {}", e.getMessage());
        }
        sessionHolder.register(sessionId, sandbox);
        return sandbox;
    }

    /** resume 轨道物化：DB 反查补全 + 槽位防抢占 + 探活 rebind；失败重新分配 + MinIO 恢复。 */
    private AegisSandbox materializeFromResume() {
        // 1. DB 反查补全（Redis state 缺 DB 主键 id/version，不补全则 rebind/release 静默失效）
        SandboxInstance inst = allocator.findByInstanceId(
                resumeState.getTenantId(), resumeState.getInstanceId());
        if (inst == null) {
            inst = buildInstanceFromState(resumeState);
        } else if (resumeState.getSlotKey() != null && inst.getSlotKey() != null
                && !resumeState.getSlotKey().equals(inst.getSlotKey())) {
            // 2. 槽位防抢占：实例已被其他槽位以干净 IDLE 重新分配（slotKey 改写），
            //    rebind 会覆盖他人会话绑定 → 放弃该实例走重分配
            log.warn("[lazy-sandbox] resume 物化 slotKey 不匹配，放弃复用走重分配: instanceId={}, "
                    + "stateSlotKey={}, dbSlotKey={}",
                    inst.getInstanceId(), resumeState.getSlotKey(), inst.getSlotKey());
            inst = buildInstanceFromState(resumeState);
        }

        // 3. 探活通过 → rebind（IDLE→OCCUPIED + 会话/心跳刷新）+ 登记
        if (allocator.probeAlive(inst.getTenantId(), inst)) {
            log.info("[lazy-sandbox] resume 物化命中存活 Pod: instanceId={}, pod={}, status={}",
                    inst.getInstanceId(), inst.getPodName(), inst.getStatus());
            allocator.rebind(inst, inst.getUserId(), inst.getAgentId(), inst.getSessionId());
            AegisSandbox sandbox = new AegisSandbox(inst, resumeState, allocator, backend, snapshotClient);
            try {
                sandbox.start();
            } catch (Exception e) {
                log.warn("[lazy-sandbox] resume 物化 start 失败（继续执行）: {}", e.getMessage());
            }
            sessionHolder.register(inst.getSessionId(), sandbox);
            return sandbox;
        }

        // 4. Pod 异常（节点重启/回收）→ 重新分配 + hydrate 从 MinIO 恢复 + 登记
        log.warn("[lazy-sandbox] resume 物化探活失败，重分配: instanceId={}", inst.getInstanceId());
        SandboxInstance fresh = allocator.allocate(inst.getTenantId(), inst.getUserId(),
                inst.getAgentId(), inst.getSessionId(), agentType);
        SandboxPool pool = allocator.findPool(inst.getTenantId());
        AegisSandboxState freshState = new AegisSandboxState(fresh,
                pool != null ? pool.getPoolCode() : "UNKNOWN", resumeState.getWorkspaceSpec());
        AegisSandbox sandbox = new AegisSandbox(fresh, freshState, allocator, backend, snapshotClient);
        try {
            sandbox.start();
        } catch (Exception e) {
            log.warn("[lazy-sandbox] 重分配 start 失败（继续执行）: {}", e.getMessage());
        }
        sessionHolder.register(fresh.getSessionId(), sandbox);
        return sandbox;
    }

    /** 从 state 标识构造内存态实例（DB 反查缺失时的降级路径）。 */
    private SandboxInstance buildInstanceFromState(AegisSandboxState state) {
        SandboxInstance inst = new SandboxInstance();
        inst.setInstanceId(state.getInstanceId());
        inst.setPodName(state.getPodName());
        inst.setNamespace(state.getNamespace());
        inst.setSlotKey(state.getSlotKey());
        inst.setTenantId(state.getTenantId());
        inst.setUserId(state.getUserId());
        inst.setAgentId(state.getAgentId());
        inst.setSessionId(state.getSessionId());
        return inst;
    }
}
