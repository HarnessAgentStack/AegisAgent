package com.aegis.runtime.service.agent;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.domain.session.Session;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.core.dto.chat.ChatRequest;
import com.aegis.core.dto.chat.SessionResourcesRef;
import com.aegis.core.dto.chat.SkillRef;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.enums.session.SessionStatus;
import com.aegis.dal.mapper.org.UserBaseMapper;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.conversation.SessionManageService;
import com.aegis.runtime.service.conversation.ContentAdapter;
import com.aegis.runtime.service.document.FileStorageService;
import com.aegis.runtime.infrastructure.document.AttachmentStrategy;
import com.aegis.runtime.infrastructure.document.ImageResizeUtil;
import com.aegis.runtime.integration.agent.AegisAgentInstanceManager;
import com.aegis.runtime.integration.skill.AegisSkillRepository;
import com.aegis.runtime.integration.model.ModelRouteResolver;
import com.aegis.runtime.integration.pool.AgentPoolManager;
import com.aegis.runtime.integration.pool.AgentRuntimeTemplate;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能体统一装配服务。
 *
 * <p>一次性完成：模板加载 -> HarnessAgent 构建 -> 会话创建 -> 版本快照 -> 用户消息持久化。
 * 输出完整的 {@link AegisTaskContext}（含 agent 和 runtimeContext），供 {@link TaskExecutionService} 直接使用。
 *
 * <h3>P0 改造：附件解析能力协商</h3>
 * <p>原 {@code buildMessageWithAttachments} 使用硬编码 4000 字符截断，
 * 现改为通过 {@link ModelCapabilityResolver} 协商模型能力 + {@link ContentAdapter} 智能裁剪。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentAssemblyService {

    private final AgentPoolManager agentPoolManager;
    private final AegisAgentInstanceManager agentInstanceManager;
    private final SessionManageService sessionManageService;
    private final FileStorageService fileStorageService;
    private final ModelCapabilityResolver capabilityResolver;
    private final ContentAdapter contentAdapter;
    private final ModelRouteResolver modelRouteResolver;
    private final ResourceQueryService resourceQueryService;
    private final UserBaseMapper userMapper;

    /**
     * 装配执行上下文（同步，在 boundedElastic 线程执行）。
     *
     * @param request 对话请求（已通过 ChatRequestValidator 校验）
     * @param taskId  任务ID
     * @return 完整的执行上下文（含 HarnessAgent）；装配失败时 ctx.blocked=true
     */
    public AegisTaskContext assemble(ChatRequest request, String taskId) {
        Long tenantId = request.getTenantId();
        Long userId = request.getUserId();
        Long agentId = request.getAgentId();

        // 在 boundedElastic 线程上显式设置租户上下文（供 MyBatis-Plus 多租户插件读取）
        if (tenantId != null) {
            TenantContextHolder.set(TenantContext.builder().tenantId(tenantId).build());
        }

        AegisTaskContext.AegisTaskContextBuilder builder = AegisTaskContext.builder()
                .taskId(taskId)
                .agentId(agentId)
                .tenantId(tenantId)
                .userId(userId)
                .userName(resolveUserName(userId))
                .startTime(LocalDateTime.now())
                .traceId(taskId)
                .isolationStrategy(request.resolveIsolationStrategy())
                .requestedSkills(request.getSkills())
                .sessionResources(request.getResources());

        try {
            // Step 1: 加载模板 (Layer 1) + 生命周期校验
            AgentRuntimeTemplate template = resolveAgentTemplate(agentId, tenantId, userId, builder);
            if (template == null) {
                return builder.build();
            }

            // Step 2: 创建/获取会话
            if (request.getSessionId() == null || request.getSessionId().isEmpty()) {
                sessionManageService.freezeActiveSession(userId, agentId);
            }
            Session session = sessionManageService.getOrCreateSession(agentId, request.getSessionId(), tenantId, userId);
            builder.sessionId(session.getSessionId());

            // Step 3: 恢复配置（快照优先）
            AgentConfig cfg = restoreConfigFromSnapshot(session, template.getAgentConfig(),
                    agentId, tenantId, builder);

            // Step 4: 锁定版本快照（P2-7 修复：仅首轮锁定，已有快照则跳过）
            // 原实现每轮重锁 → 用当前 template 版本覆盖快照，但运行配置取旧快照 →
            // agentVersion 标签与实际运行配置漂移；bindings 始终取当前 template（快照中的 bindings 是死数据）。
            // 改为首轮锁定：新会话固化配置全程不变，智能体编辑后进行中会话沿用旧配置至会话结束。
            if (session.getVersionSnapshot() == null) {
                sessionManageService.lockVersionSnapshot(session.getSessionId(), agentId,
                        template.getVersion(), cfg, template.getBindings(),
                        template.getAgentDef() != null ? template.getAgentDef().getGovernanceTier() : null);
            }

            // Step 5: 持久化用户消息（含附件） + 更新状态 -> THINKING
            // HITL 恢复场景：无新用户消息时跳过持久化，直接将状态设为 STARTED（已由审批接口设置）
            AdaptedContent adapted = buildMessageWithAttachments(
                    request.getMessage(), request.getAttachments(), tenantId, userId, cfg);
            String effectiveMessage = adapted.text();
            if (effectiveMessage != null && !effectiveMessage.isEmpty()) {
                sessionManageService.persistUserMessage(session.getSessionId(), tenantId, userId, effectiveMessage);
                sessionManageService.updateStatus(session.getSessionId(), SessionStatus.THINKING);
            } else {
                // HITL 恢复：保持 STARTED 状态（由 hitl/approve 接口设置）
                log.info("HITL 恢复：跳过用户消息持久化，保持 STARTED 状态: sessionId={}", session.getSessionId());
            }
            builder.userMessage(effectiveMessage);
            if (!adapted.imageBlocks().isEmpty()) {
                builder.multimodalBlocks(adapted.imageBlocks());
            }

            // Step 6: 构建/复用 HarnessAgent (Layer 2)
            AgentDef def = template.getAgentDef();
            String agentType = def != null && def.getAgentType() != null
                    ? def.getAgentType().name() : "UNIVERSAL";
            // T3/T4：装配期一次查询 enabled 绑定 + 批量加载绑定 Skill 实体，
            // 供装配链（ToolBridge）与运行时中间件（RAG/SkillRepository/BindingSync）共享，
            // 消除同次会话内 5+ 次 listEnabledBindings 重复 SELECT 与绑定 Skill 双重加载
            AssemblyResourceContext resources = buildResourceContext(agentId);
            HarnessAgent agent = acquireHarnessAgent(builder, session.getSessionId(), cfg, def,
                    builder.build().getIsolationStrategy(), agentType, resources);
            builder.agent(agent);

            // Step 7: 构造 RuntimeContext（含会话级资源引用）
            // A4：注入 agentType/agentId，供 AegisSkillRepository 按智能体类型分轨装载技能
            // T3/T4：注入装配期资源上下文（enabled 绑定 + 绑定 Skill 实体），供运行时中间件读取
            RuntimeContext rc = buildRuntimeContext(session.getSessionId(), tenantId, userId,
                    request.getDeptId(), request.getSkills(), request.getResources(),
                    agentType, agentId, resources);

            // Step 8: 构建 AegisTaskContext 并注入 RuntimeContext（供中间件链使用）
            AegisTaskContext taskCtx = builder.build();
            rc.put(AegisTaskContext.class, taskCtx);
            taskCtx.setRuntimeContext(rc);

            return taskCtx;

        } catch (Exception e) {
            log.error("Assembly failed: taskId={}", taskId, e);
            builder.blocked(true).blockReason("装配智能体失败: " + e.getMessage());
            return builder.build();
        }
    }

    /**
     * 装配智能体模板并校验生命周期。
     */
    private AgentRuntimeTemplate resolveAgentTemplate(Long agentId, Long tenantId, Long userId,
                                                       AegisTaskContext.AegisTaskContextBuilder builder) {
        AgentRuntimeTemplate template = agentPoolManager.getTemplate(agentId, null, tenantId, userId);
        if (template == null || template.getAgentDef() == null) {
            builder.blocked(true).blockReason("智能体不存在或未发布: agentId=" + agentId);
            return null;
        }
        AgentDef def = template.getAgentDef();

        String blockReason = checkLifeStatus(def, userId);
        if (blockReason != null) {
            builder.blocked(true).blockReason(blockReason);
            return null;
        }

        builder.agentVersion(template.getVersion())
                .agentDef(def)
                .agentConfig(template.getAgentConfig())
                .bindings(template.getBindings())
                .template(template);
        return template;
    }

    /**
     * 校验智能体生命周期状态。
     *
     * <p>规则：
     * <ul>
     *   <li>PUBLISHED：所有人可用<restoreConfigFromSnapshot/li>
     *   <li>DRAFT / REJECTED：仅作者本人可自用调试（旁路发布闭环），便于开发预览</li>
     *   <li>其他状态（REVIEWING / ARCHIVED / null）：一律拒绝</li>
     * </ul>
     */
    private String checkLifeStatus(AgentDef def, Long userId) {
        if (def.getLifeStatus() == null) {
            return "智能体当前状态不可使用: null";
        }
        // 已发布智能体（含平台预置通用智能体）可直接使用
        if (def.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            return null;
        }
        // 草稿/驳回态：作者本人可自用调试（旁路发布闭环）
        if ((def.getLifeStatus() == AgentLifeStatus.DRAFT
                || def.getLifeStatus() == AgentLifeStatus.REJECTED)
                && userId != null
                && userId.equals(def.getAuthorUserId())) {
            log.info("作者自用旁路放行: agentId={}, status={}, authorUserId={}, userId={}",
                    def.getId(), def.getLifeStatus(), def.getAuthorUserId(), userId);
            return null;
        }
        return "智能体当前状态不可使用: " + def.getLifeStatus();
    }

    /**
     * 从版本快照恢复 AgentConfig。
     */
    private AgentConfig restoreConfigFromSnapshot(Session session, AgentConfig templateCfg,
                                                   Long agentId, Long tenantId,
                                                   AegisTaskContext.AegisTaskContextBuilder builder) {
        String versionSnapshot = session.getVersionSnapshot();
        if (versionSnapshot == null || versionSnapshot.isEmpty()) {
            return templateCfg;
        }
        try {
            com.alibaba.fastjson2.JSONObject snapshot = com.alibaba.fastjson2.JSON.parseObject(versionSnapshot);
            com.alibaba.fastjson2.JSONObject cfgJson = snapshot.getJSONObject("agentConfig");
            if (cfgJson == null) {
                return templateCfg;
            }
            AgentConfig snapshotCfg = AgentConfig.builder()
                    .agentId(agentId)
                    .version(snapshot.getString("agentVersion"))
                    .systemPrompt(cfgJson.getString("systemPrompt"))
                    .modelTier(ModelTier.valueOf(cfgJson.getString("modelTier")))
                    .temperature(cfgJson.getBigDecimal("temperature"))
                    .maxTurns(cfgJson.getInteger("maxTurns"))
                    .enabledTools(cfgJson.getString("enabledTools"))
                    .build();
            snapshotCfg.setTenantId(tenantId);
            builder.agentConfig(snapshotCfg);
            log.info("Loaded agentConfig from version snapshot: sessionId={}, version={}",
                    session.getSessionId(), snapshot.getString("agentVersion"));
            return snapshotCfg;
        } catch (Exception e) {
            log.warn("Failed to load config from version snapshot, use template config: sessionId={}, error={}",
                    session.getSessionId(), e.getMessage());
            return templateCfg;
        }
    }

    /**
     * 从实例池获取或构建 HarnessAgent 实例。
     *
     * @param resources 装配期资源上下文（T3/T4：enabled 绑定 + 绑定 Skill 实体，
     *                  传递给 ToolBridge 注册 Toolkit，避免其按 agentId 重新查 DB）
     */
    private HarnessAgent acquireHarnessAgent(AegisTaskContext.AegisTaskContextBuilder builder,
                                              String sessionId, AgentConfig cfg, AgentDef def,
                                              IsolationStrategy isolationStrategy, String agentType,
                                              AssemblyResourceContext resources) {
        long tenantId = builder.build().getTenantId() != null ? builder.build().getTenantId() : 0L;
        long userId = builder.build().getUserId() != null ? builder.build().getUserId() : 0L;
        long agentId = builder.build().getAgentId() != null ? builder.build().getAgentId() : 0L;
        String sysPrompt = cfg != null ? cfg.getSystemPrompt() : null;
        List<AgentBinding> bindings = builder.build().getBindings();
        String modelTier = cfg != null && cfg.getModelTier() != null
                ? cfg.getModelTier().name() : "STANDARD";

        // 从会话资源中提取临时选择的 MCP 服务ID列表
        List<Long> sessionMcpServiceIds = null;
        SessionResourcesRef sessionResources = builder.build().getSessionResources();
        if (sessionResources != null && sessionResources.getMcpIds() != null) {
            sessionMcpServiceIds = sessionResources.getMcpIds();
        }

        // preloadedTools 传 null 而非空列表，使 AegisAgentInstanceManager.loadToolkit
        // 在 preloadedTools 为空时自动走绑定装配路径（toolBridge.resolveTools(toolkit, resources)），
        // 通过装配期资源上下文加载工具。传空列表会导致 resolveTools(toolkit, emptyList) 直接 return。
        return agentInstanceManager.acquireOrBuild(
                sessionId, tenantId, userId, agentType, agentId, sysPrompt,
                bindings != null ? bindings : Collections.emptyList(),
                null,
                modelTier, isolationStrategy,
                sessionMcpServiceIds,
                resources != null ? resources : AssemblyResourceContext.EMPTY);
    }

    /**
     * 构建装配期资源上下文（T3/T4 核心：一次查询、多处共享）。
     *
     * <p>一次 {@code listEnabledBindings(agentId)} + 一次 {@code selectBatchIds} 批量加载
     * 绑定 Skill 实体。结果供装配链（ToolBridge 的 TOOL/SKILL 注册）与运行时中间件
     * （RAG 检索 / SkillRepository 分轨装载 / BindingSync 指纹）共享，消除重复 SELECT。
     *
     * <p>查询失败时返回空上下文并记录日志，不阻断装配（各消费方回退自身 DB 直查）。
     */
    private AssemblyResourceContext buildResourceContext(Long agentId) {
        try {
            List<AgentBinding> enabledBindings = resourceQueryService.listEnabledBindings(agentId);
            List<Long> skillIds = enabledBindings.stream()
                    .filter(b -> b.getResourceType() == com.aegis.core.enums.resource.ResourceType.SKILL
                            && b.getResourceId() != null)
                    .map(AgentBinding::getResourceId)
                    .distinct()
                    .toList();
            List<com.aegis.core.domain.resource.Skill> boundSkills =
                    resourceQueryService.findSkillsByIds(skillIds);
            return new AssemblyResourceContext(enabledBindings, boundSkills);
        } catch (Exception e) {
            log.warn("装配期资源上下文构建失败，回退各消费方 DB 直查: agentId={}", agentId, e);
            return AssemblyResourceContext.EMPTY;
        }
    }

    /**
     * 构造 AgentScope RuntimeContext。
     *
     * <p>P0 @SKILL：将租户/部门上下文与显式引用的技能 code 列表写入 RuntimeContext 属性，
     * 供 {@link AegisSkillRepository}（{@code RuntimeContextSkillRepository}）在
     * {@code getAllSkills(ctx)} 中消费，强制包含被 {@code @} 选中的技能。
     *
     * <p>P1 会话资源：将会话级资源引用（知识库ID列表、MCP服务ID列表）写入 RuntimeContext 属性，
     * 并与智能体绑定资源合并，供资源查询和验证逻辑消费。
     *
     * <p>P2 资源过滤：过滤已发布且安全等级匹配的资源，确保运行时安全。
     *
     * <p>A4 技能订阅分轨：注入 agentType/agentId，供 {@link AegisSkillRepository}
     * 按智能体类型选择装载轨道（UNIVERSAL=订阅+自建；APPLICATION/SYSTEM=绑定）。
     */
    private RuntimeContext buildRuntimeContext(String sessionId, long tenantId, long userId,
                                               Long deptId, List<SkillRef> skills,
                                               SessionResourcesRef sessionResources,
                                               String agentType, long agentId,
                                               AssemblyResourceContext resources) {
        List<String> requestedCodes = (skills == null) ? List.of()
                : skills.stream()
                        .map(SkillRef::getSkillCode)
                        .filter(code -> code != null && !code.isBlank())
                        .toList();

        RuntimeContext.Builder builder = RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(String.valueOf(userId))
                .put("tenantId", String.valueOf(tenantId))
                .put("deptId", deptId != null ? String.valueOf(deptId) : "")
                .put(AegisSkillRepository.CTX_AGENT_TYPE, agentType != null ? agentType : "UNIVERSAL")
                .put(AegisSkillRepository.CTX_AGENT_ID, String.valueOf(agentId))
                .put(AegisSkillRepository.CTX_REQUESTED_SKILLS, requestedCodes)
                // T3/T4：注入装配期资源上下文，供 RAG/SkillRepository/BindingSync 中间件读取，
                // 消除运行时重复 listEnabledBindings 与绑定 Skill 双重加载
                .put(AssemblyResourceContext.CTX_ENABLED_BINDINGS,
                        resources != null ? resources.enabledBindings() : List.of())
                .put(AssemblyResourceContext.CTX_BOUND_SKILLS,
                        resources != null ? resources.boundSkills() : List.of());

        // P1: 注入会话级资源引用（合并智能体绑定资源 + 会话选择资源 + UNIVERSAL 用户订阅/自建）
        SessionResourcesRef mergedResources = mergeResourcesWithAgentBindings(
                sessionId, tenantId, userId, agentType, agentId, sessionResources, resources);

        // P2: 过滤有效资源（知识库：已发布 + 用户自建草稿/审核中；MCP：已发布且启用）
        List<Long> validKbIds = filterValidKbIds(mergedResources.getKbIds(), userId);
        List<Long> validMcpIds = filterValidMcpIds(mergedResources.getMcpIds());

        if (!validKbIds.isEmpty()) {
            builder.put("aegis.sessionKbIds", validKbIds);
        }
        if (!validMcpIds.isEmpty()) {
            builder.put("aegis.sessionMcpIds", validMcpIds);
        }

        log.info("RuntimeContext 资源注入: sessionId={}, validKbCount={}, validMcpCount={}",
                sessionId, validKbIds.size(), validMcpIds.size());

        return builder.build();
    }

    /**
     * 合并会话资源与智能体绑定资源（v3：UNIVERSAL 轨道C 只装 MCP，不装知识库）。
     *
     * <p>三条资源轨道的合并优先级（高→低）：
     * <ol>
     *   <li><b>轨道 A</b> 会话显式选择资源（前端 UI 勾选，ChatRequest.resources）— 所有智能体生效</li>
     *   <li><b>轨道 B</b> 智能体绑定资源（agent_binding）— 所有智能体生效</li>
     *   <li><b>轨道 C</b> UNIVERSAL 用户订阅 MCP — 仅 agentType=UNIVERSAL 时生效，只装 MCP</li>
     * </ol>
     *
     * <h3>UNIVERSAL 知识库语义变更（v3）</h3>
     * <p>原 v2 轨道C 会把用户自建/订阅的知识库自动注入 UNIVERSAL 智能体，导致"没指定知识库也触发 RAG"。
     * 新规则：<b>知识库必须显式指定</b>（轨道A 会话勾选 / 轨道B agent_binding 绑定），
     * UNIVERSAL 智能体的轨道C 只自动装载 MCP 服务（联网搜索、工具调用等），
     * 不自动装载任何知识库。这样保证"用户不指定知识库 = 纯 LLM + 工具"的预期行为。
     *
     * @param sessionId       会话ID
     * @param tenantId        租户ID
     * @param userId          用户ID
     * @param agentType       智能体类型（UNIVERSAL / APPLICATION / SYSTEM）
     * @param agentId         智能体ID
     * @param sessionResources 会话级资源引用
     * @param resources       装配期资源上下文（enabledBindings）
     * @return 合并后的资源引用
     */
    private SessionResourcesRef mergeResourcesWithAgentBindings(String sessionId, long tenantId, long userId,
                                                                String agentType, long agentId,
                                                                SessionResourcesRef sessionResources,
                                                                AssemblyResourceContext resources) {
        try {
            // 轨道 B：从装配期资源上下文派生智能体绑定的资源（T3 避免重复 SELECT）
            List<AgentBinding> enabledBindings = resources != null
                    ? resources.enabledBindings() : List.of();
            List<Long> boundKbIds = enabledBindings.stream()
                    .filter(b -> b.getResourceType() == com.aegis.core.enums.resource.ResourceType.KNOWLEDGE_BASE
                            && b.getResourceId() != null)
                    .map(AgentBinding::getResourceId)
                    .toList();
            List<Long> boundMcpIds = enabledBindings.stream()
                    .filter(b -> b.getResourceType() == com.aegis.core.enums.resource.ResourceType.MCP_SERVICE
                            && b.getResourceId() != null)
                    .map(AgentBinding::getResourceId)
                    .toList();

            // 轨道 C：UNIVERSAL 只自动装载订阅的 MCP（知识库不自动装）
            List<Long> universalMcpIds = Collections.emptyList();
            if ("UNIVERSAL".equals(agentType)) {
                List<Long> subscribedMcp = resourceQueryService.listUserSubscribedMcpIds(tenantId, userId);
                universalMcpIds = subscribedMcp; // MCP 无"自建"概念
            }

            // 知识库：只有轨道 B（绑定）+ 轨道 A（会话选择）
            // UNIVERSAL 不再自动装用户自建/订阅的知识库 — 用户必须显式指定
            List<Long> mergedKbIds = new ArrayList<>(boundKbIds);

            // MCP：轨道 C（UNIVERSAL 订阅）+ 轨道 B（绑定）
            List<Long> mergedMcpIds = resourceQueryService.mergeAndDeduplicate(universalMcpIds, boundMcpIds);

            if (sessionResources != null) {
                mergedKbIds = resourceQueryService.mergeAndDeduplicate(
                        sessionResources.getKbIds(), mergedKbIds);
                mergedMcpIds = resourceQueryService.mergeAndDeduplicate(
                        sessionResources.getMcpIds(), mergedMcpIds);
            }

            log.info("资源合并: sessionId={}, agentType={}, boundKb={}, universalMcp={}, finalKb={}, finalMcp={}",
                    sessionId, agentType, boundKbIds.size(), universalMcpIds.size(),
                    mergedKbIds.size(), mergedMcpIds.size());

            return SessionResourcesRef.builder()
                    .kbIds(mergedKbIds)
                    .mcpIds(mergedMcpIds)
                    .build();

        } catch (Exception e) {
            log.warn("资源合并异常，降级使用原始资源: sessionId={}", sessionId, e);
            return sessionResources != null ? sessionResources : SessionResourcesRef.builder().build();
        }
    }

    /**
     * 过滤有效的知识库 ID（可引用的）。
     *
     * <p>引用规则：PUBLISHED 任何用户可引用；DRAFT/REVIEWING 仅创建者本人可引用；
     * ARCHIVED 不可引用。与 {@code AgentResourceController} 面板候选集语义一致。
     *
     * @param kbIds  知识库ID列表
     * @param userId 当前用户ID（判定创建者身份）
     * @return 有效的知识库ID列表
     */
    private List<Long> filterValidKbIds(List<Long> kbIds, Long userId) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBase> validKbs = resourceQueryService
                .findReferenceableKnowledgeBasesByIds(Set.copyOf(kbIds), userId);
        return validKbs.stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toList());
    }

    /**
     * 过滤有效的 MCP 服务 ID（已发布且启用的）。
     *
     * @param mcpIds MCP服务ID列表
     * @return 有效的MCP服务ID列表
     */
    private List<Long> filterValidMcpIds(List<Long> mcpIds) {
        if (mcpIds == null || mcpIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<McpService> validMcps = resourceQueryService.findActiveMcpServicesByIds(Set.copyOf(mcpIds));
        return validMcps.stream()
                .map(McpService::getId)
                .collect(Collectors.toList());
    }

    /**
     * 将附件信息拼入用户消息，使 LLM 感知附件内容。
     *
     * <h3>P0 改造</h3>
     * <p>原实现：硬编码 4000 字符截断，所有文件类型一刀切处理。
     * <p>新实现：通过 {@link ModelCapabilityResolver} 协商模型能力，
     * 使用 {@link ContentAdapter} 智能裁剪替代硬截断；
     * NATIVE_PASS 图片额外构造 {@link ImageBlock} 多模态内容块。
     */
    private AdaptedContent buildMessageWithAttachments(String message, List<AttachmentRef> attachments,
                                                       Long tenantId, Long userId, AgentConfig cfg) {
        if (attachments == null || attachments.isEmpty()) {
            return new AdaptedContent(message, List.of());
        }

        ModelTier modelTier = cfg != null ? cfg.getModelTier() : ModelTier.STANDARD;

        // 1. 能力协商：根据模型能力为每个附件决定处理策略
        List<AttachmentStrategy> strategies = capabilityResolver.resolve(tenantId, modelTier, attachments);

        // 2. 解析 ENGINE_PARSE 策略的附件
        for (AttachmentStrategy strategy : strategies) {
            capabilityResolver.parseAttachment(strategy, tenantId, userId);
        }

        // 3. 获取模型上下文窗口
        int contextWindow = modelRouteResolver.resolveContextWindow(tenantId, modelTier);

        // 4. 内容适配：智能裁剪 + 多附件合并（文本部分）
        String text = contentAdapter.adapt(strategies, contextWindow, message);

        // 5. NATIVE_PASS 图片构造多模态 ImageBlock（base64）
        List<ContentBlock> imageBlocks = buildImageBlocks(strategies, tenantId, userId);

        return new AdaptedContent(text, imageBlocks);
    }

    /**
     * 从 NATIVE_PASS 图片策略构造 AgentScope {@link ImageBlock} 列表。
     *
     * <p>读取 MinIO 中的图片字节 → {@link ImageResizeUtil} 缩放 → Base64 编码 →
     * 构造 {@link Base64Source} + {@link ImageBlock}。任何异常均跳过该图片（降级不阻塞）。
     *
     * @param strategies 附件策略列表
     * @param tenantId   租户 ID（归属校验）
     * @param userId     用户 ID（归属校验）
     * @return 多模态 ImageBlock 列表；无 NATIVE_PASS 图片时返回空列表
     */
    private List<ContentBlock> buildImageBlocks(List<AttachmentStrategy> strategies,
                                                Long tenantId, Long userId) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (AttachmentStrategy strategy : strategies) {
            if (strategy.getType() != AttachmentStrategy.StrategyType.NATIVE_PASS
                    || !"image".equals(strategy.getFileCategory())) {
                continue;
            }
            AttachmentRef att = strategy.getAttachment();
            if (att == null || att.getFileId() == null) {
                continue;
            }
            try {
                byte[] imageBytes = fileStorageService.readContent(att.getFileId(), tenantId, userId);
                if (imageBytes == null || imageBytes.length == 0) {
                    log.warn("NATIVE_PASS 图片字节为空，跳过: fileId={}", att.getFileId());
                    continue;
                }
                byte[] processed = ImageResizeUtil.resizeIfNeeded(imageBytes, att.getName());
                String base64Data = Base64.getEncoder().encodeToString(processed);
                String mediaType = ImageResizeUtil.guessMimeType(att.getName());
                Base64Source source = Base64Source.builder()
                        .mediaType(mediaType)
                        .data(base64Data)
                        .build();
                blocks.add(ImageBlock.builder().source(source).build());
                log.debug("NATIVE_PASS 图片构造 ImageBlock: fileId={}, mediaType={}, size={}KB",
                        att.getFileId(), mediaType, processed.length / 1024);
            } catch (Exception e) {
                log.warn("NATIVE_PASS 图片读取失败，跳过（降级不阻塞）: fileId={}, error={}",
                        att.getFileId(), e.getMessage());
            }
        }
        return blocks;
    }

    /** buildMessageWithAttachments 的返回值：文本消息 + 多模态图片内容块。 */
    record AdaptedContent(String text, List<ContentBlock> imageBlocks) {
    }

    /**
     * 解析用户显示名（realName 优先，回退 username）。
     *
     * <p>用于可观测追踪链路的用户标识展示，避免仅显示 User#ID。
     * 查询失败或用户不存在时返回 null，由前端回退为 User#ID 格式。
     *
     * @param userId 用户ID
     * @return 用户显示名，可能为 null
     */
    private String resolveUserName(Long userId) {
        if (userId == null) return null;
        try {
            User user = userMapper.selectById(userId);
            if (user == null) return null;
            if (user.getRealName() != null && !user.getRealName().isEmpty()) {
                return user.getRealName();
            }
            return user.getUsername();
        } catch (Exception e) {
            log.warn("resolveUserName failed for userId={}: {}", userId, e.getMessage());
            return null;
        }
    }
}
