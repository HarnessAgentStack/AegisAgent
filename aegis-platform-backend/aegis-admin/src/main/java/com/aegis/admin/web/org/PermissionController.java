package com.aegis.admin.web.org;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.org.RolePermissionService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.domain.org.Permission;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理 Controller：权限树查询 + 角色权限分配。
 *
 * <p>支撑数据驱动的细粒度 RBAC，前端角色页通过权限树勾选为角色配置权限。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final RolePermissionService rolePermissionService;

    /**
     * 查询全部权限（扁平列表，前端组装树）。
     */
    @GetMapping("/tree")
    public Result<List<Permission>> tree(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(rolePermissionService.listPermissions(tenantId));
    }

    /**
     * 查询角色已分配的权限ID列表。
     */
    @GetMapping("/role/{roleId}")
    public Result<List<Long>> getRolePermissions(
            @PathVariable Long roleId,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(rolePermissionService.getRolePermissionIds(tenantId, roleId));
    }

    /**
     * 分配角色权限（先删后增）。
     */
    @PutMapping("/role/{roleId}")
    @Auditable(operation = "ASSIGN_ROLE_PERMISSIONS", resourceType = "ROLE", resourceIdParam = "roleId")
    public Result<Void> assignRolePermissions(
            @PathVariable Long roleId,
            @RequestBody AssignPermissionsRequest req,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        rolePermissionService.assignRolePermissions(tenantId, roleId, req.getPermissionIds());
        return Result.success(null);
    }

    @Data
    public static class AssignPermissionsRequest {
        private List<Long> permissionIds;
    }
}
