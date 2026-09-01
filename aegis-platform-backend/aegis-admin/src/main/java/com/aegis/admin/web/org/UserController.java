package com.aegis.admin.web.org;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.org.UserService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.org.User;
import com.aegis.core.dto.org.UserCreateRequest;
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
import com.aegis.core.domain.tenant.Tenant;

/**
 * UserController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

/**
     * 分页查询用户。
     */
    @GetMapping("/page")
    public Result<Page<User>> page(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long deptId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(userService.page(tenantId, keyword, deptId, page, size));
    }

    /**
     * 查询用户详情。
     */
    @GetMapping("/{id}")
    public Result<User> detail(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(userService.detail(tenantId, id));
    }

    /**
     * 创建用户（BCrypt 加密密码）。
     */
    @PostMapping
    @Auditable(operation = "CREATE_USER", resourceType = "USER")
    public Result<Long> create(@Valid @RequestBody UserCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        if (req.getTenantId() == null) req.setTenantId(tenantId);
        Long id = userService.create(req);
        return Result.success(id);
    }

    /**
     * 更新用户。
     */
    @PutMapping("/{id}")
    @Auditable(operation = "UPDATE_USER", resourceType = "USER", resourceIdParam = "id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody User user,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        user.setId(id);
        if (user.getTenantId() == null) user.setTenantId(tenantId);
        userService.update(user);
        return Result.success(null);
    }

    /**
     * 分配角色（先删后增）。
     */
    @PostMapping("/{id}/roles")
    @Auditable(operation = "ASSIGN_USER_ROLES", resourceType = "USER", resourceIdParam = "id")
    public Result<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody List<Long> roleIds,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        userService.assignRoles(tenantId, id, roleIds);
        return Result.success(null);
    }

    /**
     * 禁用用户。
     */
    @PostMapping("/{id}/disable")
    @Auditable(operation = "DISABLE_USER", resourceType = "USER", resourceIdParam = "id")
    public Result<Void> disable(@PathVariable Long id,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        userService.disable(tenantId, id);
        return Result.success(null);
    }

    /**
     * 启用用户。
     */
    @PostMapping("/{id}/enable")
    @Auditable(operation = "ENABLE_USER", resourceType = "USER", resourceIdParam = "id")
    public Result<Void> enable(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        userService.enable(tenantId, id);
        return Result.success(null);
    }
}
