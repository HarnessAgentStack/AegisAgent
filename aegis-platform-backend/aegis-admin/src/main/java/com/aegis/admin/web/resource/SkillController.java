package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.SkillManageService;
import com.aegis.admin.service.resource.SkillReviewService;
import com.aegis.admin.service.resource.SkillVersionService;
import com.aegis.dal.security.SkillSecurityScanner;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.resource.SkillApproveRequest;
import com.aegis.core.dto.resource.SkillCreateRequest;
import com.aegis.core.dto.resource.SkillRollbackRequest;
import com.aegis.core.dto.resource.SkillUpdateRequest;
import com.aegis.core.dto.resource.SkillVersionPublishRequest;
import com.aegis.core.dto.resource.SkillVO;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SkillController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/resource/skill")
@RequiredArgsConstructor
public class SkillController {

    private final SkillManageService skillManageService;
    private final SkillVersionService skillVersionService;
    private final SkillReviewService skillReviewService;

    /**
     * 创建技能（草稿态）。
     */
    @PostMapping
    @Auditable(operation = "CREATE_SKILL", resourceType = "SKILL")
    public Result<Long> create(@Valid @RequestBody SkillCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        if (req.getAuthorUserId() == null) req.setAuthorUserId(userId);
        Long id = skillManageService.create(tenantId, userId, req);
        return Result.success(id);
    }

    /**
     * 更新技能。
     */
    @PutMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SkillUpdateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        skillManageService.update(tenantId, userId, id, req);
        return Result.success(null);
    }

    /**
     * 查询技能详情。
     */
    @GetMapping("/{id}")
    public Result<SkillVO> detail(@PathVariable Long id,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(skillManageService.getDetail(tenantId, userId, id));
    }

    /**
     * 分页查询技能。
     *
     * @param scope   视图范围：mine（本租户）/ market（已发布可订阅）
     * @param keyword 关键词
     * @param type    技能类型（ATOMIC/COMPOSITE）
     */
    @GetMapping("/page")
    public Result<Page<SkillVO>> page(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                     @RequestParam(required = false, defaultValue = "mine") String scope,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(skillManageService.page(tenantId, scope, userId, keyword, type, page, size));
    }

    /**
     * 删除技能。
     */
    @DeleteMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.DELETE, resourceIdParam = "id")
    @Auditable(operation = "DELETE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> delete(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        skillManageService.delete(tenantId, userId, id);
        return Result.success(null);
    }

    /**
     * 提交技能审核发布。
     */
    @PostMapping("/{id}/submit-review")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "SUBMIT_SKILL_REVIEW", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> submitForReview(@PathVariable Long id,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        skillManageService.submitForReview(tenantId, userId, id);
        return Result.success(null);
    }

    /**
     * 触发安全扫描。
     */
    @PostMapping("/{id}/scan")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "SCAN_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<SkillSecurityScanner.ScanResult> scan(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(skillManageService.triggerScan(tenantId, userId, id));
    }

    /**
     * 退回草稿（PUBLISHED/ARCHIVED -> DRAFT，仅作者可操作）。
     */
    @PostMapping("/{id}/revert-draft")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "REVERT_SKILL_TO_DRAFT", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> revertToDraft(@PathVariable Long id,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                      @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        skillManageService.revertToDraft(tenantId, userId, id);
        return Result.success(null);
    }

    /**
     * 归档技能（PUBLISHED -> ARCHIVED，仅作者可操作）。
     */
    @PostMapping("/{id}/archive")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.MANAGE, resourceIdParam = "id")
    @Auditable(operation = "ARCHIVE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> archive(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        skillManageService.archive(tenantId, userId, id);
        return Result.success(null);
    }

    /**
     * 版本指针发布（将当前草稿/指定版本发布为新版本指针）。
     */
    @PostMapping("/{id}/publish")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.PUBLISH, resourceIdParam = "id")
    @Auditable(operation = "PUBLISH_SKILL_VERSION", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> publishVersion(@PathVariable Long id,
                                    @RequestBody SkillVersionPublishRequest req,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        req.setSkillId(id);
        skillVersionService.publish(tenantId, req);
        return Result.success(null);
    }

    /**
     * 版本回滚（激活历史版本为当前版本）。
     */
    @PostMapping("/{id}/rollback")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.PUBLISH, resourceIdParam = "id")
    @Auditable(operation = "ROLLBACK_SKILL_VERSION", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> rollbackVersion(@PathVariable Long id,
                                      @RequestBody SkillRollbackRequest req,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                      @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        req.setSkillId(id);
        skillVersionService.rollback(tenantId, req);
        return Result.success(null);
    }

    /**
     * 灰度发布（按百分比逐步放量新版本）。
     */
    @PostMapping("/{id}/gray-release")
    @ResourceOwner(resourceType = ResourceType.SKILL, permission = ResourcePermission.PUBLISH, resourceIdParam = "id")
    @Auditable(operation = "GRAY_RELEASE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> grayReleaseVersion(@PathVariable Long id,
                                         @RequestBody SkillVersionPublishRequest req,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        String version = req.getTargetVersion();
        Integer percent = req.getGrayPercent() != null ? req.getGrayPercent() : 10;
        skillVersionService.grayRelease(tenantId, id, version, percent);
        return Result.success(null);
    }

    /**
     * 查询版本历史列表。
     */
    @GetMapping("/{id}/versions")
    public Result<List<?>> getVersionHistory(@PathVariable Long id,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(skillVersionService.getVersionHistory(tenantId, id));
    }

    /**
     * 版本差异对比（比较两个版本的关键字段差异）。
     */
    @GetMapping("/{id}/version-diff")
    public Result<Map<String, Object>> getVersionDiff(@PathVariable Long id,
                                                       @RequestParam String versionA,
                                                       @RequestParam String versionB,
                                                       @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(skillVersionService.getVersionDiff(tenantId, id, versionA, versionB));
    }

    /**
     * 审批通过（支持多级审批链）。
     */
    @PostMapping("/{id}/approve")
    @Auditable(operation = "APPROVE_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> approveSkill(@PathVariable Long id,
                                  @RequestBody SkillApproveRequest req,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        req.setSkillId(id);
        req.setApproverUserId(userId);
        Long latestReviewId = skillReviewService.resolveLatestReviewId(id);
        if (latestReviewId != null) {
            req.setReviewId(latestReviewId);
        }
        skillReviewService.approve(tenantId, req);
        return Result.success(null);
    }

    /**
     * 审批驳回。
     */
    @PostMapping("/{id}/reject")
    @Auditable(operation = "REJECT_SKILL", resourceType = "SKILL", resourceIdParam = "id")
    public Result<Void> rejectSkill(@PathVariable Long id,
                                  @RequestBody SkillApproveRequest req,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        req.setSkillId(id);
        req.setApproverUserId(userId);
        Long latestReviewId = skillReviewService.resolveLatestReviewId(id);
        if (latestReviewId != null) {
            req.setReviewId(latestReviewId);
        }
        skillReviewService.reject(tenantId, req);
        return Result.success(null);
    }
}
