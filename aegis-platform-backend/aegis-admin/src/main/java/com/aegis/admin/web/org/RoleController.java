package com.aegis.admin.web.org;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.org.RoleService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.org.Role;
import com.aegis.core.dto.org.RoleCreateRequest;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.aegis.core.domain.tenant.Tenant;

/**
 * RoleController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

/**
     * 查询角色列表。
     */
    @GetMapping("/list")
    public Result<List<Role>> list(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(roleService.list(tenantId));
    }

    /**
     * 创建角色（仅资源角色可创建）。
     */
    @PostMapping
    @Auditable(operation = "CREATE_ROLE", resourceType = "ROLE")
    public Result<Long> create(@Valid @RequestBody RoleCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        Role role = Role.builder()
                .roleCode(req.getRoleCode())
                .roleName(req.getRoleName())
                .roleType(req.getRoleType())
                .description(req.getDescription())
                .sort(req.getSort())
                .build();
        if (req.getTenantId() != null) {
            role.setTenantId(req.getTenantId());
        } else {
            role.setTenantId(tenantId);
        }
        Long id = roleService.create(role);
        return Result.success(id);
    }

    /**
     * 更新角色。
     */
    @PutMapping("/{id}")
    @Auditable(operation = "UPDATE_ROLE", resourceType = "ROLE", resourceIdParam = "id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Role role,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        role.setId(id);
        if (role.getTenantId() == null) role.setTenantId(tenantId);
        roleService.update(role);
        return Result.success(null);
    }

    /**
     * 删除角色（校验无关联用户、无子角色）。
     */
    @DeleteMapping("/{id}")
    @Auditable(operation = "DELETE_ROLE", resourceType = "ROLE", resourceIdParam = "id")
    public Result<Void> delete(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        roleService.delete(tenantId, id);
        return Result.success(null);
    }
}
