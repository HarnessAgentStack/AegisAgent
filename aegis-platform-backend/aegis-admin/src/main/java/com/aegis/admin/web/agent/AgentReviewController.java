package com.aegis.admin.web.agent;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.agent.AgentPublishService;
import com.aegis.admin.service.agent.AgentVersionService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.resource.ResourceReview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体审核与版本管理 Controller。
 *
 * <p>负责审核流程（提交/通过/驳回/历史）与版本管理（列表/详情/Diff）端点。
 * 父路径与 {@link AgentAdminController} 共享 {@code /api/admin/agent}。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent")
@RequiredArgsConstructor
public class AgentReviewController {

    private final AgentPublishService agentPublishService;
    private final AgentVersionService agentVersionService;

    /**
     * 提交审核（DRAFT/ACTIVE/REJECTED -> REVIEWING）。
     */
    @PostMapping("/{id}/submit-review")
    @Auditable(operation = "SUBMIT_AGENT_REVIEW", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Long> submitReview(@PathVariable Long id,
                                      @RequestHeader("X-Tenant-Id") Long tenantId,
                                      @RequestHeader("X-User-Id") Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.submitReview(tenantId, id, userId));
    }

    /**
     * 审核通过（REVIEWING -> PUBLISHED）。
     */
    @PostMapping("/{id}/approve")
    @Auditable(operation = "APPROVE_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> approve(@PathVariable Long id,
                                 @RequestHeader("X-Tenant-Id") Long tenantId,
                                 @RequestHeader("X-User-Id") Long approverId,
                                 @RequestParam(required = false) Long reviewId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.approveReview(id, approverId, reviewId);
        return Result.success(null);
    }

    /**
     * 审核驳回（REVIEWING -> REJECTED）。
     */
    @PostMapping("/{id}/reject")
    @Auditable(operation = "REJECT_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> reject(@PathVariable Long id,
                                @RequestHeader("X-Tenant-Id") Long tenantId,
                                @RequestHeader("X-User-Id") Long approverId,
                                @RequestParam String reason,
                                @RequestParam(required = false) Long reviewId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.rejectReview(id, approverId, reason, reviewId);
        return Result.success(null);
    }

    /**
     * 查询智能体审核历史。
     */
    @GetMapping("/{id}/reviews")
    public Result<List<ResourceReview>> listReviews(@PathVariable Long id,
                                                     @RequestHeader("X-Tenant-Id") Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.listReviews(id));
    }

    /**
     * 查询智能体版本历史。
     */
    @GetMapping("/{id}/versions")
    public Result<List<AgentConfig>> listVersions(@PathVariable Long id,
                                                   @RequestHeader("X-Tenant-Id") Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentVersionService.getVersionHistory(tenantId, id));
    }

    /**
     * 查询智能体指定版本配置。
     */
    @GetMapping("/{id}/versions/{version}")
    public Result<AgentConfig> getVersionConfig(@PathVariable Long id,
                                                 @PathVariable String version,
                                                 @RequestHeader("X-Tenant-Id") Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.getConfig(tenantId, id, version));
    }

    /**
     * 比较两个版本配置差异。
     */
    @GetMapping("/{id}/versions/{v1}/diff/{v2}")
    public Result<List<Map<String, Object>>> versionDiff(@PathVariable Long id,
                                                          @PathVariable String v1,
                                                          @PathVariable String v2,
                                                          @RequestHeader("X-Tenant-Id") Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentVersionService.versionDiff(id, v1, v2));
    }
}
