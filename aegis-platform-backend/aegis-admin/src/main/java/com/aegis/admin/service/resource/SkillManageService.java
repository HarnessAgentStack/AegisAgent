package com.aegis.admin.service.resource;

import com.aegis.admin.service.resource.ReviewProcessEngine;
import com.aegis.dal.security.SkillSecurityScanner;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillSubscriptionMapper;
import com.aegis.dal.mapper.resource.ToolMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.domain.resource.Tool;
import com.aegis.core.dto.resource.SkillCreateRequest;
import com.aegis.core.dto.resource.SkillUpdateRequest;
import com.aegis.core.dto.resource.SkillVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.util.XssSanitizer;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 技能管理领域服务。
 *
 * <p>编排技能的创建、更新、查询、提交审核与删除能力。
 * 技能是智能体执行特定任务的能力封装单元，承载工具编排与输入输出映射，
 * 通过审核流程发布至资源中心供其他智能体订阅复用。
 *
 * @author wang.zhen
 * @see Skill
 * @see ReviewProcessEngine
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillManageService {

    private final SkillMapper skillMapper;
    private final SkillSubscriptionMapper skillSubscriptionMapper;
    private final ToolMapper toolMapper;
    private final ReviewProcessEngine reviewProcessEngine;
    private final SkillSecurityScanner securityScanner;
    // 统一创建路径
    private final SkillLifecycleService skillLifecycleService;

    /**
     * 创建技能（草稿态）。
     *
     * @param tenantId 租户ID
     * @param userId   创建用户ID
     * @param req      技能创建请求
     * @return 技能ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Long tenantId, Long userId, SkillCreateRequest req) {
        if (tenantId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        if (req.getSkillCode() == null || req.getSkillCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "技能编码不能为空");
        }
        if (req.getSkillName() == null || req.getSkillName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "技能名称不能为空");
        }

        // 统一创建逻辑（校验 + XSS + 编码唯一 + 默认值 + 版本号）
        Skill skill = skillLifecycleService.createDraft(
                tenantId, userId,
                req.getSkillCode(), req.getSkillName(),
                req.getSkillType(), req.getCategory(),
                req.getSecurityLevel());

        // ========== admin 特有扩展字段（runtime 对话创建不涉及） ==========
        // 补充 XSS 清洗后的描述（createDraft 已清洗名称，此处补 description/tags/icon）
        if (req.getDescription() != null && !req.getDescription().isEmpty()) {
            skill.setDescription(XssSanitizer.sanitize(req.getDescription(), 1000));
        }
        if (req.getTags() != null && !req.getTags().isEmpty()) {
            skill.setTags(req.getTags());
        }
        if (req.getIcon() != null && !req.getIcon().isEmpty()) {
            skill.setIcon(XssSanitizer.sanitize(req.getIcon(), 500));
        }
        // V2 扩展字段
        if (req.getInstructions() != null && !req.getInstructions().isEmpty()) {
            skill.setInstructions(req.getInstructions());
        }
        if (req.getReferencesManifest() != null && !req.getReferencesManifest().isEmpty()) {
            skill.setReferencesManifest(req.getReferencesManifest());
        }
        if (req.getTriggerExamples() != null && !req.getTriggerExamples().isEmpty()) {
            skill.setTriggerExamples(req.getTriggerExamples());
        }
        // inputs / outputs / mappingConfig 若请求中带了则覆盖默认值
        if (req.getInputs() != null && !req.getInputs().isEmpty()) {
            skill.setInputs(req.getInputs());
        }
        if (req.getOutputs() != null && !req.getOutputs().isEmpty()) {
            skill.setOutputs(req.getOutputs());
        }
        if (req.getMappingConfig() != null && !req.getMappingConfig().isEmpty()) {
            skill.setMappingConfig(req.getMappingConfig());
        }
        if (req.getBindingTools() != null && !req.getBindingTools().isEmpty()) {
            skill.setBindingTools(req.getBindingTools());
        }

        skillMapper.updateById(skill);
        log.info("Skill created via admin: id={}, code={}, tenantId={}, authorUserId={}",
                skill.getId(), skill.getSkillCode(), tenantId, userId);
        return skill.getId();
    }

    /**
     * 更新技能（仅 DRAFT/REJECTED 状态可更新）。
     *
     * @param tenantId 租户ID
     * @param userId   操作人用户ID
     * @param skillId  技能ID
     * @param req      技能更新请求
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long tenantId, Long userId, Long skillId, SkillUpdateRequest req) {
        Skill existing = requireSkill(skillId, tenantId, userId);
        // REVIEWING 状态：作者本人修改放行（创作者在审核期间仍可完善草稿）。
        // PUBLISHED/ARCHIVED 仍严格阻断。
        boolean isAuthor = existing.getAuthorUserId() != null && existing.getAuthorUserId().equals(userId);
        AgentLifeStatus status = existing.getLifeStatus();
        if (status == AgentLifeStatus.PUBLISHED || status == AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "技能当前状态不可修改: " + status);
        }
        if (status == AgentLifeStatus.REVIEWING && !isAuthor) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "技能审核中，仅作者本人可修改: " + status);
        }

        LambdaUpdateWrapper<Skill> wrapper = new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skillId);
        if (req.getSkillName() != null) wrapper.set(Skill::getSkillName, XssSanitizer.sanitize(req.getSkillName(), 200));
        if (req.getIcon() != null) wrapper.set(Skill::getIcon, XssSanitizer.sanitize(req.getIcon(), 500));
        if (req.getDescription() != null) wrapper.set(Skill::getDescription, XssSanitizer.sanitize(req.getDescription(), 1000));
        if (req.getSkillType() != null) wrapper.set(Skill::getSkillType, req.getSkillType());
        if (req.getCategory() != null) wrapper.set(Skill::getCategory, req.getCategory());
        if (req.getTags() != null) wrapper.set(Skill::getTags, req.getTags());
        if (req.getSecurityLevel() != null) wrapper.set(Skill::getSecurityLevel, req.getSecurityLevel());
        if (req.getVisibility() != null) wrapper.set(Skill::getVisibility, req.getVisibility());
        if (req.getInputs() != null) wrapper.set(Skill::getInputs, req.getInputs());
        if (req.getOutputs() != null) wrapper.set(Skill::getOutputs, req.getOutputs());
        if (req.getBindingTools() != null) wrapper.set(Skill::getBindingTools, req.getBindingTools());
        if (req.getMappingConfig() != null) wrapper.set(Skill::getMappingConfig, req.getMappingConfig());
        // instructions 是技能核心方法论，空值不覆盖（与 create 路径守卫一致，防止客户端未回显导致误清空）
        if (req.getInstructions() != null && !req.getInstructions().isEmpty()) {
            wrapper.set(Skill::getInstructions, req.getInstructions());
        }
        if (req.getReferencesManifest() != null) wrapper.set(Skill::getReferencesManifest, req.getReferencesManifest());
        if (req.getTriggerExamples() != null) wrapper.set(Skill::getTriggerExamples, req.getTriggerExamples());

        skillMapper.update(null, wrapper);
        log.info("Skill updated: id={}, tenantId={}", skillId, tenantId);
    }

    /**
     * 查询技能详情。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID（可为null，仅校验租户级权限）
     * @param skillId  技能ID
     * @return 技能详情
     */
    public SkillVO getDetail(Long tenantId, Long userId, Long skillId) {
        return toVO(requireSkill(skillId, tenantId, userId));
    }

    /**
     * 分页查询技能。
     *
     * @param tenantId 租户ID
     * @param scope    视图范围：mine（本租户全部）/ market（已发布可订阅）
     * @param keyword  关键词（匹配名称/编码/描述）
     * @param type     技能类型过滤（ATOMIC/COMPOSITE）
     * @param page     页码
     * @param size     每页条数
     * @return 分页结果
     */
    public Page<SkillVO> page(Long tenantId, String scope, String keyword, String type, int page, int size) {
        return page(tenantId, scope, null, keyword, type, page, size);
    }

    /**
     * 分页查询技能（多字段模糊 + 热门排序）。
     *
     * @param tenantId 租户ID
     * @param scope    视图范围：mine / market / subscribed（用户已订阅，仅 PUBLISHED）
     * @param userId   用户ID（subscribed 视图必填）
     * @param keyword  关键词（匹配名称/描述/标签）
     * @param type     技能类型过滤（ATOMIC/COMPOSITE）
     * @param page     页码
     * @param size     每页条数
     * @return 分页结果
     */
    public Page<SkillVO> page(Long tenantId, String scope, Long userId,
                              String keyword, String type, int page, int size) {
        Page<Skill> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<Skill> wrapper = new LambdaQueryWrapper<Skill>();

        com.aegis.core.enums.resource.ResourceScope scopeEnum = com.aegis.core.enums.resource.ResourceScope.fromCode(scope);
        boolean isMarket = scopeEnum == com.aegis.core.enums.resource.ResourceScope.MARKET;
        boolean isSubscribed = scopeEnum == com.aegis.core.enums.resource.ResourceScope.SUBSCRIBED;
        boolean isMine = scopeEnum == com.aegis.core.enums.resource.ResourceScope.MINE;
        if (isMarket) {
            // 市场视图：租户隔离 - 仅返回本租户已发布资源，禁止跨租户访问
            wrapper.eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                    .eq(Skill::getTenantId, tenantId);
        } else if (isSubscribed) {
            // A4 订阅视图：res_skill_subscription 中 USER 订阅的技能（仅 PUBLISHED）
            List<Long> subscribedIds = listSubscribedSkillIds(tenantId, userId);
            if (subscribedIds.isEmpty()) {
                return convertPage(new Page<>(page, size), this::toVO, page, size);
            }
            wrapper.eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                    .eq(tenantId != null, Skill::getTenantId, tenantId)
                    .in(Skill::getId, subscribedIds);
        } else if (isMine) {
            // 我的视图 - 仅展示当前用户自己创建的技能（用户级隔离）
            wrapper.eq(Skill::getTenantId, tenantId)
                    .eq(userId != null, Skill::getAuthorUserId, userId);
        } else {
            // 兜底：本租户全部（管理后台无用户级过滤场景）
            wrapper.eq(tenantId != null, Skill::getTenantId, tenantId);
        }

        // 多字段模糊匹配（名称 + 描述 + 标签 JSON 包含）
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(Skill::getSkillName, kw)
                    .or().like(Skill::getDescription, kw)
                    .or().like(Skill::getTags, kw));
        }
        if (type != null && !type.isEmpty()) {
            try {
                SkillType skillType = SkillType.valueOf(type.toUpperCase());
                wrapper.eq(Skill::getSkillType, skillType);
            } catch (IllegalArgumentException ignored) {
                // 忽略无效类型
            }
        }

        // 市场视图按订阅数倒序 + 创建时间倒序（热门优先）
        if (isMarket) {
            wrapper.orderByDesc(Skill::getSubsCount);
        }
        wrapper.orderByDesc(Skill::getCreateTime);
        Page<Skill> entityPage = skillMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toVO, page, size);
    }

    /**
     * 查询用户订阅的技能ID列表（res_skill_subscription，USER 订阅）。
     */
    private List<Long> listSubscribedSkillIds(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return Collections.emptyList();
        }
        return skillSubscriptionMapper.selectList(
                        new LambdaQueryWrapper<SkillSubscription>()
                                .eq(SkillSubscription::getTenantId, tenantId)
                                .eq(SkillSubscription::getSubscriberType, SubscriberType.USER)
                                .eq(SkillSubscription::getSubscriberId, userId))
                .stream()
                .map(SkillSubscription::getSkillId)
                .collect(Collectors.toList());
    }

    /**
     * 提交技能审核发布（含安全扫描）。
     *
     * <p>提交前自动执行 {@link SkillSecurityScanner} 安全扫描，
     * P0 级风险直接阻断审核流程，技能 life_status 保持 DRAFT。</p>
     *
     * @param tenantId 租户ID
     * @param userId   操作人用户ID
     * @param skillId  技能ID
     */
    public void submitForReview(Long tenantId, Long userId, Long skillId) {
        Skill skill = requireSkill(skillId, tenantId, userId);

        // U8: 状态前置校验（fail-fast，避免非可提交状态先执行一轮完整安全扫描后才被引擎拒绝）
        // 规则与 ReviewProcessEngine.submit 内部校验一致：仅 DRAFT/REJECTED 可提交
        if (skill.getLifeStatus() != AgentLifeStatus.DRAFT
                && skill.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "技能当前状态不可提交审核: " + skill.getLifeStatus());
        }

        // 安全扫描
        SkillSecurityScanner.ScanResult scanResult = securityScanner.scan(skillId);
        if (!scanResult.isPassed()) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "安全扫描未通过（P0 风险阻断）: " + scanResult.getSummary());
        }

        reviewProcessEngine.submit(tenantId, "SKILL", skillId);
        log.info("Skill submitted for review: id={}, tenantId={}, scanPassed={}",
                skillId, tenantId, scanResult.isPassed());
    }

    /**
     * 删除技能（仅 DRAFT/REJECTED 可删除）。
     *
     * @param tenantId 租户ID
     * @param userId   操作人用户ID
     * @param skillId  技能ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long tenantId, Long userId, Long skillId) {
        Skill existing = requireSkill(skillId, tenantId, userId);
        if (existing.getLifeStatus() != AgentLifeStatus.DRAFT
                && existing.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅草稿/驳回态技能可删除，当前状态: " + existing.getLifeStatus());
        }
        skillMapper.deleteById(skillId);
        log.info("Skill deleted: id={}, tenantId={}", skillId, tenantId);
    }

    /**
     * 触发安全扫描（管理侧手动扫描）。
     *
     * @param tenantId 租户ID
     * @param userId   操作人用户ID
     * @param skillId  技能ID
     * @return 扫描结果
     */
    public SkillSecurityScanner.ScanResult triggerScan(Long tenantId, Long userId, Long skillId) {
        requireSkill(skillId, tenantId, userId);
        return securityScanner.scan(skillId);
    }

    /**
     * 退回草稿。
     *
     * <p>状态流转：PUBLISHED/ARCHIVED -> DRAFT。
     * 退回后作者可重新编辑，需重新提交审核才能再次发布。</p>
     *
     * @param tenantId 租户ID
     * @param userId   操作人用户ID（仅作者可操作）
     * @param skillId  技能ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void revertToDraft(Long tenantId, Long userId, Long skillId) {
        Skill skill = requireSkill(skillId, tenantId, userId);
        if (skill.getLifeStatus() != AgentLifeStatus.PUBLISHED
                && skill.getLifeStatus() != AgentLifeStatus.ARCHIVED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅已发布/已归档技能可退回草稿，当前状态: " + skill.getLifeStatus());
        }

        Skill update = new Skill();
        update.setId(skillId);
        update.setLifeStatus(AgentLifeStatus.DRAFT);
        skillMapper.updateById(update);

        log.info("Skill reverted to draft: id={}, fromStatus={}, operator={}",
                skillId, skill.getLifeStatus(), userId);
    }

    /**
     * 归档技能。
     *
     * <p>状态流转：PUBLISHED -> ARCHIVED。
     * 归档后技能从市场下架，订阅方无法继续解析加载，可通过"重新激活"退回草稿。</p>
     *
     * @param tenantId 租户ID
     * @param userId   操作人用户ID（仅作者可操作）
     * @param skillId  技能ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void archive(Long tenantId, Long userId, Long skillId) {
        Skill skill = requireSkill(skillId, tenantId, userId);
        if (skill.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅已发布技能可归档，当前状态: " + skill.getLifeStatus());
        }

        Skill update = new Skill();
        update.setId(skillId);
        update.setLifeStatus(AgentLifeStatus.ARCHIVED);
        skillMapper.updateById(update);

        log.info("Skill archived: id={}, operator={}", skillId, userId);
    }

    /**
     * 修改技能 scope（管理员操作，Controller 下沉方法）。
     *
     * <p>替代 SkillAdminController.updateScope 中直连 SkillMapper 的 selectById + UpdateWrapper。
     * 保留原领域规则：仅系统内置技能可升级为 GLOBAL，scope 必须为 GLOBAL 或 LOCAL。
     *
     * @param skillId   技能ID
     * @param newScope  目标 scope
     * @throws BusinessException 技能不存在(NOT_FOUND) / scope 非法(PARAM_ERROR) /
     *                           非内置技能升 GLOBAL(FORBIDDEN)
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateScope(Long skillId, com.aegis.core.enums.resource.SkillScope newScope) {
        if (newScope != com.aegis.core.enums.resource.SkillScope.GLOBAL
                && newScope != com.aegis.core.enums.resource.SkillScope.LOCAL) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "scope 必须是 GLOBAL 或 LOCAL");
        }
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + skillId);
        }
        if (newScope == com.aegis.core.enums.resource.SkillScope.GLOBAL
                && !Boolean.TRUE.equals(skill.getIsSystem())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只有系统内置技能可升级为 GLOBAL");
        }
        skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skillId)
                .set(Skill::getScope, newScope));
        log.info("Skill scope updated: skillId={}, newScope={}", skillId, newScope);
    }

    /**
     * 查询技能编码（Controller 下沉方法）。
     *
     * <p>替代 SkillUserController.subscribe 中直连 SkillMapper.selectById 仅取 skillCode 的场景。
     * 返回 null 表示技能不存在（由调用方决定是否阻断）。
     *
     * @param skillId 技能ID
     * @return 技能编码，不存在则 null
     */
    public String getSkillCodeById(Long skillId) {
        if (skillId == null) {
            return null;
        }
        Skill skill = skillMapper.selectById(skillId);
        return skill != null ? skill.getSkillCode() : null;
    }

    // ============ 内部方法 ============

    /**
     * 校验技能存在性与权限（租户级 + 用户级，仅技能作者可操作草稿态）。
     *
     * @param skillId  技能ID
     * @param tenantId 租户ID
     * @param userId   用户ID（为null时仅校验租户级权限）
     * @return 技能实体
     */
    private Skill requireSkill(Long skillId, Long tenantId, Long userId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + skillId);
        }
        if (tenantId != null && !tenantId.equals(skill.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该技能");
        }
        // 用户级权限校验 - 仅技能作者可操作草稿态技能
        if (userId != null && skill.getAuthorUserId() != null
                && !userId.equals(skill.getAuthorUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作他人创建的技能");
        }
        return skill;
    }

    private SkillVO toVO(Skill skill) {
        SkillVO vo = new SkillVO();
        BeanUtils.copyProperties(skill, vo);
        enrichBindingTools(vo);
        return vo;
    }

    /**
     * 补全 bindingTools JSON 中的工具详情。
     *
     * <p>DB binding_tools 存储纯 toolCode 字符串（如 {@code ["web_search"]}），
     * 前端展示时缺少 toolName/description 等详情。本方法解析后批量查 res_tool
     * 表补全详情，重建 JSON 返回给前端，前端 {@code parseBindingTools} 可
     * 自动识别对象格式并填充字段。
     *
     * <p>res_tool 在租户忽略表中，查询无需绑定 TenantContextHolder。
     */
    private void enrichBindingTools(SkillVO vo) {
        String bindingTools = vo.getBindingTools();
        if (bindingTools == null || bindingTools.isEmpty()) {
            return;
        }
        try {
            Object parsed = JSON.parse(bindingTools);
            Set<String> toolCodes = new HashSet<>();
            collectToolCodes(parsed, toolCodes);
            if (toolCodes.isEmpty()) {
                return;
            }
            List<Tool> tools = toolMapper.selectList(
                    new QueryWrapper<Tool>().in("tool_code", toolCodes));
            Map<String, Tool> toolMap = tools.stream()
                    .collect(Collectors.toMap(Tool::getToolCode, t -> t, (a, b) -> a));
            vo.setBindingTools(JSON.toJSONString(rebuildNode(parsed, toolMap)));
        } catch (Exception e) {
            log.warn("enrichBindingTools failed, keep original: {}", e.getMessage());
        }
    }

    /** 从 bindingTools JSON 递归收集所有 toolCode */
    private void collectToolCodes(Object node, Set<String> toolCodes) {
        if (node == null) return;
        if (node instanceof JSONArray arr) {
            for (Object item : arr) {
                if (item instanceof String s) toolCodes.add(s);
                else if (item instanceof JSONObject obj && obj.containsKey("toolCode")) {
                    toolCodes.add(obj.getString("toolCode"));
                }
            }
        } else if (node instanceof JSONObject obj) {
            for (Object val : obj.values()) collectToolCodes(val, toolCodes);
        }
    }

    /** 递归重建 bindingTools JSON：纯字符串 toolCode → 带详情的对象 */
    private Object rebuildNode(Object node, Map<String, Tool> toolMap) {
        if (node instanceof JSONArray arr) {
            JSONArray newArr = new JSONArray();
            for (Object item : arr) {
                if (item instanceof String toolCode) {
                    Tool tool = toolMap.get(toolCode);
                    if (tool != null) {
                        JSONObject detail = new JSONObject();
                        detail.put("toolCode", tool.getToolCode());
                        detail.put("toolName", tool.getToolName());
                        detail.put("description", tool.getDescription());
                        detail.put("toolType", tool.getToolType() != null ? tool.getToolType().name() : null);
                        newArr.add(detail);
                    } else {
                        newArr.add(toolCode);
                    }
                } else {
                    newArr.add(rebuildNode(item, toolMap));
                }
            }
            return newArr;
        } else if (node instanceof JSONObject obj) {
            JSONObject newObj = new JSONObject();
            for (Map.Entry<String, Object> entry : obj.entrySet()) {
                newObj.put(entry.getKey(), rebuildNode(entry.getValue(), toolMap));
            }
            return newObj;
        }
        return node;
    }

    private <E, V> Page<V> convertPage(Page<E> entityPage, Function<E, V> converter, int page, int size) {
        Page<V> voPage = new Page<>(page, size);
        voPage.setTotal(entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(converter).collect(Collectors.toList()));
        return voPage;
    }
}
