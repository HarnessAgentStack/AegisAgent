package com.aegis.admin.web.review;

import com.aegis.admin.service.resource.ReviewQueryService;
import com.aegis.admin.service.agent.AgentPublishService;
import com.aegis.admin.service.resource.ReviewProcessEngine;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.enums.resource.ResourceType;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * ReviewController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewProcessEngine reviewProcessEngine;
    private final ReviewQueryService reviewQueryService;
    private final AgentPublishService agentPublishService;

/**
     * 提交审核。
     *
     * @param body     请求体，包含 resourceType（SKILL/KNOWLEDGE_BASE）与 resourceId
     * @param tenantId 租户ID
     * @return 审核单ID
     */
    @PostMapping("/submit")
    public Result<Long> submit(@Valid @RequestBody Map<String, Object> body,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        String resourceType = (String) body.get("resourceType");
        Object resourceIdObj = body.get("resourceId");
        if (resourceType == null || resourceType.isEmpty()) {
            return Result.fail(ResultCode.PARAM_ERROR, "资源类型不能为空");
        }
        if (resourceIdObj == null) {
            return Result.fail(ResultCode.PARAM_ERROR, "资源ID不能为空");
        }
        Long resourceId = Long.parseLong(resourceIdObj.toString());
        Long reviewId = reviewProcessEngine.submit(tenantId, resourceType, resourceId);
        return Result.success(reviewId);
    }

    /**
     * 审核通过。
     *
     * @param id        审核单ID
     * @param approverId 审批人ID（X-User-Id 头）
     */
    @PostMapping("/{id}/approve")
    public Result<Void> approve(@PathVariable Long id,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) Long approverId) {
        TenantContextHolder.bind(tenantId);
        // 审核通过并返回审核单对象（含资源类型和资源ID）
        ResourceReview review = reviewProcessEngine.approve(id, approverId);
        // BUG-1 修复：通用审核路径需对 AGENT 类型触发 SYSTEM 特定副作用
        // （沙箱池匹配 + API 启用 + API Key 生成），确保与 AgentReviewController 路径行为一致
        if (review.getResourceType() == ResourceType.AGENT) {
            agentPublishService.handlePostApprovalSystemEffects(review.getResourceId());
        }
        return Result.success(null);
    }

    /**
     * 审核驳回。
     *
     * @param id        审核单ID
     * @param body      请求体，包含 reason（驳回原因）
     * @param approverId 审批人ID（X-User-Id 头）
     */
    @PostMapping("/{id}/reject")
    public Result<Void> reject(@PathVariable Long id,
                                @Valid @RequestBody Map<String, Object> body,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long approverId) {
        TenantContextHolder.bind(tenantId);
        String reason = body == null ? null : (String) body.get("reason");
        reviewProcessEngine.reject(id, approverId, reason);
        return Result.success(null);
    }

    /**
     * 待审核列表。
     *
     * @param resourceType 资源类型过滤（可空）
     * @param page         页码
     * @param size         每页条数
     */
    @GetMapping("/pending")
    public Result<Page<ResourceReview>> pending(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String resourceType,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(reviewQueryService.pending(keyword, resourceType, page, size));
    }

    /**
     * 我的提交列表。
     *
     * @param userId 申请人ID（X-User-Id 头）
     * @param page   页码
     * @param size   每页条数
     */
    @GetMapping("/mine")
    public Result<Page<ResourceReview>> mine(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                              @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(reviewQueryService.mine(userId, page, size));
    }

    /**
     * 全部审核列表（管理员全量视图，支持按资源类型和审核状态过滤）。
     *
     * @param resourceType 资源类型过滤（可空：SKILL / KNOWLEDGE_BASE / MCP_SERVICE / AGENT）
     * @param reviewStatus 审核状态过滤（可空：PENDING / APPROVED / REJECTED）
     * @param page         页码
     * @param size         每页条数
     */
    @GetMapping("/all")
    public Result<Page<ResourceReview>> all(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                             @RequestParam(required = false) String resourceType,
                                             @RequestParam(required = false) String reviewStatus,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(reviewQueryService.all(resourceType, reviewStatus, page, size));
    }
}
