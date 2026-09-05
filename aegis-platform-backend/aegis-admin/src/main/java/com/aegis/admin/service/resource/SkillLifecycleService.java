package com.aegis.admin.service.resource;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.helper.SkillDomainHelper;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 技能生命周期核心服务（admin 入口）。
 *
 * <p>核心实体构建 / patch / 版本递增逻辑已下沉到 {@link SkillDomainHelper}，
 * 本 Service 负责：编码唯一性校验 + 调用 Helper + 落库。runtime 走独立链路但共用 Helper，
 * 两条路径字段构建行为完全一致。</p>
 *
 * @author wang.zhen
 * @see SkillDomainHelper
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillLifecycleService {

    private final SkillMapper skillMapper;

    /**
     * 统一创建技能草稿（admin 入口）。
     *
     * <p>编码唯一性校验后委托 {@link SkillDomainHelper#buildDefaultSkill} 构建实体，
     * 字段级行为与 runtime SkillCreatorOrchestrator 完全一致。</p>
     */
    public Skill createDraft(Long tenantId, Long userId,
                             String skillCode, String skillName,
                             SkillType skillType, SkillCategory category,
                             SecurityLevel securityLevel) {
        // 编码唯一性（租户内）——Helper 不查 DB，这个校验留在 admin Service
        Long exists = skillMapper.selectCount(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getTenantId, tenantId)
                        .eq(Skill::getSkillCode, skillCode != null ? skillCode.trim() : null));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "技能编码已存在: " + skillCode);
        }

        Skill skill = SkillDomainHelper.buildDefaultSkill(tenantId, userId, skillCode, skillName, skillType, category, securityLevel);
        skillMapper.insert(skill);

        log.info("Skill draft created (admin): id={}, code={}, tenantId={}, type={}",
                skill.getId(), skill.getSkillCode(), tenantId, skill.getSkillType());
        return skill;
    }

    /**
     * 增量 patch 字段 + 落库（admin 更新入口）。
     *
     * <p>委托 {@link SkillDomainHelper#patchFields} 做对象级变更，
     * 有变化才 updateById，避免无意义 DB 写。</p>
     */
    public boolean patchFieldsAndSave(Skill skill,
                                      String description,
                                      String instructions,
                                      String bindingTools) {
        boolean updated = SkillDomainHelper.patchFields(skill, null, description, instructions,
                null, null, bindingTools);
        if (updated) {
            skillMapper.updateById(skill);
            log.debug("Skill fields patched (admin): skillId={}", skill.getId());
        }
        return updated;
    }
}
