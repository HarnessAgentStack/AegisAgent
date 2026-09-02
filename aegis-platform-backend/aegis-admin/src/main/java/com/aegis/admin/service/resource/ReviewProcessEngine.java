package com.aegis.admin.service.resource;

import com.aegis.admin.service.resource.handler.ResourceReviewHandler;
import com.aegis.admin.service.resource.handler.ResourceReviewInfo;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.ReviewStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 审核流程引擎。
 *
 * <p>资源（技能/知识库）发布审核的状态机引擎，管理审核单的提交→审核→通过/驳回→重交流转。
 * 驱动 {@code KnowledgeBaseService} 与 {@code SkillManageService} 的审核环节，
 * 保证发布物的合规性与可追溯。
 *
 * <h3>状态机</h3>
 * <ul>
 *   <li>{@code DRAFT/REJECTED} → {@code REVIEWING}：提交审核，审核单 PENDING</li>
 *   <li>{@code REVIEWING} → {@code PUBLISHED}：审核通过，版本递增 + 写入 publishedTime</li>
 *   <li>{@code REVIEWING} → {@code DRAFT}：审核驳回，附驳回原因，可修改后重交</li>
 *   <li>{@code REJECTED}（审核单）→ {@code PENDING}（审核单）：修改后重交</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>审核单与资源双向状态联动：审核单状态 + 资源 lifeStatus 同步更新</li>
 *   <li>版本递增：审核通过时按语义化版本递增（草稿 0.0.x → 1.0.0；已发布 x.y.z → x.(y+1).0）</li>
 *   <li>审计追溯：状态流转全记录于 res_review 表，支撑审计查询</li>
 * </ul>
 *
 * @author wang.zhen
 * @see com.aegis.admin.service.resource.KnowledgeBaseService
 * @see com.aegis.admin.service.resource.SkillManageService
 * @see ResourceReview
 * @see ReviewStatus
 */
@Slf4j
@Component
public class ReviewProcessEngine {

    private final ResourceReviewMapper resourceReviewMapper;
    private final Map<ResourceType, ResourceReviewHandler> handlers;

