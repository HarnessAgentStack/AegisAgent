package com.aegis.admin.web.sandbox;

import com.aegis.admin.service.sandbox.SandboxInstanceManageService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 沙箱实例管理 Controller（两参数驱动模型）。
 *
 * <p>提供实例的查询、回收（工作区重初始化）、销毁等管理接口。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sandbox/instance")
@RequiredArgsConstructor
public class SandboxInstanceController {

    private final SandboxInstanceManageService instanceManageService;

    /**
     * 分页查询实例。
     */
    @GetMapping("/page")
    public Result<Page<SandboxInstance>> page(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long poolId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long filterTenantId,
            @RequestParam(required = false) String instanceId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(instanceManageService.page(page, size, poolId, status, filterTenantId, instanceId));
    }

    /**
     * 按 instanceId 查询详情。
     */
    @GetMapping("/{instanceId}")
    public Result<SandboxInstance> getByInstanceId(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String instanceId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(instanceManageService.getByInstanceId(instanceId));
    }

    /**
     * 手动回收实例。
     */
    @PostMapping("/{instanceId}/recycle")
    public Result<Void> recycle(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String instanceId) {
        TenantContextHolder.bind(tenantId);
        instanceManageService.recycle(instanceId);
        return Result.success();
    }

    /**
     * 销毁实例（删除 Pod，标记 DESTROYED）。
     */
    @DeleteMapping("/{instanceId}")
    public Result<Void> destroy(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String instanceId) {
        TenantContextHolder.bind(tenantId);
        instanceManageService.destroy(instanceId);
        return Result.success();
    }

    /**
     * 获取实例的 K8s Pod 状态。
     */
    @GetMapping("/{instanceId}/pod-status")
    public Result<Map<String, Object>> getPodStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String instanceId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(instanceManageService.getPodStatus(instanceId));
    }

    /**
     * 统计：按状态分组计数。
     */
    @GetMapping("/stats")
    public Result<Map<String, Long>> countByStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(instanceManageService.countByStatus());
    }
}