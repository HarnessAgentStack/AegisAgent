package com.aegis.admin.web.tenant;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.tenant.TenantManageService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.tenant.TenantCreateRequest;
import com.aegis.core.dto.tenant.TenantQuotaUpdateRequest;
import com.aegis.core.dto.tenant.TenantUpdateRequest;
import com.aegis.core.dto.tenant.TenantUsageVO;
import com.aegis.core.dto.tenant.TenantVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.aegis.core.domain.tenant.Tenant;

/**
 * TenantAdminController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/tenant")
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantManageService tenantManageService;

/**
     * 分页查询租户。
     */
    @GetMapping("/page")
    public Result<Page<TenantVO>> page(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(tenantManageService.page(keyword, status, page, size));
    }

    /**
     * 查询租户详情。
     */
    @GetMapping("/{id}")
    public Result<TenantVO> detail(@PathVariable Long id,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(tenantManageService.detail(id));
    }

    /**
     * 创建租户（平台管理员）。
     */
    @PostMapping
    @Auditable(operation = "CREATE_TENANT", resourceType = "TENANT")
    public Result<Long> create(@Valid @RequestBody TenantCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        Long id = tenantManageService.create(req);
        return Result.success(id);
    }

    /**
     * 更新租户信息。
     */
    @PutMapping("/{id}")
    @Auditable(operation = "UPDATE_TENANT", resourceType = "TENANT", resourceIdParam = "id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TenantUpdateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        tenantManageService.update(id, req);
        return Result.success(null);
    }

    /**
     * 调整租户配额。
     */
    @PutMapping("/{id}/quota")
    @Auditable(operation = "UPDATE_TENANT_QUOTA", resourceType = "TENANT", resourceIdParam = "id")
    public Result<Void> updateQuota(@PathVariable Long id, @Valid @RequestBody TenantQuotaUpdateRequest req,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        tenantManageService.updateQuota(id, req);
        return Result.success(null);
    }

    /**
     * 冻结租户。
     */
    @PostMapping("/{id}/freeze")
    @Auditable(operation = "FREEZE_TENANT", resourceType = "TENANT", resourceIdParam = "id")
    public Result<Void> freeze(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        tenantManageService.freeze(id);
        return Result.success(null);
    }

    /**
     * 解冻租户。
     */
    @PostMapping("/{id}/unfreeze")
    @Auditable(operation = "UNFREEZE_TENANT", resourceType = "TENANT", resourceIdParam = "id")
    public Result<Void> unfreeze(@PathVariable Long id,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        tenantManageService.unfreeze(id);
        return Result.success(null);
    }

    /**
     * 查询租户用量（计量）。
     */
    @GetMapping("/{id}/usage")
    public Result<TenantUsageVO> usage(@PathVariable Long id,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(tenantManageService.getUsage(id));
    }
}
