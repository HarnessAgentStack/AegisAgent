package com.aegis.runtime.integration.skill;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.dto.chat.SkillRef;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.intent.IntentType;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillSubscriptionMapper;
import com.aegis.runtime.integration.middleware.AegisIntentMiddleware;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.aegis.runtime.service.intent.IntentRecognitionService;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.skill.RuntimeContextSkillRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aegis 技能仓库：复用 AgentScope 2.0.2 原生 {@link RuntimeContextSkillRepository} SPI。
 *
 * <p><b>职责</b>：将 {@code res_skill} 表中已发布（PUBLISHED）且对当前请求可见的技能，
 * 转换为框架的 {@link AgentSkill}（{@code skillContent = instructions}，
 * {@code resources = references_manifest}）。框架 {@code HarnessSkillMiddleware} 在装配期
 * 自动调用 {@link #getAllSkills(RuntimeContext)}，把可见技能注入系统提示词的
 * {@code <available_skills>} 段落——<b>这正是 V1 分析中“运行时技能链路断裂”的根因修复点</b>：
 * 此前 {@code skillRefs} 无消费方、{@code SkillExecutor} 是死代码，技能从未进入模型上下文。
 *
 * <p><b>@SKILL 显式引用</b>：装配期把 {@link SkillRef} 展平为 code 列表写入
 * {@code RuntimeContext} 的 {@code aegis.requestedSkills} 属性；本仓库读取该属性，
 * 对可见的请求技能强制包含，对不可见/不存在的请求技能记入 {@link SkillResolution#rejectedCodes}。
 *
 * <p><b>A4 技能订阅分轨</b>（修 P1-3 / R-G1，与 A6 MCP 分轨对齐）：按 RuntimeContext 中的
 * {@code agentType} 选择装载轨道——
 * <ul>
 *   <li><b>UNIVERSAL</b>：GLOBAL 平台内置 + <b>用户订阅</b>（{@code res_skill_subscription}，
 *       仅 PUBLISHED）+ <b>用户自建</b>（author=userId，<b>含 DRAFT</b>，修 P2-1）</li>
 *   <li><b>APPLICATION / SYSTEM</b>：GLOBAL 平台内置 + {@code agent_binding} 绑定技能
 *       （审核通过 + PUBLISHED），<b>不加载用户订阅/自建</b>（防越权，修 R-G3 技能面）</li>
 * </ul>
 * 会话级引用兜底：UNIVERSAL 智能体上用户 {@code @} 引用未订阅但可见的已发布技能时，
 * 允许一次性注入（会话级临时语义）；APPLICATION/SYSTEM 无此兜底，仅限装载集。
 *
 * <p><b>可见性规则</b>（列类型为 VARCHAR，应用枚举驱动）：
 * <ul>
 *   <li>TENANT / PUBLIC：当前租户任意用户可见</li>
 *   <li>其他/未知值：不可见（防御性兜底）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class AegisSkillRepository implements RuntimeContextSkillRepository {

    /** RuntimeContext 属性键：本次请求显式引用的技能 code 列表（由装配期写入） */
    public static final String CTX_REQUESTED_SKILLS = "aegis.requestedSkills";

    /** RuntimeContext 属性键：当前智能体类型（A4 分轨装载，由 buildRuntimeContext 写入） */
    public static final String CTX_AGENT_TYPE = "agentType";

    /** RuntimeContext 属性键：当前智能体 ID（A4 分轨装载，由 buildRuntimeContext 写入） */
    public static final String CTX_AGENT_ID = "agentId";

    private final SkillMapper skillMapper;
    private final SkillSubscriptionMapper skillSubscriptionMapper;
    private final ResourceQueryService resourceQueryService;

    /** 写权限标记：Aegis 技能仓库为只读（技能落盘由 SkillManageService 负责） */
    private boolean writeable = false;

    public AegisSkillRepository(SkillMapper skillMapper,
                                SkillSubscriptionMapper skillSubscriptionMapper,
                                ResourceQueryService resourceQueryService) {
        this.skillMapper = skillMapper;
        this.skillSubscriptionMapper = skillSubscriptionMapper;
        this.resourceQueryService = resourceQueryService;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    /**
     * 无上下文读方法（框架部分路径使用）：安全降级，避免跨租户泄漏技能名/正文。
     * 真实可见性解析始终走 {@link #getAllSkills(RuntimeContext)}（带租户上下文）。
     */
    @Override
    public AgentSkill getSkill(String name) {
        return null;
    }

    @Override
    public List<String> getAllSkillNames() {
        return List.of();
    }

    @Override
    public boolean skillExists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        LambdaQueryWrapper<Skill> w = new LambdaQueryWrapper<>();
        w.eq(Skill::getSkillCode, skillName)
                .eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .eq(Skill::getDeleted, 0);
        return skillMapper.selectCount(w) > 0;
    }

    /**
     * 只读仓库：写操作不被允许。框架在 {@link #isWriteable()} 返回 false 时不会调用本方法；
     * 兜底直接返回 false，避免任何意外写库。
     */
    @Override
    public boolean save(List<AgentSkill> skills, boolean force) {
        log.warn("AegisSkillRepository 为只读仓库，拒绝 save 调用（skills={}）", skills == null ? 0 : skills.size());
        return false;
    }

    @Override
    public boolean delete(String skillName) {
        log.warn("AegisSkillRepository 为只读仓库，拒绝 delete 调用（skillName={}）", skillName);
        return false;
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("mysql", "aegis.res_skill", this.writeable);
    }

    @Override
    public String getSource() {
        return "aegis-db";
    }

    /**
     * 无上下文调用（框架部分路径使用），等价于对所有租户可见技能不做租户过滤的兜底。
     * 实践中总由 {@link #getAllSkills(RuntimeContext)} 提供租户上下文，此处返回空以保证安全。
     */
    @Override
    public List<AgentSkill> getAllSkills() {
        return List.of();
    }

    /**
     * 核心方法：根据请求上下文解析本次应注入的技能集合与被驳回的显式引用。
     */
    @Override
    public List<AgentSkill> getAllSkills(RuntimeContext ctx) {
        return resolve(ctx).skills();
    }

    /**
     * 解析技能集合与被驳回引用。供 {@link #getAllSkills(RuntimeContext)} 与
     * {@code TaskExecutionService}（用于发出 {@code skill.rejected} 事件）共用，单一数据源。
     *
     * <h3>A4 分轨装载</h3>
     * <pre>
     * GLOBAL 平台内置（所有类型）
     *   + UNIVERSAL            → 用户订阅(PUBLISHED) + 自建(含 DRAFT)
     *   + APPLICATION/SYSTEM   → agent_binding 绑定(PUBLISHED)
     * </pre>
     */
    public SkillResolution resolve(RuntimeContext ctx) {
        // 任务 10 验收 #2：CHITCHAT/RAG_QUERY 下 skills 为空（闲聊/纯检索不需要技能指令注入）
        IntentType intent = readIntentType(ctx);
        if (intent == IntentType.CHITCHAT || intent == IntentType.RAG_QUERY) {
            List<String> requested = extractRequestedCodes(ctx);
            log.info("技能按意图过滤：intent={}，技能集清空，显式引用 {} 个全部驳回", intent,
                    requested == null ? 0 : requested.size());
            return new SkillResolution(List.of(), requested == null ? List.of() : requested);
        }

        Long tenantId = parseLong(ctx == null ? null : ctx.get("tenantId"));
        Long userId = parseLong(ctx == null ? null : ctx.getUserId());
        Long deptId = parseLong(ctx == null ? null : ctx.get("deptId"));
        Long agentId = parseLong(ctx == null ? null : ctx.get(CTX_AGENT_ID));
        String agentType = agentTypeOf(ctx);
        boolean applicationLike = isApplicationLike(agentType);
        List<String> requested = extractRequestedCodes(ctx);

        // 1. GLOBAL 平台内置技能：三类智能体均装载
        List<Skill> globalSkills = queryByScope(SkillScope.GLOBAL);

        // 2. A4 分轨：按智能体类型装载租户内技能
        List<Skill> trackSkills = applicationLike
                ? queryBoundSkills(ctx, agentId)
                : queryUserSkills(tenantId, userId, deptId);

        // 3. 合并去重（skillCode 维度，GLOBAL 优先）
        Map<String, Skill> byCode = new LinkedHashMap<>();
        for (Skill s : globalSkills) {
            byCode.putIfAbsent(s.getSkillCode(), s);
        }
        for (Skill s : trackSkills) {
            byCode.putIfAbsent(s.getSkillCode(), s);
        }

        // 4. @SKILL 会话级引用：不在装载集中的请求码做兜底判定
        List<String> rejected = new ArrayList<>();
        if (requested != null && !requested.isEmpty()) {
            for (String code : requested) {
                if (byCode.containsKey(code)) {
                    continue;
                }
                Skill extra = null;
                // 会话级临时引用兜底仅 UNIVERSAL 开放：
                // 未订阅但可见的已发布技能允许一次性注入；APPLICATION/SYSTEM 严格限装载集（防越权）
                if (!applicationLike) {
                    Skill candidate = findPublishedByCode(tenantId, code);
                    if (candidate != null && isVisible(candidate, tenantId, userId, deptId)) {
                        extra = candidate;
                    }
                }
                if (extra != null) {
                    byCode.put(code, extra);
                } else {
                    rejected.add(code);
                }
            }
        }

        List<AgentSkill> skills = new ArrayList<>(byCode.size());
        for (Skill s : byCode.values()) {
            skills.add(toAgentSkill(s));
        }

        if (log.isDebugEnabled()) {
            log.debug("AegisSkillRepository.resolve: tenantId={}, agentType={}, agentId={}, "
                            + "global={}, track={}, requested={}, rejected={}",
                    tenantId, agentType, agentId, globalSkills.size(), trackSkills.size(),
                    requested, rejected);
        }
        return new SkillResolution(skills, rejected);
    }

    /** 解析结果载体 */
    public record SkillResolution(List<AgentSkill> skills, List<String> rejectedCodes) {}

    // ---------------------------------------------------------------------
    //  内部工具
    // ---------------------------------------------------------------------

    /**
     * A4：UNIVERSAL 轨道 —— 用户订阅技能（PUBLISHED）+ 自建技能（含 DRAFT）。
     *
     * <p>订阅技能语义与 MCP 订阅对齐：仅 {@code res_skill_subscription} 中 USER 订阅、
     * 技能 PUBLISHED 且可见的记录生效。自建技能按 author=userId 查询，
     * DRAFT/REVIEWING/PUBLISHED 均装载（草稿仅创建者可查到，天然满足可见性），ARCHIVED 排除。
     */
    private List<Skill> queryUserSkills(Long tenantId, Long userId, Long deptId) {
        if (tenantId == null || userId == null) {
            return List.of();
        }
        List<Skill> result = new ArrayList<>();

        // 1. 用户订阅技能（PUBLISHED + 可见性过滤）
        List<Long> subscribedIds = skillSubscriptionMapper.selectList(
                        new LambdaQueryWrapper<SkillSubscription>()
                                .eq(SkillSubscription::getTenantId, tenantId)
                                .eq(SkillSubscription::getSubscriberType, SubscriberType.USER)
                                .eq(SkillSubscription::getSubscriberId, userId))
                .stream()
                .map(SkillSubscription::getSkillId)
                .toList();
        if (!subscribedIds.isEmpty()) {
            for (Skill s : skillMapper.selectBatchIds(subscribedIds)) {
                if (s == null || s.getTenantId() == null || !tenantId.equals(s.getTenantId())) {
                    continue;
                }
                if (s.getLifeStatus() == AgentLifeStatus.PUBLISHED
                        && isVisible(s, tenantId, userId, deptId)) {
                    result.add(s);
                }
            }
        }

        // 2. 自建技能（含 DRAFT；作者天然可见，不做可见性过滤）
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

    /**
     * A4：APPLICATION/SYSTEM 轨道 —— 仅 {@code agent_binding} 审核通过的绑定技能（PUBLISHED）。
     *
     * <p>不加载用户订阅/自建技能，防止应用/系统智能体上下文被注入未审核资源（修 R-G3 技能面）。
     *
     * <p>T4 双载收敛：优先复用装配期 {@code AssemblyResourceContext.boundSkills} 预载实体
     * （与 ToolBridge 的 SkillAsToolAdapter 注册共享同一批实体，消除重复 selectById）；
     * 上下文缺失时降级为 DB 逐条直查。PUBLISHED 过滤在两条路径上统一执行。
     */
    private List<Skill> queryBoundSkills(RuntimeContext ctx, Long agentId) {
        if (agentId == null) {
            return List.of();
        }
        List<Skill> result = new ArrayList<>();
        List<Skill> preloaded = com.aegis.runtime.service.agent.AssemblyResourceContext.boundSkillsOf(ctx);
        if (preloaded != null) {
            for (Skill s : preloaded) {
                if (s == null) {
                    continue;
                }
                if (s.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
                    log.info("绑定技能未发布，跳过: agentId={}, skillCode={}, lifeStatus={}",
                            agentId, s.getSkillCode(), s.getLifeStatus());
                    continue;
                }
                result.add(s);
            }
            log.debug("queryBoundSkills 复用装配期预载技能: agentId={}, count={}", agentId, result.size());
            return result;
        }

        // 降级路径：RuntimeContext 无装配期缓存，DB 直查
        for (AgentBinding binding : resourceQueryService.listEnabledBindings(agentId)) {
            if (binding.getResourceType() != ResourceType.SKILL || binding.getResourceId() == null) {
                continue;
            }
            Skill skill = resourceQueryService.findSkillById(binding.getResourceId());
            if (skill == null) {
                log.warn("绑定技能不存在，跳过: agentId={}, resourceId={}", agentId, binding.getResourceId());
                continue;
            }
            if (skill.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
                log.info("绑定技能未发布，跳过: agentId={}, skillCode={}, lifeStatus={}",
                        agentId, skill.getSkillCode(), skill.getLifeStatus());
                continue;
            }
            result.add(skill);
        }
        return result;
    }

    /** A4：按 code 查询租户内已发布技能（@SKILL 会话级引用兜底用） */
    private Skill findPublishedByCode(Long tenantId, String code) {
        if (tenantId == null || code == null || code.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<Skill> w = new LambdaQueryWrapper<>();
        w.eq(Skill::getTenantId, tenantId)
                .eq(Skill::getSkillCode, code)
                .eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .eq(Skill::getDeleted, 0)
                .last("LIMIT 1");
        return skillMapper.selectOne(w);
    }

    /** A4：读取 RuntimeContext 中的智能体类型（缺失时按 UNIVERSAL 处理） */
    private String agentTypeOf(RuntimeContext ctx) {
        Object o = ctx == null ? null : ctx.get(CTX_AGENT_TYPE);
        return o != null ? o.toString() : null;
    }

    /** A4：是否为应用/系统类智能体（绑定轨道） */
    private boolean isApplicationLike(String agentType) {
        return "APPLICATION".equals(agentType) || "SYSTEM".equals(agentType);
    }

    /**
     * 任务 10：从 RuntimeContext 读取意图类型（用于技能过滤）。
     *
     * <p>兼容 {@link IntentRecognitionService.IntentResult} record 与裸 {@link IntentType} 两种存储。
     * 意图由 {@link AegisIntentMiddleware}（order=67）在 onAgent 中写入，
     * 本仓库在装配期被框架调用时意图已就绪。
     */
    private IntentType readIntentType(RuntimeContext ctx) {
        if (ctx == null) return null;
        try {
            Object raw = ctx.get(AegisIntentMiddleware.CTX_KEY_INTENT);
            if (raw instanceof IntentRecognitionService.IntentResult ir) {
                return ir.intent();
            }
            if (raw instanceof IntentType it) {
                return it;
            }
        } catch (Exception ignored) { /* no-op */ }
        return null;
    }

    private List<Skill> queryByScope(SkillScope scope) {
        if (scope == SkillScope.GLOBAL) {
            // GLOBAL 平台内置技能（tenant_id=0）：selectGlobalSkillsForTenant 已通过
            // @InterceptorIgnore 显式跳过租户插件，无需清空/恢复租户上下文——
            // 旧的 clear() 模式在 fail-closed 租户插件下会抛"租户上下文缺失"异常
            return skillMapper.selectGlobalSkillsForTenant(
                    scope.name(), AgentLifeStatus.PUBLISHED.name(), null);
        }
        LambdaQueryWrapper<Skill> w = new LambdaQueryWrapper<>();
        w.eq(Skill::getScope, scope)
                .eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .eq(Skill::getDeleted, 0);
        return skillMapper.selectList(w);
    }

    private boolean isVisible(Skill s, Long tenantId, Long userId, Long deptId) {
        if (s.getScope() == SkillScope.GLOBAL) {
            return true;
        }
        Visibility v = s.getVisibility();
        if (v == null) {
            v = Visibility.TENANT;
        }
        return switch (v) {
            case TENANT, PUBLIC -> true;
            default -> false;
        };
    }

    private AgentSkill toAgentSkill(Skill s) {
        // 解析 references_manifest -> resources
        Map<String, String> resources = new HashMap<>();
        if (s.getReferencesManifest() != null && !s.getReferencesManifest().isBlank()) {
            try {
                JSONObject obj = JSONObject.parseObject(s.getReferencesManifest());
                if (obj != null) {
                    for (String key : obj.keySet()) {
                        resources.put(key, obj.getString(key));
                    }
                }
            } catch (Exception e) {
                log.warn("解析 references_manifest 失败 skillCode={}: {}", s.getSkillCode(), e.getMessage());
            }
        }
        // skillContent 必须非空，否则框架抛异常；用 instructions 优先，描述兜底
        String content = (s.getInstructions() != null && !s.getInstructions().isBlank())
                ? s.getInstructions()
                : (s.getDescription() != null ? s.getDescription() : "（该技能暂无方法论正文）");
        String desc = s.getDescription() != null ? s.getDescription() : s.getSkillName();

        return AgentSkill.builder()
                .name(s.getSkillCode())
                .description(desc)
                .skillContent(content)
                .resources(resources)
                .source("aegis-db")
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRequestedCodes(RuntimeContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        Object o = ctx.get(CTX_REQUESTED_SKILLS);
        if (o instanceof List<?> list) {
            List<String> codes = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String str && !str.isBlank()) {
                    codes.add(str);
                } else if (item instanceof SkillRef ref && ref.getSkillCode() != null) {
                    codes.add(ref.getSkillCode());
                }
            }
            return codes;
        }
        return List.of();
    }

    private Long parseLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.valueOf(o.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
