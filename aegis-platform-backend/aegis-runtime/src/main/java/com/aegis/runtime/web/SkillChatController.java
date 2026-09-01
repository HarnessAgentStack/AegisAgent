package com.aegis.runtime.web;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.common.ReviewStatus;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.security.SkillContentScanner;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;
import com.aegis.core.common.tenant.TenantContextScope;
import org.springframework.web.bind.annotation.RequestHeader;
import com.aegis.dal.mapper.agent.AgentBindingMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillSubscriptionMapper;
import com.aegis.runtime.integration.skill.SkillCreatorTool;
import com.aegis.runtime.integration.skill.SkillDebuggerTool;
import com.aegis.runtime.integration.skill.SkillPackagerTool;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话内 @SKILL 选择器数据接口，按租户与可见性提供可引用的已发布技能列表。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/skill")
@RequiredArgsConstructor
public class SkillChatController {

    private final SkillMapper skillMapper;
    private final AgentDefMapper agentDefMapper;
    private final AgentBindingMapper agentBindingMapper;
    private final SkillSubscriptionMapper skillSubscriptionMapper;
    private final SkillCreatorTool skillCreatorTool;
    private final SkillDebuggerTool skillDebuggerTool;
    private final SkillPackagerTool skillPackagerTool;
    private final ResourceReviewMapper resourceReviewMapper;
    private final SkillContentScanner skillContentScanner;

    /**
     * 列出当前用户可引用的已发布技能。
     *
     * <p>根据智能体类型返回不同的技能列表：
     * <ul>
     *   <li>通用智能体：全局技能(GLOBAL) + 用户自己创建的 + 用户订阅的</li>
     *   <li>应用智能体：该智能体绑定的所有技能</li>
     * </ul>
     * </p>
     */
    @GetMapping("/available")
    public List<SkillOption> available(
            @TenantId Long gwTenantId,
            @UserId Long gwUserId,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String agentId,
            @RequestParam(required = false) String keyword) {
        Long effTenant = tenantId != null ? tenantId : gwTenantId;
        Long effUser = userId != null ? userId : gwUserId;

        // 确定智能体类型
        AgentType agentType = null;
        List<Long> boundSkillIds = new ArrayList<>();

        if (agentId != null && !agentId.isBlank()) {
            try {
                AgentDef agent = agentDefMapper.selectById(Long.parseLong(agentId));
                if (agent != null) {
                    agentType = agent.getAgentType();

                    // 如果是应用智能体，查询其绑定的技能
                    if (agentType == AgentType.APPLICATION) {
                        QueryWrapper<AgentBinding> bindingQw = new QueryWrapper<>();
                        bindingQw.eq("agent_id", agent.getId())
                                .eq("resource_type", ResourceType.SKILL)
                                .eq("enabled", 1);
                        List<AgentBinding> bindings = agentBindingMapper.selectList(bindingQw);
                        boundSkillIds = bindings.stream()
                                .map(AgentBinding::getResourceId)
                                .collect(Collectors.toList());
                    }
                }
            } catch (NumberFormatException e) {
                log.warn("无效的 agentId: {}", agentId);
            }
        }

        List<Skill> skills;

        if (agentType == AgentType.APPLICATION) {
            // 应用智能体：返回绑定的技能
            skills = getSkillsForApplicationAgent(boundSkillIds, keyword);
        } else {
            // 通用智能体（或无智能体）：全局技能 + 用户创建的 + 用户订阅的
            skills = getSkillsForUniversalAgent(effTenant, effUser, keyword);
        }

        log.debug("[SkillChat] available skills: agentId={}, agentType={}, size={}",
                agentId, agentType, skills.size());
        return skills.stream().map(SkillOption::from).collect(Collectors.toList());
    }

