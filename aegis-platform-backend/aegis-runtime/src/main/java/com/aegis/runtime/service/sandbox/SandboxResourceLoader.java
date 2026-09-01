package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillSubscriptionMapper;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.aegis.runtime.service.sandbox.SandboxInstanceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A5：沙箱资源装载流水线（修 P1-2 / S-G5）。
 *
 * <p>在沙箱分配成功后将智能体可见资源清单（KB 元数据 / SKILL 文件 / MCP 连接配置）
 * 异步物化到 Pod 工作区 {@code /workspace/{tenantId}/{agentId}/{sessionId}/} 下，
 * 与 LLM 首 Token 并行，不阻塞 Agent 构建；工具首次执行前
 * {@link #awaitLoading(String, long)} 等待装载完成（超时降级并告警）。
 *
 * <h3>装载清单分轨（与 A6 MCP 分轨、A4 技能分轨对齐）</h3>
 * <ul>
 *   <li><b>UNIVERSAL</b>：用户订阅/自建 KB（自建含 DRAFT）+ 用户订阅/自建 SKILL（自建含 DRAFT）+ 用户订阅 MCP</li>
 *   <li><b>APPLICATION / SYSTEM</b>：仅 agent_binding 绑定资源（KB/SKILL/MCP，PUBLISHED）</li>
 * </ul>
 *
 * <h3>沙箱内目录规划（方案 3.4）</h3>
 * <ul>
 *   <li>KB：{@code {root}/kb/{kbId}/metadata.json} — KB 元数据（编码/名称/检索参数）</li>
 *   <li>SKILL：{@code {root}/skills/{skillCode}/SKILL.md} — 技能指令正文</li>
 *   <li>MCP：{@code {root}/mcp/config.json} — MCP 连接配置（endpoint/transport，<b>不落盘密钥</b>）</li>
 * </ul>
 *
 * <h3>指纹与增量装载</h3>
 * <p>装载清单（类型:标识:版本 排序拼接）的 SHA-256 记录在
 * {@code sbx_instance.resource_fingerprint}；分配复用 OCCUPIED 实例时对比指纹，
 * 一致则跳过装载（日志「指纹一致，跳过装载」，热复用秒级），不一致则清目录重装载。
 * 回收/释放/重建路径已同步失效指纹，防止脏指纹误跳过。
 *
 * <h3>initialized 三态（A5）</h3>
 * <ul>
 *   <li>0 = 脏（用户残留数据，待 admin 回收重初始化）</li>
 *   <li>1 = 标准 workspace（干净，资源未装载）</li>
 *   <li>2 = 资源已装载（本类装载成功后写入）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class SandboxResourceLoader {

    private final ISandboxBackend sandboxBackend;
    private final SandboxInstanceService sandboxInstanceService;
    private final ResourceQueryService resourceQueryService;
    private final SkillMapper skillMapper;
    private final SkillSubscriptionMapper skillSubscriptionMapper;

    /** 装载执行线程池（IO 密集：exec 远程调用，daemon 线程不阻塞 JVM 退出） */
    private final ExecutorService loaderExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sandbox-resource-loader");
        t.setDaemon(true);
        return t;
    });

    /** 进行中的装载任务注册表：instanceId -> 装载 Future（供 awaitLoading 等待） */
    private final ConcurrentHashMap<String, CompletableFuture<LoadOutcome>> loadingFutures =
            new ConcurrentHashMap<>();

    /** 单文件写入上限（防超长 exec 命令） */
    private static final int MAX_FILE_BYTES = 64 * 1024;

    /** exec 超时（秒） */
    private static final long EXEC_TIMEOUT_SEC = 30;

    /** 路径片段白名单（防注入：skillCode/kbId 等拼入 shell 路径） */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /**
     * 装载结果。
     *
     * @param loaded      是否装载成功（本次实际执行了装载且成功）
     * @param skipped     是否跳过（指纹一致或上下文无效）
     * @param fingerprint 装载清单指纹（跳过时为当前清单指纹）
     */
    public record LoadOutcome(boolean loaded, boolean skipped, String fingerprint) {
        static LoadOutcome skipped(String fingerprint) {
            return new LoadOutcome(false, true, fingerprint);
        }

        static LoadOutcome loaded(String fingerprint) {
            return new LoadOutcome(true, false, fingerprint);
        }
    }

    /**
     * 装载上下文（由沙箱分配成功后的调用方构造）。
     *
     * @param tenantId          租户 ID
     * @param userId            用户 ID（UNIVERSAL 分轨使用）
     * @param agentId           智能体 ID
     * @param sessionId         会话 ID（工作区子路径）
     * @param agentType         智能体类型（UNIVERSAL/APPLICATION/SYSTEM）
     * @param instanceId        沙箱实例 ID
     * @param k8sResourceId     K8s 资源标识（namespace/podName）
     * @param isolationStrategy 隔离策略（决定 workspaceRoot 派生规则）
     */
    public record LoadingContext(Long tenantId, Long userId, Long agentId, String sessionId,
                                 String agentType, String instanceId, String k8sResourceId,
                                 IsolationStrategy isolationStrategy) {
    }

    /**
     * 装载清单（分轨解析后的资源集合）。
     */
    private record LoadingManifest(List<KnowledgeBase> kbs, List<Skill> skills, List<McpService> mcps) {
    }

    public SandboxResourceLoader(ISandboxBackend sandboxBackend,
                                 SandboxInstanceService sandboxInstanceService,
                                 ResourceQueryService resourceQueryService,
                                 SkillMapper skillMapper,
                                 SkillSubscriptionMapper skillSubscriptionMapper) {
        this.sandboxBackend = sandboxBackend;
        this.sandboxInstanceService = sandboxInstanceService;
        this.resourceQueryService = resourceQueryService;
        this.skillMapper = skillMapper;
        this.skillSubscriptionMapper = skillSubscriptionMapper;
    }

    // =========================================================================
    // 公共 API
    // =========================================================================

    /**
     * 异步触发资源装载（分配成功后调用，不阻塞 Agent 构建）。
     *
     * <p>指纹一致（initialized=2 且 resource_fingerprint 匹配当前清单）时直接返回
     * 已完成的 skipped Future；否则提交后台任务执行装载。同一实例并发调用复用
     * 同一 Future（幂等）。
     *
     * @param ctx 装载上下文
     * @return 装载结果 Future
     */
    public CompletableFuture<LoadOutcome> loadAsync(LoadingContext ctx) {
        if (ctx == null || isBlank(ctx.instanceId()) || isBlank(ctx.k8sResourceId())
                || ctx.tenantId() == null || isBlank(ctx.sessionId())) {
            log.debug("[A5] 装载上下文不完整，跳过装载: ctx={}", ctx);
            return CompletableFuture.completedFuture(LoadOutcome.skipped(null));
        }

        // resume 路径自愈：userId 缺失时从 sbx_instance 占用记录补全（UNIVERSAL 分轨依赖）
        final LoadingContext effectiveCtx = resolveUserId(ctx);

        String fingerprint = computeFingerprint(buildManifest(effectiveCtx));
        SandboxInstance instance = sandboxInstanceService.findByInstanceId(effectiveCtx.instanceId());
        if (instance != null && Integer.valueOf(2).equals(instance.getInitialized())
                && fingerprint != null && fingerprint.equals(instance.getResourceFingerprint())) {
            log.info("[A5] 指纹一致，跳过装载: instanceId={}, slotKey={}, fingerprint={}",
                    effectiveCtx.instanceId(), instance.getSlotKey(), fingerprint);
            return CompletableFuture.completedFuture(LoadOutcome.skipped(fingerprint));
        }

        return loadingFutures.computeIfAbsent(effectiveCtx.instanceId(), id ->
                CompletableFuture.supplyAsync(() -> doLoad(effectiveCtx, fingerprint), loaderExecutor)
                        .whenComplete((outcome, err) -> loadingFutures.remove(id)));
    }

    /**
     * resume/重建路径 userId 缺失时，从 {@code sbx_instance} 占用记录补全
     * （跨进程恢复时 create() 选项不可用，但分配记录中保留了占用用户）。
     */
    private LoadingContext resolveUserId(LoadingContext ctx) {
        if (ctx.userId() != null) {
            return ctx;
        }
        try {
            SandboxInstance instance = sandboxInstanceService.findByInstanceId(ctx.instanceId());
            if (instance != null && instance.getUserId() != null) {
                return new LoadingContext(ctx.tenantId(), instance.getUserId(), ctx.agentId(),
                        ctx.sessionId(), ctx.agentType(), ctx.instanceId(), ctx.k8sResourceId(),
                        ctx.isolationStrategy());
            }
        } catch (Exception e) {
            log.debug("[A5] 补全 userId 失败（按现有上下文继续）: instanceId={}", ctx.instanceId());
        }
        return ctx;
    }

    /**
     * 等待实例装载完成（工具首次执行前调用）。
     *
     * <p>有进行中的装载任务则等待其完成（bounded 超时）；无任务时按
     * {@code sbx_instance.initialized} 判定（2=已装载）。超时或未装载返回 false，
     * 调用方降级为按需语义（继续执行，不阻断代码执行本身）并告警。
     *
     * @param instanceId   实例 ID
     * @param timeoutSec   等待超时（秒）
     * @return true 表示装载完成（或跳过）；false 表示超时/未装载
     */
    public boolean awaitLoading(String instanceId, long timeoutSec) {
        if (isBlank(instanceId)) {
            return false;
        }
        CompletableFuture<LoadOutcome> future = loadingFutures.get(instanceId);
        if (future != null) {
            try {
                LoadOutcome outcome = future.get(timeoutSec, TimeUnit.SECONDS);
                return outcome.skipped() || outcome.loaded();
            } catch (Exception e) {
                log.warn("[A5] 等待装载完成超时/异常（降级为按需拉取）: instanceId={}, error={}",
                        instanceId, e.getMessage());
                return false;
            }
        }
        SandboxInstance instance = sandboxInstanceService.findByInstanceId(instanceId);
        return instance != null && Integer.valueOf(2).equals(instance.getInitialized());
    }

    /**
     * 计算指定上下文的装载清单指纹（不执行装载）。
     *
     * @param ctx 装载上下文
     * @return SHA-256 指纹（十六进制）
     */
    public String computeFingerprint(LoadingContext ctx) {
        return computeFingerprint(buildManifest(ctx));
    }

    // =========================================================================
    // 装载执行
    // =========================================================================

    /**
     * 执行装载：分轨解析清单 → 清理旧产物（指纹变化场景）→ 写入 KB/SKILL/MCP → 标记 initialized=2。
     */
    private LoadOutcome doLoad(LoadingContext ctx, String fingerprint) {
        try {
            LoadingManifest manifest = buildManifest(ctx);
            String root = resolveWorkspaceRoot(ctx);

            // 指纹变化（复用场景）：清理旧装载产物后全量重写（清单级增量语义）
            SandboxInstance instance = sandboxInstanceService.findByInstanceId(ctx.instanceId());
            boolean fingerprintChanged = instance == null
                    || !Integer.valueOf(2).equals(instance.getInitialized())
                    || !fingerprintEquals(fingerprint, instance.getResourceFingerprint());
            if (fingerprintChanged) {
                cleanLoadedDirs(ctx, root);
            }

            writeKbMetadata(ctx, root, manifest.kbs());
            writeSkillFiles(ctx, root, manifest.skills());
            writeMcpConfig(ctx, root, manifest.mcps());

            sandboxInstanceService.markResourceLoaded(ctx.instanceId(), fingerprint);
            log.info("[A5] 资源装载完成: instanceId={}, agentType={}, root={}, kb={}, skills={}, mcp={}, fingerprint={}",
                    ctx.instanceId(), ctx.agentType(), root,
                    manifest.kbs().size(), manifest.skills().size(), manifest.mcps().size(), fingerprint);
            return LoadOutcome.loaded(fingerprint);
        } catch (Exception e) {
            log.error("[A5] 资源装载失败（工具执行时按需降级）: instanceId={}, error={}",
                    ctx.instanceId(), e.getMessage(), e);
            return new LoadOutcome(false, false, fingerprint);
        }
    }

    /**
     * 分轨解析装载清单。
     */
    private LoadingManifest buildManifest(LoadingContext ctx) {
        boolean universal = "UNIVERSAL".equalsIgnoreCase(ctx.agentType());
        return universal
                ? buildUniversalManifest(ctx)
                : buildBoundManifest(ctx);
    }

    /**
     * UNIVERSAL 轨道：用户订阅 KB + 用户自建 KB（含 DRAFT）+ 用户订阅/自建 SKILL + 用户订阅 MCP。
     */
    private LoadingManifest buildUniversalManifest(LoadingContext ctx) {
        Long tenantId = ctx.tenantId();
        Long userId = ctx.userId();

        // KB：用户订阅（PUBLISHED）+ 自建（含 DRAFT/REVIEWING，与 SKILL 轨道"自建含草稿"语义对齐）
        Set<Long> kbIds = new HashSet<>();
        if (userId != null) {
            kbIds.addAll(resourceQueryService.listUserSubscribedKbIds(tenantId, userId));
            kbIds.addAll(resourceQueryService.listUserOwnedKbIds(tenantId, userId));
        }
        List<KnowledgeBase> kbs = resourceQueryService.findReferenceableKnowledgeBasesByIds(kbIds, userId);

        // SKILL：用户订阅（PUBLISHED）+ 自建（含 DRAFT/REVIEWING）
        List<Skill> skills = new ArrayList<>(queryUserSkills(tenantId, userId));

        // MCP：用户订阅（PUBLISHED+启用）
        List<Long> mcpIds = userId != null
                ? resourceQueryService.listUserSubscribedMcpIds(tenantId, userId) : List.of();
        List<McpService> mcps = resourceQueryService.findActiveMcpServicesByIds(new HashSet<>(mcpIds));

        return new LoadingManifest(kbs, skills, mcps);
    }

    /**
     * APPLICATION/SYSTEM 轨道：仅 agent_binding 绑定资源（PUBLISHED）。
     */
    private LoadingManifest buildBoundManifest(LoadingContext ctx) {
        Long agentId = ctx.agentId();
        if (agentId == null) {
            return new LoadingManifest(List.of(), List.of(), List.of());
        }

        List<Long> kbIds = resourceQueryService.listBoundKbIds(agentId);
        List<KnowledgeBase> kbs = resourceQueryService.findKnowledgeBasesByIds(new HashSet<>(kbIds)).stream()
                .filter(kb -> kb.getLifeStatus() == AgentLifeStatus.PUBLISHED)
                .toList();

        List<Skill> skills = new ArrayList<>();
        for (AgentBinding binding : resourceQueryService.listEnabledBindings(agentId)) {
            if (binding.getResourceType() != ResourceType.SKILL || binding.getResourceId() == null) {
                continue;
            }
            Skill skill = resourceQueryService.findSkillById(binding.getResourceId());
            if (skill == null || skill.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
                continue;
            }
            skills.add(skill);
        }

        List<Long> mcpIds = resourceQueryService.listBoundMcpIds(agentId);
        List<McpService> mcps = resourceQueryService.findActiveMcpServicesByIds(new HashSet<>(mcpIds));

        return new LoadingManifest(kbs, skills, mcps);
    }

    /**
     * 查询用户可见技能：订阅（PUBLISHED）+ 自建（含 DRAFT/REVIEWING/PUBLISHED）。
     *
     * <p>与 {@code AegisSkillRepository.queryUserSkills} 分轨语义一致；
     * 装载到用户自己的沙箱工作区，作者对自建技能天然可见。
     */
    private List<Skill> queryUserSkills(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return List.of();
        }
        List<Skill> result = new ArrayList<>();

        List<Long> subscribedIds = skillSubscriptionMapper.selectList(
                        new LambdaQueryWrapper<SkillSubscription>()
                                .eq(SkillSubscription::getTenantId, tenantId)
                                .eq(SkillSubscription::getSubscriberType, SubscriberType.USER)
                                .eq(SkillSubscription::getSubscriberId, userId))
                .stream().map(SkillSubscription::getSkillId).toList();
        if (!subscribedIds.isEmpty()) {
            for (Skill s : skillMapper.selectBatchIds(subscribedIds)) {
                if (s == null || !tenantId.equals(s.getTenantId())) {
                    continue;
                }
                if (s.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
                    result.add(s);
                }
            }
        }

        result.addAll(skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getTenantId, tenantId)
                        .eq(Skill::getAuthorUserId, userId)
                        .eq(Skill::getDeleted, 0)
                        .in(Skill::getLifeStatus,
                                AgentLifeStatus.DRAFT,
                                AgentLifeStatus.REVIEWING,
                                AgentLifeStatus.PUBLISHED)));
        return result;
    }

    // =========================================================================
    // 文件写入
    // =========================================================================

    /**
     * 写入 KB 元数据：{root}/kb/{kbId}/metadata.json。
     */
    private void writeKbMetadata(LoadingContext ctx, String root, List<KnowledgeBase> kbs) {
        for (KnowledgeBase kb : kbs) {
            if (kb == null || kb.getId() == null) {
                continue;
            }
            String json = kbMetadataJson(kb);
            if (json == null) {
                continue;
            }
            String dir = root + "/kb/" + kb.getId();
            writeFile(ctx, dir + "/metadata.json", json);
        }
        ensureDir(ctx, root + "/kb");
    }

    /**
     * 写入技能文件：{root}/skills/{skillCode}/SKILL.md。
     */
    private void writeSkillFiles(LoadingContext ctx, String root, List<Skill> skills) {
        for (Skill skill : skills) {
            if (skill == null || isBlank(skill.getSkillCode())) {
                continue;
            }
            if (!SAFE_SEGMENT.matcher(skill.getSkillCode()).matches()) {
                log.warn("[A5] 技能编码含非法字符，跳过装载: skillCode={}", skill.getSkillCode());
                continue;
            }
            String content = skill.getInstructions() != null ? skill.getInstructions() : "";
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES) {
                log.warn("[A5] 技能文件超过 {}KB 上限，跳过装载: skillCode={}",
                        MAX_FILE_BYTES / 1024, skill.getSkillCode());
                continue;
            }
            String dir = root + "/skills/" + skill.getSkillCode();
            writeFile(ctx, dir + "/SKILL.md", content);
        }
        ensureDir(ctx, root + "/skills");
    }

    /**
     * 写入 MCP 连接配置：{root}/mcp/config.json（聚合所有服务，不落盘密钥）。
     */
    private void writeMcpConfig(LoadingContext ctx, String root, List<McpService> mcps) {
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        for (McpService mcp : mcps) {
            if (mcp == null || isBlank(mcp.getMcpCode())) {
                continue;
            }
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", mcp.getEndpoint() != null ? mcp.getEndpoint() : "");
            if (mcp.getProtocol() != null) {
                server.put("transport", mcp.getProtocol().name().toLowerCase());
            }
            mcpServers.put(mcp.getMcpCode(), server);
        }
        String json = "{\n  \"mcpServers\": " + toJson(mcpServers) + "\n}";
        writeFile(ctx, root + "/mcp/config.json", json);
        ensureDir(ctx, root + "/mcp");
    }

    /**
     * KB 元数据 JSON（编码/名称/描述/检索参数，供沙箱内工具感知可用知识库）。
     */
    private String kbMetadataJson(KnowledgeBase kb) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("kbId", kb.getId());
        meta.put("kbCode", kb.getKbCode());
        meta.put("kbName", kb.getKbName());
        meta.put("description", kb.getDescription());
        meta.put("embeddingModel", kb.getEmbeddingModel());
        meta.put("topK", kb.getTopK());
        meta.put("docCount", kb.getDocCount());
        meta.put("version", kb.getVersion());
        return "{\n  \"knowledgeBase\": " + toJson(meta) + "\n}";
    }

    /**
     * 通过 exec + base64 写入文件。
     *
     * <p>不使用 heredoc：{@code KubernetesSandboxBackend.exec} 会在命令末尾追加
     * {@code ; echo "__EXIT_CODE:$?"}，污染 heredoc 结束定界符行（定界符必须独占一行），
     * 导致 heredoc 悬空产生语法错误。base64 字符集（A-Za-z0-9+/=）不含 shell 元字符，
     * 单行命令与追加机制天然兼容（与 AegisExecuteTool/AegisSandbox 传输模式一致）。
     */
    private void writeFile(LoadingContext ctx, String path, String content) {
        String b64 = java.util.Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String cmd = "mkdir -p \"$(dirname '" + path + "')\" && printf '%s' '" + b64
                + "' | base64 -d > '" + path + "'";
        ISandboxBackend.ExecResult result = sandboxBackend.exec(
                ctx.tenantId(), ctx.k8sResourceId(), cmd, EXEC_TIMEOUT_SEC);
        if (result == null || result.exitCode != 0) {
            throw new IllegalStateException("写入沙箱文件失败: path=" + path
                    + ", stderr=" + (result != null ? result.stderr : "null result"));
        }
        log.debug("[A5] 沙箱文件写入成功: instanceId={}, path={}", ctx.instanceId(), path);
    }

    /**
     * 确保目录存在（空清单时也保证目录结构，满足验收「存在对应 KB/技能/MCP 目录」）。
     */
    private void ensureDir(LoadingContext ctx, String dir) {
        ISandboxBackend.ExecResult result = sandboxBackend.exec(
                ctx.tenantId(), ctx.k8sResourceId(), "mkdir -p '" + dir + "'", EXEC_TIMEOUT_SEC);
        if (result == null || result.exitCode != 0) {
            log.warn("[A5] 创建沙箱目录失败（忽略，后续装载重试）: instanceId={}, dir={}",
                    ctx.instanceId(), dir);
        }
    }

    /**
     * 清理旧装载产物（指纹变化时的清单级增量：重写 KB/SKILL/MCP 三目录）。
     */
    private void cleanLoadedDirs(LoadingContext ctx, String root) {
        String cmd = "rm -rf '" + root + "/kb' '" + root + "/skills' '" + root + "/mcp' 2>/dev/null";
        sandboxBackend.exec(ctx.tenantId(), ctx.k8sResourceId(), cmd, EXEC_TIMEOUT_SEC);
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * 工作区根路径派生（与 {@code AegisSandboxState.resolveWorkspaceRoot()} 对齐）。
     */
    private String resolveWorkspaceRoot(LoadingContext ctx) {
        IsolationStrategy strategy = ctx.isolationStrategy() != null
                ? ctx.isolationStrategy() : IsolationStrategy.SHARED_PER_SCOPE;
        String tenantPart = ctx.tenantId() != null ? String.valueOf(ctx.tenantId()) : "default";
        String sessionPart = isBlank(ctx.sessionId()) ? "default" : ctx.sessionId();
        return switch (strategy) {
            case SHARED_PER_SCOPE -> {
                String agentPart = ctx.agentId() != null ? String.valueOf(ctx.agentId()) : "default";
                yield "/workspace/" + tenantPart + "/" + agentPart + "/" + sessionPart;
            }
            case DEDICATED_PER_SESSION, SHARED_WITH_QUOTA -> "/workspace/" + tenantPart + "/" + sessionPart;
        };
    }

    /**
     * 计算装载清单指纹：类型:标识:version 排序拼接后 SHA-256。
     */
    private String computeFingerprint(LoadingManifest manifest) {
        List<String> parts = new ArrayList<>();
        for (KnowledgeBase kb : manifest.kbs()) {
            parts.add("KB:" + kb.getId() + ":" + kb.getVersion());
        }
        for (Skill s : manifest.skills()) {
            parts.add("SKILL:" + s.getSkillCode() + ":" + s.getActiveVersion());
        }
        for (McpService m : manifest.mcps()) {
            parts.add("MCP:" + m.getMcpCode() + ":" + m.getVersion());
        }
        parts.sort(String::compareTo);
        return sha256(String.join("|", parts));
    }

    private String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算装载指纹失败: " + e.getMessage(), e);
        }
    }

    private boolean fingerprintEquals(String a, String b) {
        return a != null && a.equals(b);
    }

    /**
     * 简易 JSON 序列化（fastjson2，值已为标量/Map，无需全量 ObjectMapper）。
     */
    private String toJson(Object value) {
        return com.alibaba.fastjson2.JSON.toJSONString(value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PreDestroy
    public void shutdown() {
        loaderExecutor.shutdown();
    }
}
