package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.ResourceReviewMapper;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.enums.common.ReviewStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.aegis.admin.service.resource.ReviewProcessEngine;

/**
 * 审核查询领域服务。
 *
 * <p>提供审核单的待审核列表查询与我的提交列表查询能力。
 * 审核提交、通过、驳回由 {@link com.aegis.admin.service.resource.ReviewProcessEngine} 负责。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewQueryService {

    private final ResourceReviewMapper resourceReviewMapper;

    /**
     * 待审核列表（支持关键词搜索 + 真实分页）。
     *
     * @param keyword      关键词（资源名称 / 申请人姓名 模糊匹配，可空）
     * @param resourceType 资源类型过滤（可空）
     * @param page         页码
     * @param size         每页条数
     * @return 待审核审核单分页结果
     */
    public Page<ResourceReview> pending(String keyword, String resourceType, int page, int size) {
        Page<ResourceReview> pageObj = new Page<>(page, size);
        // 安全裁剪：先判空再 trim，避免 Java 方法参数 eagerly 求值导致 keyword.trim() NPE
        String trimmedKeyword = (keyword != null) ? keyword.trim() : "";
        LambdaQueryWrapper<ResourceReview> wrapper = new LambdaQueryWrapper<ResourceReview>()
                .eq(ResourceReview::getReviewStatus, ReviewStatus.PENDING)
                .eq(resourceType != null && !resourceType.isEmpty(),
                        ResourceReview::getResourceType, parseResourceType(resourceType))
                // 关键词模糊搜索（资源名称）
                // 注：申请人姓名暂未冗余到 res_review 表，仅支持按资源名称搜索
                .like(!trimmedKeyword.isEmpty(),
                        ResourceReview::getResourceName, trimmedKeyword)
                .orderByDesc(ResourceReview::getSubmitTime);
        return resourceReviewMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 我的提交列表。
     *
     * @param userId 申请人ID
     * @param page   页码
     * @param size   每页条数
     * @return 我的审核单分页结果
     */
    public Page<ResourceReview> mine(Long userId, int page, int size) {
        Page<ResourceReview> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ResourceReview> wrapper = new LambdaQueryWrapper<ResourceReview>()
                .eq(userId != null, ResourceReview::getApplicantUserId, userId)
                .orderByDesc(ResourceReview::getSubmitTime);
        return resourceReviewMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 全部审核列表（管理员全量视图）。
     *
     * @param resourceType 资源类型过滤（可空）
     * @param reviewStatus 审核状态过滤（可空）
     * @param page         页码
     * @param size         每页条数
     * @return 审核单分页结果
     */
    public Page<ResourceReview> all(String resourceType, String reviewStatus, int page, int size) {
        Page<ResourceReview> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<ResourceReview> wrapper = new LambdaQueryWrapper<ResourceReview>()
                .eq(resourceType != null && !resourceType.isEmpty(),
                        ResourceReview::getResourceType, parseResourceType(resourceType))
                .eq(reviewStatus != null && !reviewStatus.isEmpty(),
                        ResourceReview::getReviewStatus, parseReviewStatus(reviewStatus))
                .orderByDesc(ResourceReview::getSubmitTime);
        return resourceReviewMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 解析资源类型字符串为枚举。
     *
     * @param resourceType 资源类型字符串
     * @return 资源类型枚举，无效时返回 null
     */
    private ResourceType parseResourceType(String resourceType) {
        if (resourceType == null || resourceType.isEmpty()) {
            return null;
        }
        // 向后兼容：旧的 "MCP" 类型映射为 "MCP_SERVICE"
        if ("MCP".equalsIgnoreCase(resourceType)) {
            return ResourceType.MCP_SERVICE;
        }
        try {
            return ResourceType.valueOf(resourceType.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 解析审核状态字符串为枚举。
     *
     * @param reviewStatus 审核状态字符串
     * @return 审核状态枚举，无效时返回 null
     */
    private ReviewStatus parseReviewStatus(String reviewStatus) {
        if (reviewStatus == null || reviewStatus.isEmpty()) {
            return null;
        }
        try {
            return ReviewStatus.valueOf(reviewStatus.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