    /**
     * 通用智能体可见的技能：全局(GLOBAL) + 用户创建的 + 用户订阅的。
     */
    private List<Skill> getSkillsForUniversalAgent(Long tenantId, Long userId, String keyword) {
        List<Skill> result = new ArrayList<>();

        // 1. 全局技能（scope = GLOBAL，已发布，tenant_id=0 的平台系统技能）
        // 使用 SkillMapper.selectGlobalSkillsForTenant（@InterceptorIgnore 显式跳过租户插件），
        // 无需在 Web 层清空/恢复 TenantContextHolder，避免 ThreadLocal 篡改风险。
        result.addAll(skillMapper.selectGlobalSkillsForTenant(
                SkillScope.GLOBAL.name(), "PUBLISHED", keyword));

        // 2. 用户自己创建的技能（所有状态）
        if (userId != null) {
            QueryWrapper<Skill> ownQw = new QueryWrapper<>();
            ownQw.eq("author_user_id", userId);
            if (keyword != null && !keyword.isBlank()) {
                ownQw.and(w -> w.like("skill_name", keyword)
                        .or().like("skill_code", keyword)
                        .or().like("description", keyword));
            }
            // 排除已在全局列表中的
            List<Skill> ownSkills = skillMapper.selectList(ownQw);
            ownSkills.stream()
                    .filter(s -> result.stream().noneMatch(r -> r.getId().equals(s.getId())))
                    .forEach(result::add);
        }

        // 3. 用户订阅的技能
        if (userId != null) {
            QueryWrapper<SkillSubscription> subQw = new QueryWrapper<>();
            subQw.eq("subscriber_id", userId)
                    .eq("subscriber_type", com.aegis.core.enums.resource.SubscriberType.USER);
            List<SkillSubscription> subscriptions = skillSubscriptionMapper.selectList(subQw);
            if (!subscriptions.isEmpty()) {
                List<Long> subscribedIds = subscriptions.stream()
                        .map(SkillSubscription::getSkillId)
                        .collect(Collectors.toList());
                if (!subscribedIds.isEmpty()) {
                    QueryWrapper<Skill> subSkillQw = new QueryWrapper<>();
                    subSkillQw.in("id", subscribedIds)
                            .eq("life_status", "PUBLISHED");
                    if (keyword != null && !keyword.isBlank()) {
                        subSkillQw.and(w -> w.like("skill_name", keyword)
                                .or().like("skill_code", keyword)
                                .or().like("description", keyword));
                    }
                    skillMapper.selectList(subSkillQw).stream()
                            .filter(s -> result.stream().noneMatch(r -> r.getId().equals(s.getId())))
                            .forEach(result::add);
                }
            }
        }

        return result;
    }

