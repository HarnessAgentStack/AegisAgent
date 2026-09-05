package com.aegis.core.helper;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SkillType;

import java.time.LocalDateTime;

/**
 * 技能领域工具类 —— 纯静态，零 DB / util 依赖。
 *
 * <p>收敛 admin / runtime 两条创建路径重复的字段构建、默认值填充、版本号计算逻辑。
 * 只做对象级操作。参数校验和 XSS 清洗由调用方（runtime SkillCreatorOrchestrator / admin SkillLifecycleService）负责。</p>
 *
 * @author aegis
 */
public final class SkillDomainHelper {

    private SkillDomainHelper() {}

    /**
     * 构建一个全新的技能草稿实体。
     *
     * <p>填好所有默认值、硬编码字段（scope/version/counts/timestamps）。
     * 调用方负责：参数非空校验 + XSS 清洗 + 编码唯一性检查 + DB 插入。</p>
     */
    public static Skill buildDefaultSkill(Long tenantId, Long userId,
                                           String skillCode, String skillName,
                                           SkillType skillType, SkillCategory category,
                                           SecurityLevel securityLevel) {
        Skill skill = new Skill();
        skill.setTenantId(tenantId);
        skill.setSkillCode(skillCode);
        skill.setSkillName(skillName);
        skill.setSkillType(skillType != null ? skillType : SkillType.ATOMIC);
        skill.setCategory(category != null ? category : SkillCategory.CONTENT);
        skill.setSecurityLevel(securityLevel != null ? securityLevel : SecurityLevel.L1);
        skill.setVisibility(Visibility.TENANT);
        skill.setScope(SkillScope.LOCAL);
        skill.setLifeStatus(AgentLifeStatus.DRAFT);
        skill.setVersion("0.0.1");
        skill.setLatestVersion("0.0.1");
        skill.setActiveVersion("0.0.1");
        skill.setTags("[]");
        skill.setBindingTools("[]");
        skill.setInputs("{}");
        skill.setOutputs("{}");
        skill.setMappingConfig("{}");
        skill.setSubsCount(0);
        skill.setAuthorUserId(userId);
        skill.setIsSystem(false);
        skill.setCreateBy(userId);
        skill.setCreateTime(LocalDateTime.now());
        skill.setDeleted(0);
        return skill;
    }

    /**
     * 增量 patch 字段（对话内 MODIFY 用）。null 参数表示"不更新该字段"。
     *
     * @return 是否有字段实际变化
     */
    public static boolean patchFields(Skill skill,
                                      String skillName,
                                      String description,
                                      String instructions,
                                      SkillCategory category,
                                      SecurityLevel securityLevel,
                                      String bindingTools) {
        boolean updated = false;
        if (skillName != null && !skillName.trim().isEmpty()) {
            String trimmed = skillName.trim();
            if (!trimmed.equals(skill.getSkillName())) { skill.setSkillName(trimmed); updated = true; }
        }
        if (description != null) {
            if (!description.equals(skill.getDescription())) { skill.setDescription(description); updated = true; }
        }
        if (instructions != null && !instructions.equals(skill.getInstructions())) {
            skill.setInstructions(instructions); updated = true;
        }
        if (category != null && !category.equals(skill.getCategory())) {
            skill.setCategory(category); updated = true;
        }
        if (securityLevel != null && !securityLevel.equals(skill.getSecurityLevel())) {
            skill.setSecurityLevel(securityLevel); updated = true;
        }
        if (bindingTools != null && !bindingTools.equals(skill.getBindingTools())) {
            skill.setBindingTools(bindingTools); updated = true;
        }
        return updated;
    }

    /**
     * 语义化版本递增。
     *
     * @param current 当前版本，如 "0.0.1"
     * @param level   MINOR（0.0.1 → 0.1.0）/ MAJOR（0.3.0 → 1.0.0）/ PATCH（默认）
     */
    public static String bumpVersion(String current, String level) {
        String v = (current != null && !current.isEmpty()) ? current : "0.0.1";
        String[] parts = v.split("\\.");
        int major = parts.length > 0 ? safeParse(parts[0]) : 0;
        int minor = parts.length > 1 ? safeParse(parts[1]) : 0;
        int patch = parts.length > 2 ? safeParse(parts[2]) : 0;

        if ("MAJOR".equalsIgnoreCase(level)) return (major + 1) + ".0.0";
        if ("MINOR".equalsIgnoreCase(level)) return major + "." + (minor + 1) + ".0";
        return major + "." + minor + "." + (patch + 1);
    }

    private static int safeParse(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }
}
