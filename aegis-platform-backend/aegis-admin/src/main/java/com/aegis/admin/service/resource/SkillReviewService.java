package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.dto.resource.SkillApproveRequest;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.ReviewStatus;
import com.aegis.core.enums.common.Visibility;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 技能多级审批链领域服务。
 *
 * <p>实现技能发布的两级审批链：对 PUBLIC（全平台可见）技能需要 L1 初审 + L2 终审，
 * 对 TENANT（本租户可见）技能只需单级审批。支持每级的 approve / reject 操作。</p>
 *
 * @author wang.zhen
 * @see ResourceReview
 * @see Skill
 * @see SkillManageService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillReviewService {

    private final ResourceReviewMapper resourceReviewMapper;
    private final SkillMapper skillMapper;

    /**
     * 审批通过。
     *
     * @param tenantId 租户ID
     * @param req      审批请求（含 reviewId、approveLevel、approverUserId）
     */
    @Transactional(rollbackFor = Exception.class)
    public void approve(Long tenantId, SkillApproveRequest req) {
        // 1. 获取并校验审核单
        ResourceReview review = requireReview(req.getReviewId());
        if (review.getReviewStatus() != ReviewStatus.PENDING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "审核单当前状态不可审批: " + review.getReviewStatus());
        }

        // 获取技能并校验租户归属
        Skill skill = requireSkill(review.getResourceId(), tenantId);

        // 4. 根据可见性选择审批路径
        Visibility visibility = skill.getVisibility() != null
                ? skill.getVisibility() : Visibility.TENANT;

        if (visibility == Visibility.PUBLIC) {
            handlePublicApprove(review, skill, req);
        } else {
            handleTenantApprove(review, skill, req);
        }
    }

    /**
     * 审批驳回。
     *
     * @param tenantId 租户ID
     * @param req      审批请求（含 reviewId、rejectReason、approverUserId）
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long tenantId, SkillApproveRequest req) {
        // 1. 获取并校验审核单
        ResourceReview review = requireReview(req.getReviewId());
        if (review.getReviewStatus() != ReviewStatus.PENDING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "审核单当前状态不可驳回: " + review.getReviewStatus());
        }
        if (req.getRejectReason() == null || req.getRejectReason().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "驳回原因不能为空");
        }

        // 2. 获取技能并校验租户归属
        Skill skill = requireSkill(review.getResourceId(), tenantId);

        // 3. 更新审核单状态
        LocalDateTime now = LocalDateTime.now();
        resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                .eq(ResourceReview::getId, review.getId())
                .set(ResourceReview::getReviewStatus, ReviewStatus.REJECTED)
                .set(ResourceReview::getReviewerUserId, req.getApproverUserId())
                .set(ResourceReview::getReviewTime, now)
                .set(ResourceReview::getRejectReason, req.getRejectReason()));

        // 4. 更新技能状态
        skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skill.getId())
                .set(Skill::getLifeStatus, AgentLifeStatus.REJECTED));

        log.info("技能审批驳回: reviewId={}, skillId={}, approverId={}, reason={}",
                review.getId(), skill.getId(), req.getApproverUserId(), req.getRejectReason());
    }

    // ============ 内部审批逻辑 ============

    /**
     * 处理 PUBLIC 技能审批（两级审批链）。
     */
    private void handlePublicApprove(ResourceReview review, Skill skill, SkillApproveRequest req) {
        LocalDateTime now = LocalDateTime.now();
        String level = req.getApproveLevel() != null ? req.getApproveLevel().toUpperCase() : "L1";

        if ("L1".equals(level)) {
            resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                    .eq(ResourceReview::getId, review.getId())
                    .set(ResourceReview::getReviewerUserId, req.getApproverUserId())
                    .set(ResourceReview::getReviewTime, now));

            log.info("技能 L1 初审通过: reviewId={}, skillId={}, 等待 L2 终审",
                    review.getId(), skill.getId());
        } else if ("L2".equals(level)) {
            resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                    .eq(ResourceReview::getId, review.getId())
                    .set(ResourceReview::getReviewStatus, ReviewStatus.APPROVED)
                    .set(ResourceReview::getReviewerUserId, req.getApproverUserId())
                    .set(ResourceReview::getReviewTime, now));

            skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                    .eq(Skill::getId, skill.getId())
                    .set(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                    .set(Skill::getPublishedTime, now));

            log.info("技能 L2 终审通过（已发布）: reviewId={}, skillId={}",
                    review.getId(), skill.getId());
        } else {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的审批级别: " + level);
        }
    }

    /**
     * 处理 TENANT 技能审批（单级审批）。
     */
    private void handleTenantApprove(ResourceReview review, Skill skill, SkillApproveRequest req) {
        LocalDateTime now = LocalDateTime.now();

        resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                .eq(ResourceReview::getId, review.getId())
                .set(ResourceReview::getReviewStatus, ReviewStatus.APPROVED)
                .set(ResourceReview::getReviewerUserId, req.getApproverUserId())
                .set(ResourceReview::getReviewTime, now));

        skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skill.getId())
                .set(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .set(Skill::getPublishedTime, now));

        log.info("技能单级审批通过（已发布）: reviewId={}, skillId={}",
                review.getId(), skill.getId());
    }

    // ============ 校验方法 ============

    /**
     * 获取审核单，不存在则抛出业务异常。
     */
    /**
     * 解析技能的最新审核单ID（Controller 下沉方法）。
     *
     * <p>替代 SkillController.approveSkill/rejectSkill 中重复的 ResourceReviewMapper 直查，
     * 消除 4.1 节"approve vs reject 重复 LambdaQueryWrapper"代码债。查询最近一条 SKILL 类型
     * 审核单（按 id 倒序），返回其 ID；无记录返回 null，由 Controller 决定是否阻断。
     *
     * @param skillId 技能ID
     * @return 最新审核单ID，无则 null
     */
    public Long resolveLatestReviewId(Long skillId) {
        if (skillId == null) {
            return null;
        }
        ResourceReview review = resourceReviewMapper.selectOne(
                new LambdaQueryWrapper<ResourceReview>()
                        .eq(ResourceReview::getResourceType, com.aegis.core.enums.resource.ResourceType.SKILL)
                        .eq(ResourceReview::getResourceId, skillId)
                        .orderByDesc(ResourceReview::getId)
                        .last("LIMIT 1"));
        return review != null ? review.getId() : null;
    }

    private ResourceReview requireReview(Long reviewId) {
        ResourceReview review = resourceReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核单不存在: " + reviewId);
        }
        return review;
    }

    /**
     * 获取技能并校验租户归属，不存在或无权操作则抛出业务异常。
     */
    private Skill requireSkill(Long skillId, Long tenantId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + skillId);
        }
        if (tenantId != null && !tenantId.equals(skill.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该技能");
        }
        return skill;
    }
}