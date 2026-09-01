package com.aegis.admin.service.resource;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.util.SkillCodeGenerator;
import com.aegis.core.util.XssSanitizer;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 技能生命周期核心服务。
 *
 * <p>提取 admin / runtime 两条创建路径的公共逻辑，
 * 确保字段校验、版本号、默认值、XSS 清洗、编码唯一性等行为完全一致，
 * 消除双路径漂移导致的数据一致性风险。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLifecycleService {

    private final SkillMapper skillMapper;

    /**
     * 统一创建技能草稿（admin / runtime 共用入口）。
     *
     * <p>执行完整的校验 + 清洗 + 默认值填充 + 编码唯一性检查，
     * 返回创建后的技能实体。调用方无需再做字段级校验。</p>
     *
     * @param tenantId      租户ID（必填）
     * @param userId        操作人用户ID（必填，将作为 authorUserId）
     * @param skillCode     技能编码（必填，租户内唯一）
     * @param skillName     技能名称（必填）
     * @param skillType     技能类型，null 则默认 ATOMIC
     * @param category      分类，null 则默认 CONTENT
     * @param securityLevel 安全等级，null 则默认 L1
     * @return 创建后的技能实体
     */
    public Skill createDraft(Long tenantId, Long userId,
                             String skillCode, String skillName,
                             SkillType skillType, SkillCategory category,
                             SecurityLevel securityLevel) {
        // ========== 必填校验 ==========
        if (tenantId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        if (skillCode == null || skillCode.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "技能编码不能为空");
        }
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "技能名称不能为空");
        }

        String code = skillCode.trim();
        String name = skillName.trim();

        // ========== XSS 清洗 ==========
        name = XssSanitizer.sanitize(name, 200);

        // ========== 编码唯一性（租户内） ==========
        Long exists = skillMapper.selectCount(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getTenantId, tenantId)
                        .eq(Skill::getSkillCode, code));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "技能编码已存在: " + code);
        }

        // ========== 构建实体（统一默认值） ==========
        // 注：Skill extends TenantEntity，@Builder 不继承父类字段，使用 setter 模式
        Skill skill = new Skill();
        skill.setTenantId(tenantId);
        skill.setSkillCode(code);
        skill.setSkillName(name);
        skill.setSkillType(skillType != null ? skillType : SkillType.ATOMIC);
        skill.setCategory(category != null ? category : SkillCategory.CONTENT);
        skill.setSecurityLevel(securityLevel != null ? securityLevel : SecurityLevel.L1);
        skill.setVisibility(Visibility.TENANT);
        skill.setLifeStatus(AgentLifeStatus.DRAFT);
        // 版本号统一为 0.0.1
        skill.setVersion("0.0.1");
        skill.setLatestVersion("0.0.1");
        skill.setActiveVersion("0.0.1");
        // 默认 JSON 字段
        skill.setTags("[]");
        skill.setBindingTools("[]");
        skill.setInputs("{}");
        skill.setOutputs("{}");
        skill.setMappingConfig("{}");
        // 初始计数
        skill.setSubsCount(0);
        skill.setHealthScore(new java.math.BigDecimal("100.00"));
        // 用户级隔离
        skill.setAuthorUserId(userId);
        skill.setIsSystem(false);
        skill.setCertified(false);
        skill.setCreateBy(userId);
        skill.setCreateTime(java.time.LocalDateTime.now());
        skill.setDeleted(0);

        skillMapper.insert(skill);

        log.info("Skill draft created (unified): id={}, code={}, tenantId={}, authorUserId={}, type={}",
                skill.getId(), code, tenantId, userId, skill.getSkillType());
        return skill;
    }

    /**
     * 更新技能描述/正文等文本字段（runtime 对话编辑用，带 XSS 清洗）。
     *
     * <p>仅更新非空字段，避免 null 覆盖原值。适用于 LLM 逐步补充字段的场景。</p>
     *
     * @param skill         技能实体（必须已存在）
     * @param skillName     技能名称（null 则不更新）
     * @param description   描述（null 则不更新）
     * @param instructions  方法论正文（null 则不更新）
     * @param category      分类（null 则不更新）
     * @param securityLevel 安全等级（null 则不更新）
     * @param bindingTools  绑定工具（null 则不更新）
     * @return 是否有字段被更新
     */
    public boolean patchFields(Skill skill,
                               String skillName,
                               String description,
                               String instructions,
                               SkillCategory category,
                               SecurityLevel securityLevel,
                               String bindingTools) {
        boolean updated = false;

        if (skillName != null && !skillName.trim().isEmpty()) {
            String sanitized = XssSanitizer.sanitize(skillName.trim(), 200);
            if (!sanitized.equals(skill.getSkillName())) {
                skill.setSkillName(sanitized);
                updated = true;
            }
        }
        if (description != null) {
            String sanitized = XssSanitizer.sanitize(description, 1000);
            if (!sanitized.equals(skill.getDescription())) {
                skill.setDescription(sanitized);
                updated = true;
            }
        }
        if (instructions != null) {
            if (!instructions.equals(skill.getInstructions())) {
                skill.setInstructions(instructions);
                updated = true;
            }
        }
        if (category != null && !category.equals(skill.getCategory())) {
            skill.setCategory(category);
            updated = true;
        }
        if (securityLevel != null && !securityLevel.equals(skill.getSecurityLevel())) {
            skill.setSecurityLevel(securityLevel);
            updated = true;
        }
        if (bindingTools != null) {
            if (!bindingTools.equals(skill.getBindingTools())) {
                skill.setBindingTools(bindingTools);
                updated = true;
            }
        }

        if (updated) {
            skillMapper.updateById(skill);
            log.debug("Skill fields patched: skillId={}, fields updated", skill.getId());
        }
        return updated;
    }

    /**
     * 从名称生成技能编码（兜底用，runtime 创建场景常用）。
     *
     * <p>U5 统一：委托 {@link SkillCodeGenerator}（admin/runtime 三套生成器收敛为单一实现）。
     * 规则：保留字母数字，转小写，长度限制 32 字符；
     * 若生成的编码为空则使用 "skill_" + 时间戳。</p>
     *
     * @param name 技能名称
     * @return 生成的 skillCode
     */
    public String generateSkillCodeFromName(String name) {
        return SkillCodeGenerator.fromName(name);
    }
}
