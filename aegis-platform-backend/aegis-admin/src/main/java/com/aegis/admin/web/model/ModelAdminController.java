package com.aegis.admin.web.model;

import com.aegis.admin.service.model.ModelManageService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.model.ModelDefCreateRequest;
import com.aegis.core.dto.model.ModelDefUpdateRequest;
import com.aegis.core.dto.model.ModelDefVO;
import com.aegis.core.dto.model.ModelProviderCreateRequest;
import com.aegis.core.dto.model.ModelProviderUpdateRequest;
import com.aegis.core.dto.model.ModelProviderVO;
import com.aegis.core.dto.model.ModelRateLimitSaveRequest;
import com.aegis.core.dto.model.ModelRateLimitVO;
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

import java.util.List;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * ModelAdminController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/model")
@RequiredArgsConstructor
public class ModelAdminController {

    private final ModelManageService modelManageService;

// ============ 供应商管理 ============

    /**
     * 新增模型供应商。
     */
    @PostMapping("/providers")
    public Result<Long> createProvider(@Valid @RequestBody ModelProviderCreateRequest req,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.createProvider(req));
    }

    /**
     * 更新供应商配置。
     */
    @PutMapping("/providers/{id}")
    public Result<Void> updateProvider(@PathVariable Long id, @Valid @RequestBody ModelProviderUpdateRequest req,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        modelManageService.updateProvider(id, req);
        return Result.success(null);
    }

    /**
     * 供应商分页列表。
     */
    @GetMapping("/providers")
    public Result<Page<ModelProviderVO>> pageProviders(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.pageProviders(page, size));
    }

    /**
     * 测试供应商连接可达性。
     */
    @PostMapping("/providers/{id}/test")
    public Result<Boolean> testProvider(@PathVariable Long id,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.testProviderConnection(id));
    }

    // ============ 模型定义管理 ============

    /**
     * 新增模型定义。
     */
    @PostMapping("/defs")
    public Result<Long> createModel(@Valid @RequestBody ModelDefCreateRequest req,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.createModel(req));
    }

    /**
     * 更新模型定义。
     */
    @PutMapping("/defs/{id}")
    public Result<Void> updateModel(@PathVariable Long id, @Valid @RequestBody ModelDefUpdateRequest req,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        modelManageService.updateModel(id, req);
        return Result.success(null);
    }

    /**
     * 模型列表（按供应商与档位过滤）。
     */
    @GetMapping("/defs")
    public Result<List<ModelDefVO>> listModels(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                              @RequestParam(required = false) Long providerId,
                                              @RequestParam(required = false) String tier) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.listModels(providerId, tier));
    }

    // ============ 限流策略管理 ============

    /**
     * 配置限流策略（id 存在更新，否则新增）。
     */
    @PutMapping("/rate-limits")
    public Result<Void> saveRateLimit(@Valid @RequestBody ModelRateLimitSaveRequest req,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        modelManageService.saveRateLimit(req);
        return Result.success(null);
    }

    /**
     * 限流策略列表。
     */
    @GetMapping("/rate-limits")
    public Result<List<ModelRateLimitVO>> listRateLimits(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(modelManageService.listRateLimits(tenantId));
    }
}
