package com.aegis.admin.service.resource.handler;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 技能资源审核 SPI 实现。驳回回退状态为 {@link AgentLifeStatus#REJECTED}
 * （与 SkillReviewService.reject 对齐，submitForReview 允许 REJECTED 重提，语义闭环）。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillReviewHandler implements ResourceReviewHandler {

    private final SkillMapper skillMapper;

    @Override
    public ResourceType supportedType() {
        return ResourceType.SKILL;
    }

    @Override
    public ResourceReviewInfo loadResourceInfo(Long resourceId) {
        Skill skill = skillMapper.selectById(resourceId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + resourceId);
        }
        if (skill.getLifeStatus() != AgentLifeStatus.DRAFT
                && skill.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "技能当前状态不可提交审核: " + skill.getLifeStatus());
        }
        Integer securityLevel = skill.getSecurityLevel() != null
                ? skill.getSecurityLevel().ordinal() + 1 : null;
        return new ResourceReviewInfo(
                skill.getSkillName(),
                skill.getVersion(),
                securityLevel,
                skill.getAuthorUserId(),
                skill.getAuthorDeptId());
    }

    @Override
    public void updateLifeStatus(Long resourceId, AgentLifeStatus lifeStatus,
                                 String newVersion, LocalDateTime publishedTime) {
        Skill skill = skillMapper.selectById(resourceId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + resourceId);
        }
        String version = newVersion != null ? newVersion
                : ResourceReviewHandler.bumpVersion(skill.getVersion(), lifeStatus);
        skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, resourceId)
                .set(Skill::getLifeStatus, lifeStatus)
                .set(Skill::getVersion, version)
                .set(publishedTime != null, Skill::getPublishedTime, publishedTime));
    }

    @Override
    public AgentLifeStatus rejectStatus() {
        return AgentLifeStatus.REJECTED;
    }
}
