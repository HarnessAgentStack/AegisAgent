package com.aegis.admin.web.sandbox;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.sandbox.SandboxPoolManageService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.List;
import java.util.Map;

/**
 * 沙箱池管理 Controller（两参数驱动模型）。
 *
 * <p>提供池的 CRUD、状态管理、K8s 状态查询、手动修复等接口。
 * 预热和回收由 Reconcile 循环自动执行，无需手动触发。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sandbox/pool")
@RequiredArgsConstructor
public class SandboxPoolController {

    private final SandboxPoolManageService poolManageService;

    /**
     * 分页查询池。
     */
    @GetMapping("/page")
    public Result<Page<SandboxPool>> page(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String poolName,
            @RequestParam(required = false) String poolType,
            @RequestParam(required = false) String status) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.page(page, size, poolName, poolType, status));
    }

    /**
     * 列出所有启用的池。
     */
    @GetMapping("/list")
    public Result<List<SandboxPool>> listEnabled(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.listEnabled());
    }

    /**
     * 按 ID 查询池详情。
     */
    @GetMapping("/{id}")
    public Result<SandboxPool> getById(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.getById(id));
    }

    /**
     * 新建池（K8s 资源预检查 + 创建 K8s 资源 + 写入 DB）。
     */
    @PostMapping
    @Auditable(operation = "CREATE_SANDBOX_POOL", resourceType = "SANDBOX_POOL")
    public Result<SandboxPool> create(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @Valid @RequestBody SandboxPool pool) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.create(pool));
    }

    /**
     * 更新池配置（min/max/idle_timeout 可调，同步 K8s Quota/NetworkPolicy）。
     */
    @PutMapping
    @Auditable(operation = "UPDATE_SANDBOX_POOL", resourceType = "SANDBOX_POOL")
    public Result<SandboxPool> update(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @Valid @RequestBody SandboxPool pool) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.update(pool));
    }

    /**
     * 启用/停用/维护池。
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id,
            @RequestParam String status) {
        TenantContextHolder.bind(tenantId);
        poolManageService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 删除池（校验无 OCCUPIED 实例 → 销毁 Pod → 清理 K8s → 逻辑删除 DB）。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id) {
        TenantContextHolder.bind(tenantId);
        poolManageService.delete(id);
        return Result.success();
    }

    /**
     * 查询池的 K8s 资源状态（Pod 列表 + 命名空间 + 实例统计）。
     */
    @GetMapping("/{id}/k8s-status")
    public Result<Map<String, Object>> getK8sStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.getK8sStatus(id));
    }

    /**
     * 手动修复池 K8s 资源（重建 Namespace + ResourceQuota + NetworkPolicy）。
     */
    @PostMapping("/{id}/repair")
    public Result<Map<String, Object>> repairK8s(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id) {
        TenantContextHolder.bind(tenantId);
        return Result.success(poolManageService.repairPoolK8s(id));
    }
}