    /**
     * 应用智能体可见的技能：该智能体绑定的所有技能。
     */
    private List<Skill> getSkillsForApplicationAgent(List<Long> boundSkillIds, String keyword) {
        if (boundSkillIds.isEmpty()) {
            return new ArrayList<>();
        }

        QueryWrapper<Skill> qw = new QueryWrapper<>();
        qw.in("id", boundSkillIds);
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("skill_name", keyword)
                    .or().like("skill_code", keyword)
                    .or().like("description", keyword));
        }
        return skillMapper.selectList(qw);
    }

    @PostMapping("/draft")
    public SkillCreatorTool.SkillDraftResult createDraft(
            @TenantId Long tenantId,
            @UserId Long userId,
            @RequestBody CreateDraftRequest req) {
        return skillCreatorTool.structureArtifacts(
                tenantId, userId,
                req.getSkillName(), req.getDescription(),
                req.getInstructions(), req.getInputs(),
                req.getOutputs(), req.getBindingTools());
    }

    @PostMapping("/{id}/debug")
    public SkillDebuggerTool.DebugResult debugSkill(
            @UserId Long userId,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> testInputs) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null) {
            return SkillDebuggerTool.DebugResult.fail("技能不存在: " + id);
        }
        if (!canAccessSkill(skill, userId)) {
            return SkillDebuggerTool.DebugResult.fail("无权调试该技能");
        }
        return skillDebuggerTool.runTest(id, testInputs);
    }

    @PostMapping("/{id}/diagnose")
    public SkillDebuggerTool.DebugResult diagnoseSkill(
            @UserId Long userId,
            @PathVariable Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null) {
            return SkillDebuggerTool.DebugResult.fail("技能不存在: " + id);
        }
        if (!canAccessSkill(skill, userId)) {
            return SkillDebuggerTool.DebugResult.fail("无权诊断该技能");
        }
        return skillDebuggerTool.diagnose(id);
    }

    @PostMapping("/{id}/package")
    public SkillPackagerTool.PackageResult packageSkill(
            @TenantId Long tenantId,
            @UserId Long userId,
            @PathVariable Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null) {
            return SkillPackagerTool.PackageResult.fail("技能不存在: " + id);
        }
        if (!canAccessSkill(skill, userId)) {
            return SkillPackagerTool.PackageResult.fail("无权打包该技能");
        }
        return skillPackagerTool.packageAndUpload(id, tenantId);
    }

    /**
     * 提交技能审核。
     *
     * <p>创建 ResourceReview 审核记录并更新技能状态为 REVIEWING；
     * 提交前执行 {@link SkillContentScanner} 安全扫描，HIGH 级风险直接阻断提交，
     * 扫描结果写入审核单 scanResult 字段供审核员查看。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param id       技能ID
     * @return 提交结果
     */
    @PostMapping("/{id}/submit-review")
    public Map<String, Object> submitForReview(
            @TenantId Long tenantId,
            @UserId Long userId,
            @PathVariable Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null) {
            return Map.of("success", false, "message", "技能不存在: " + id);
        }
        if (!canAccessSkill(skill, userId)) {
            return Map.of("success", false, "message", "无权提交该技能审核");
        }
        // 仅作者可提交审核
        if (skill.getAuthorUserId() != null && !skill.getAuthorUserId().equals(userId)) {
            return Map.of("success", false, "message", "无权提交他人创建的技能");
        }

        // 状态校验：仅 DRAFT/REJECTED 可提交审核
        if (skill.getLifeStatus() != AgentLifeStatus.DRAFT
                && skill.getLifeStatus() != AgentLifeStatus.REJECTED) {
            if (skill.getLifeStatus() == AgentLifeStatus.REVIEWING) {
                return Map.of("success", true, "message", "技能已在审核中", "submitted", true);
            }
            return Map.of("success", false, "message", "当前状态不可提交审核: " + skill.getLifeStatus());
        }

        // 安全扫描（不静默，HIGH 风险阻断提交）
        SkillContentScanner.ScanResult scanResult = skillContentScanner.scan(skill);
        if (!scanResult.isPassed()) {
            log.warn("技能提交审核被安全扫描阻断: skillId={}, summary={}", id, scanResult.getSummary());
            return Map.of("success", false,
                    "message", "安全扫描未通过（P0 风险阻断）: " + scanResult.getSummary(),
                    "scanResult", scanResult);
        }

        // 幂等检查：同一资源已有 PENDING 审核时直接返回
        // U8: 补 tenant_id 条件（res_review 在租户忽略表中，插件不自动追加租户过滤）
        ResourceReview existingReview = resourceReviewMapper.selectOne(
                new QueryWrapper<ResourceReview>()
                        .eq("tenant_id", tenantId)
                        .eq("resource_type", ResourceType.SKILL.name())
                        .eq("resource_id", id)
                        .eq("review_status", ReviewStatus.PENDING.name())
                        .orderByDesc("id")
                        .last("LIMIT 1"));

        if (existingReview != null) {
            if (skill.getLifeStatus() != AgentLifeStatus.REVIEWING) {
                skill.setLifeStatus(AgentLifeStatus.REVIEWING);
                skillMapper.updateById(skill);
            }
            return Map.of("success", true, "message", "提交审核成功（已有待审核单）",
                    "submitted", true, "reviewId", existingReview.getId());
        }

        // 创建审核记录（附扫描结果，供审核员查看）
        ResourceReview review = ResourceReview.builder()
                .resourceType(ResourceType.SKILL)
                .resourceId(id)
                .resourceName(skill.getSkillName())
                .resourceVersion(skill.getVersion())
                .applicantUserId(userId)
                .securityLevel(skill.getSecurityLevel() != null ? skill.getSecurityLevel().getLevel() : null)
                .reviewStatus(ReviewStatus.PENDING)
                .submitTime(java.time.LocalDateTime.now())
                .build();
        review.setTenantId(tenantId);
        review.setScanResult(toScanJson(scanResult));
        resourceReviewMapper.insert(review);

        // 更新技能状态
        skill.setLifeStatus(AgentLifeStatus.REVIEWING);
        skillMapper.updateById(skill);

        log.info("技能提交审核: skillId={}, skillCode={}, reviewId={}, userId={}, scanPassed={}",
                id, skill.getSkillCode(), review.getId(), userId, scanResult.isPassed());

        return Map.of("success", true, "message", "提交审核成功",
                "submitted", true, "reviewId", review.getId());
    }

    /**
     * 触发安全扫描（供 skill_creator 对话流程使用）。
     *
     * <p>本地执行 {@link SkillContentScanner} 扫描，扫描异常不再静默返回"通过"。
     */
    @PostMapping("/{id}/scan")
    public Map<String, Object> scanSkill(
            @UserId Long userId,
            @PathVariable Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null) {
            return Map.of("success", false, "message", "技能不存在: " + id);
        }
        if (!canAccessSkill(skill, userId)) {
            return Map.of("success", false, "message", "无权扫描该技能");
        }

        try {
            SkillContentScanner.ScanResult scanResult = skillContentScanner.scan(skill);
            return Map.of(
                    "success", true,
                    "message", "安全扫描完成",
                    "scanResult", scanResult
            );
        } catch (Exception e) {
            // 扫描异常不静默吞掉，明确返回失败
            log.error("安全扫描执行异常: skillId={}", id, e);
            return Map.of(
                    "success", false,
                    "message", "安全扫描执行异常: " + e.getMessage()
            );
        }
    }

    /** 扫描结果序列化为 JSON（失败时返回 null，不阻断主流程） */
    private String toScanJson(SkillContentScanner.ScanResult scanResult) {
        try {
            return com.alibaba.fastjson2.JSON.toJSONString(scanResult);
        } catch (Exception e) {
            log.warn("扫描结果序列化失败，审核单将不含 scanResult: {}", e.getMessage());
            return null;
        }
    }

    @PutMapping("/{id}/metadata")
    public SkillCreatorTool.SkillDraftResult updateMetadata(
            @PathVariable Long id,
            @UserId Long userId,
            @RequestBody UpdateMetadataRequest req) {
        return skillCreatorTool.updateMetadata(id, userId,
                req.getDescription(), req.getInstructions(),
                req.getInputs(), req.getOutputs());
    }

    @GetMapping("/{id}/metadata")
    public SkillMetadataResponse getMetadata(@PathVariable Long id, @UserId Long userId,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        // 边界式租户作用域（P1-1）：WebFlux 阻塞式 controller 运行在 boundedElastic 线程，
        // 网关过滤器的绑定不跨线程传递，需在执行线程上显式绑定；线程归池前必须清空。
        try (var ignore = TenantContextScope.bound(tenantId)) {
            Skill skill = skillMapper.selectById(id);
            if (skill == null) return null;
            // 权限校验：GLOBAL 技能所有人可见，LOCAL 技能仅作者可见
            if (!canAccessSkill(skill, userId)) {
                throw new com.aegis.core.common.error.BusinessException(
                        com.aegis.core.common.web.ResultCode.FORBIDDEN, "无权查看该技能");
            }
            SkillMetadataResponse resp = new SkillMetadataResponse();
            resp.setId(skill.getId());
            resp.setSkillCode(skill.getSkillCode());
            resp.setSkillName(skill.getSkillName());
            resp.setDescription(skill.getDescription());
            resp.setInstructions(skill.getInstructions());
            resp.setInputs(skill.getInputs());
            resp.setOutputs(skill.getOutputs());
            resp.setBindingTools(skill.getBindingTools());
            resp.setScope(skill.getScope() != null ? skill.getScope().name() : "LOCAL");
            resp.setVersion(skill.getVersion());
            resp.setLifeStatus(skill.getLifeStatus() != null ? skill.getLifeStatus().name() : null);
            resp.setIsSystem(Boolean.TRUE.equals(skill.getIsSystem()));
            return resp;
        }
    }

    // U6: 删除 getVersions 空实现端点——版本历史由 admin SkillVersionService
    // （GET /api/admin/resource/skill/{id}/versions）提供完整实现，runtime 无需重复建设

    @GetMapping("/{id}/package/download")
    public ResponseEntity<byte[]> downloadPackage(
            @TenantId Long tenantId,
            @UserId Long userId,
            @PathVariable Long id) {
        Skill skill = skillMapper.selectById(id);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccessSkill(skill, userId)) {
            return ResponseEntity.status(403).build();
        }
        SkillPackagerTool.PackageResult result = skillPackagerTool.downloadFromStorage(id, tenantId);
        if (!result.isSuccess()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + result.getFileName() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(result.getData());
    }

    @GetMapping("/{id}/skillmd")
    public Map<String, String> getSkillMd(@PathVariable Long id,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        // 边界式租户作用域（P1-1）：同 getMetadata，boundedElastic 线程归池前必须清空
        try (var ignore = TenantContextScope.bound(tenantId)) {
            String content = skillPackagerTool.generateSkillMd(id);
            return Map.of("content", content);
        }
    }

    /**
     * 检查用户是否有权访问该技能（四场景）。
     *
     * <p>P1 修复（U1）：原实现只认 scope=GLOBAL 和作者本人，导致——
     * <ul>
     *   <li>已订阅 PUBLISHED 技能的用户被误拒（"无权调试/打包该技能"）</li>
     *   <li>GLOBAL 但未发布（DRAFT/REVIEWING）的技能对所有人开放（越权）</li>
     * </ul>
     *
     * <p>判定顺序（短路返回）：
     * <ol>
     *   <li>作者本人：全状态可见（DRAFT/REVIEWING/REJECTED/PUBLISHED）</li>
     *   <li>GLOBAL 技能：仅 PUBLISHED 开放（系统技能如 skill_creator）</li>
     *   <li>订阅用户：USER 订阅记录存在 + 技能 PUBLISHED</li>
     *   <li>智能体绑定：用户名下智能体（authorUserId=用户）通过 AgentBinding 绑定的 PUBLISHED 技能</li>
     * </ol>
     *
     * <p>租户隔离：res_skill_subscription / agent_binding / agent_def 均带 tenant_id，
     * MyBatis-Plus 租户插件自动过滤，天然防跨租户越权。
     *
     * @param skill  目标技能（非空）
     * @param userId 当前用户（可能为 null：匿名场景仅放行已发布 GLOBAL）
     * @return true=有权访问
     */
    private boolean canAccessSkill(Skill skill, Long userId) {
        // 匿名/无用户上下文：仅已发布的 GLOBAL 系统技能可见
        if (skill == null) {
            return false;
        }
        if (userId == null) {
            return skill.getScope() == SkillScope.GLOBAL
                    && skill.getLifeStatus() == AgentLifeStatus.PUBLISHED;
        }
        // 场景 1：作者本人（全状态，最高优先级）
        if (userId.equals(skill.getAuthorUserId())) {
            return true;
        }
        // 场景 2：GLOBAL 技能 —— 仅 PUBLISHED 开放（修复：原实现 DRAFT 的 GLOBAL 也放行）
        if (skill.getScope() == SkillScope.GLOBAL) {
            return skill.getLifeStatus() == AgentLifeStatus.PUBLISHED;
        }
        // 场景 3/4 仅对已发布技能开放（未发布的 LOCAL 技能只属于作者）
        if (skill.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            return false;
        }
        // 场景 3：用户订阅（res_skill_subscription: subscriber_type=USER）
        Long userSubCount = skillSubscriptionMapper.selectCount(
                new QueryWrapper<SkillSubscription>()
                        .eq("skill_id", skill.getId())
                        .eq("subscriber_type", com.aegis.core.enums.resource.SubscriberType.USER)
                        .eq("subscriber_id", userId));
        if (userSubCount != null && userSubCount > 0) {
            return true;
        }
        // 场景 4：用户名下智能体绑定的技能（agent_binding: RESOURCE=SKILL 且 agent.author_user_id=userId）
        QueryWrapper<AgentBinding> bindQw = new QueryWrapper<>();
        bindQw.eq("resource_id", skill.getId())
                .eq("resource_type", ResourceType.SKILL)
                .eq("enabled", 1);
        List<AgentBinding> bindings = agentBindingMapper.selectList(bindQw);
        if (!bindings.isEmpty()) {
            List<Long> agentIds = bindings.stream()
                    .map(AgentBinding::getAgentId)
                    .distinct()
                    .collect(Collectors.toList());
            Long ownedAgentCount = agentDefMapper.selectCount(
                    new QueryWrapper<AgentDef>()
                            .in("id", agentIds)
                            .eq("author_user_id", userId));
            if (ownedAgentCount != null && ownedAgentCount > 0) {
                return true;
            }
        }
        return false;
    }

    /** @SKILL 选择器选项 */
    @Data
    public static class SkillOption {
        /** 技能编码（@ 引用键） */
        private String skillCode;
        /** 展示名称 */
        private String skillName;
        /** 描述 */
        private String description;
        /** 分类 */
        private String category;

        public static SkillOption from(Skill s) {
            SkillOption o = new SkillOption();
            o.skillCode = s.getSkillCode();
            o.skillName = s.getSkillName();
            o.description = s.getDescription();
            o.category = s.getCategory() != null ? s.getCategory().name() : null;
            return o;
        }
    }

    @Data
    public static class CreateDraftRequest {
        private String skillName;
        private String description;
        private String instructions;
        private String inputs;
        private String outputs;
        private String bindingTools;
    }

    @Data
    public static class UpdateMetadataRequest {
        private String description;
        private String instructions;
        private String inputs;
        private String outputs;
    }

    @Data
    public static class SkillMetadataResponse {
        private Long id;
        private String skillCode;
        private String skillName;
        private String description;
        private String instructions;
        private String inputs;
        private String outputs;
        private String bindingTools;
        private String scope;
        private String version;
        private String lifeStatus;
        private Boolean isSystem;
    }
}