    /**
     * Spring 注入所有 {@link ResourceReviewHandler} 实现，按 {@link ResourceReviewHandler#supportedType()}
     * 转为 Map 供按类型分发，新增资源类型仅需新增 Handler 实现即可被自动纳入，无需改动本引擎。
     */
    public ReviewProcessEngine(ResourceReviewMapper resourceReviewMapper,
                               List<ResourceReviewHandler> handlerList) {
        this.resourceReviewMapper = resourceReviewMapper;
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ResourceReviewHandler::supportedType, h -> h));
    }

    /**
     * 提交审核（DRAFT/REJECTED → REVIEWING）。
     *
     * <p>创建审核单（PENDING），并将资源状态更新为 REVIEWING。
     *
     * @param tenantId     租户ID
     * @param resourceType 资源类别（SKILL/KNOWLEDGE_BASE）
     * @param resourceId   资源ID
     * @return 审核单ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(Long tenantId, String resourceType, Long resourceId) {
        // MCP 等平台级资源 tenantId 为 null 时使用默认租户 1
        Long effectiveTenantId = tenantId != null ? tenantId : 1L;
        
        // ★ 强制幂等检查：同一资源已有活跃审核（PENDING）时直接返回
        ResourceType type = parseResourceType(resourceType);
        Long activeCount = resourceReviewMapper.selectCount(new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getTenantId, effectiveTenantId)
                .eq(ResourceReview::getResourceType, type)
                .eq(ResourceReview::getResourceId, resourceId)
                .eq(ResourceReview::getReviewStatus, ReviewStatus.PENDING));
        if (activeCount != null && activeCount > 0) {
            log.info("资源已有活跃审核记录，跳过重复提交: type={}, resourceId={}, count={}",
                    type, resourceId, activeCount);
            // 返回已有的活跃审核记录ID
            ResourceReview existing = resourceReviewMapper.selectOne(new LambdaQueryWrapper<ResourceReview>()
                    .eq(ResourceReview::getTenantId, effectiveTenantId)
                    .eq(ResourceReview::getResourceType, type)
                    .eq(ResourceReview::getResourceId, resourceId)
                    .eq(ResourceReview::getReviewStatus, ReviewStatus.PENDING)
                    .orderByDesc(ResourceReview::getId)
                    .last("LIMIT 1"));
            return existing != null ? existing.getId() : null;
        }
        // ★ 幂等检查结束
        // 校验资源存在且状态允许提交：SPI 分发到对应资源类型的 Handler
        ResourceReviewHandler handler = handlers.get(type);
        if (handler == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "不支持审核的资源类型: " + resourceType);
        }
        ResourceReviewInfo info = handler.loadResourceInfo(resourceId);
        String resourceName = info.resourceName();
        String resourceVersion = info.resourceVersion();
        Integer securityLevel = info.securityLevel();
        Long authorUserId = info.authorUserId();
        Long authorDeptId = info.authorDeptId();

        // 创建或重用审核单（驳回后重新提交时更新已有记录）
        LambdaQueryWrapper<ResourceReview> existingWrapper = new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getTenantId, effectiveTenantId)
                .eq(ResourceReview::getResourceType, type)
                .eq(ResourceReview::getResourceId, resourceId)
                .orderByDesc(ResourceReview::getId)
                .last("LIMIT 1");
        // resourceVersion 为 null 时使用 IS NULL 判断（避免 SQL = NULL 问题）
        if (resourceVersion != null) {
            existingWrapper.eq(ResourceReview::getResourceVersion, resourceVersion);
        } else {
            existingWrapper.isNull(ResourceReview::getResourceVersion);
        }
        ResourceReview existingReview = resourceReviewMapper.selectOne(existingWrapper);
        ResourceReview review;
        if (existingReview != null) {
            existingReview.setReviewStatus(ReviewStatus.PENDING);
            existingReview.setSubmitTime(LocalDateTime.now());
            existingReview.setReviewerUserId(null);
            existingReview.setReviewTime(null);
            existingReview.setRejectReason(null);
            resourceReviewMapper.updateById(existingReview);
            review = existingReview;
        } else {
            review = ResourceReview.builder()
                    .resourceType(type)
                    .resourceId(resourceId)
                    .resourceName(resourceName)
                    .resourceVersion(resourceVersion)
                    .applicantUserId(authorUserId)
                    .applicantDeptId(authorDeptId)
                    .securityLevel(securityLevel)
                    .reviewStatus(ReviewStatus.PENDING)
                    .submitTime(LocalDateTime.now())
                    .build();
            review.setTenantId(effectiveTenantId);
            resourceReviewMapper.insert(review);
        }

        // 更新资源状态为审核中
        updateResourceLifeStatus(type, resourceId, AgentLifeStatus.REVIEWING, null, null);

        log.info("审核单提交: reviewId={}, type={}, resourceId={}, tenantId={}",
                review.getId(), type, resourceId, effectiveTenantId);
        return review.getId();
    }

    /**
     * 审核通过（REVIEWING → PUBLISHED）。
     *
     * <p>审核单标记为 APPROVED，资源状态变为 PUBLISHED，版本号递增，写入 publishedTime。
     *
     * @param reviewId   审核单ID
     * @param approverId 审批人ID
     * @return 审核单对象（包含资源类型和资源ID，供调用方执行类型特定副作用）
     */
    @Transactional(rollbackFor = Exception.class)
    public ResourceReview approve(Long reviewId, Long approverId) {
        ResourceReview review = requireReview(reviewId);
        if (review.getReviewStatus() != ReviewStatus.PENDING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "审核单当前状态不可通过: " + review.getReviewStatus());
        }
        // 更新审核单
        resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                .eq(ResourceReview::getId, reviewId)
                .set(ResourceReview::getReviewStatus, ReviewStatus.APPROVED)
                .set(ResourceReview::getReviewerUserId, approverId)
                .set(ResourceReview::getReviewTime, LocalDateTime.now()));

        // 更新资源状态为已发布，版本递增
        ResourceType type = review.getResourceType();
        updateResourceLifeStatus(type, review.getResourceId(),
                AgentLifeStatus.PUBLISHED, null, LocalDateTime.now());

        log.info("审核通过: reviewId={}, approverId={}, type={}, resourceId={}",
                reviewId, approverId, type, review.getResourceId());
        return review;
    }

    /**
     * 审核驳回（REVIEWING → DRAFT）。
     *
     * <p>审核单标记为 REJECTED 并记录驳回原因，资源回退为 DRAFT 可继续编辑。
     *
     * @param reviewId   审核单ID
     * @param approverId 审批人ID
     * @param reason     驳回原因
     */
    @Transactional(rollbackFor = Exception.class)
    public void reject(Long reviewId, Long approverId, String reason) {
        ResourceReview review = requireReview(reviewId);
        if (review.getReviewStatus() != ReviewStatus.PENDING) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "审核单当前状态不可驳回: " + review.getReviewStatus());
        }
        // 更新审核单
        resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                .eq(ResourceReview::getId, reviewId)
                .set(ResourceReview::getReviewStatus, ReviewStatus.REJECTED)
                .set(ResourceReview::getReviewerUserId, approverId)
                .set(ResourceReview::getReviewTime, LocalDateTime.now())
                .set(ResourceReview::getRejectReason, reason));

        // 资源回退：由各 ResourceReviewHandler 决定回退状态
        // （AGENT/MCP_SERVICE/SKILL → REJECTED；KNOWLEDGE_BASE → DRAFT；未知类型兜底 DRAFT）
        ResourceType type = review.getResourceType();
        ResourceReviewHandler handler = handlers.get(type);
        AgentLifeStatus rejectStatus = handler != null ? handler.rejectStatus() : AgentLifeStatus.DRAFT;
        updateResourceLifeStatus(type, review.getResourceId(),
                rejectStatus, null, null);

        log.info("审核驳回: reviewId={}, approverId={}, reason={}, resourceId={}",
                reviewId, approverId, reason, review.getResourceId());
    }

    /**
     * 重新提交（驳回后重交）。
     *
     * <p>将已驳回的审核单重新置为 PENDING，资源状态更新为 REVIEWING。
     *
     * @param reviewId 审核单ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void resubmit(Long reviewId) {
        ResourceReview review = requireReview(reviewId);
        if (review.getReviewStatus() != ReviewStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅已驳回的审核单可重新提交，当前状态: " + review.getReviewStatus());
        }

        // 更新审核单为待审核
        resourceReviewMapper.update(null, new LambdaUpdateWrapper<ResourceReview>()
                .eq(ResourceReview::getId, reviewId)
                .set(ResourceReview::getReviewStatus, ReviewStatus.PENDING)
                .set(ResourceReview::getRejectReason, null)
                .set(ResourceReview::getSubmitTime, LocalDateTime.now()));

        // 资源状态更新为审核中
        ResourceType type = review.getResourceType();
        updateResourceLifeStatus(type, review.getResourceId(),
                AgentLifeStatus.REVIEWING, null, null);

        log.info("审核重交: reviewId={}, type={}, resourceId={}",
                reviewId, type, review.getResourceId());
    }

    // ============ 内部方法 ============

    /**
     * 检查指定资源是否存在活跃审核记录（PENDING 或 REVIEWING 状态）。
     *
     * <p>用于 MCP 服务自注册时的幂等校验，避免重复创建审核单。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源ID
     * @param tenantId      租户ID
     * @return true 表示存在活跃审核记录
     */
    public boolean hasActiveReview(ResourceType resourceType, Long resourceId, Long tenantId) {
        Long effectiveTenantId = tenantId != null ? tenantId : 1L;
        Long count = resourceReviewMapper.selectCount(new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getTenantId, effectiveTenantId)
                .eq(ResourceReview::getResourceType, resourceType)
                .eq(ResourceReview::getResourceId, resourceId)
                .eq(ResourceReview::getReviewStatus, ReviewStatus.PENDING));
        return count != null && count > 0;
    }

    /**
     * 查询指定资源的审核记录列表（用于详情展示）。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源ID
     * @param tenantId      租户ID
     * @return 审核记录列表
     */
    public List<ResourceReview> listReviewsByResource(ResourceType resourceType, Long resourceId, Long tenantId) {
        Long effectiveTenantId = tenantId != null ? tenantId : 1L;
        return resourceReviewMapper.selectList(new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getTenantId, effectiveTenantId)
                .eq(ResourceReview::getResourceType, resourceType)
                .eq(ResourceReview::getResourceId, resourceId)
                .orderByDesc(ResourceReview::getSubmitTime));
    }

    /**
     * 查询指定资源最新的审核记录。
     *
     * @param resourceType 资源类型
     * @param resourceId   资源ID
     * @param tenantId      租户ID
     * @return 最新审核记录或 null
     */
    public ResourceReview getLatestReview(ResourceType resourceType, Long resourceId, Long tenantId) {
        Long effectiveTenantId = tenantId != null ? tenantId : 1L;
        return resourceReviewMapper.selectOne(new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getTenantId, effectiveTenantId)
                .eq(ResourceReview::getResourceType, resourceType)
                .eq(ResourceReview::getResourceId, resourceId)
                .orderByDesc(ResourceReview::getSubmitTime)
                .last("LIMIT 1"));
    }

    private ResourceReview requireReview(Long reviewId) {
        ResourceReview review = resourceReviewMapper.selectById(reviewId);
        if (review == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核单不存在: " + reviewId);
        }
        return review;
    }

    /**
     * 更新资源生命周期状态，并在发布时递增版本号。
     *
     * <p>通过 SPI 分发到对应资源类型的 {@link ResourceReviewHandler}，由 Handler 执行
     * 具体的 selectById 校验、版本递增与状态更新（含 AgentConfig 快照复制等类型特定副作用）。
     *
     * @param type          资源类型
     * @param resourceId    资源ID
     * @param lifeStatus    目标状态
     * @param newVersion    新版本号（null 时由 Handler 按状态递增）
     * @param publishedTime 发布时间（null 时不更新）
     */
    private void updateResourceLifeStatus(ResourceType type, Long resourceId,
                                           AgentLifeStatus lifeStatus,
                                           String newVersion,
                                           LocalDateTime publishedTime) {
        ResourceReviewHandler handler = handlers.get(type);
        if (handler != null) {
            handler.updateLifeStatus(resourceId, lifeStatus, newVersion, publishedTime);
        }
    }

    private ResourceType parseResourceType(String resourceType) {
        if (resourceType == null || resourceType.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "资源类型不能为空");
        }
        // 向后兼容：旧的 "MCP" 类型映射为 "MCP_SERVICE"
        if ("MCP".equalsIgnoreCase(resourceType)) {
            return ResourceType.MCP_SERVICE;
        }
        try {
            return ResourceType.valueOf(resourceType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "无效的资源类型: " + resourceType);
        }
    }
}
