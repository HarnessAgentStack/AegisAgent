package com.aegis.admin.web.org;

import com.aegis.admin.service.org.DepartmentService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.org.Department;
import com.aegis.core.dto.org.DepartmentCreateRequest;
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
 * DepartmentController。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/department")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

/**
     * 查询部门树（扁平列表）。
     */
    @GetMapping("/tree")
    public Result<List<Department>> tree(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(departmentService.tree(tenantId));
    }

    /**
     * 创建部门。
     */
    @PostMapping
    public Result<Long> create(@Valid @RequestBody DepartmentCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        Department dept = Department.builder()
                .deptName(req.getDeptName())
                .parentId(req.getParentId())
                .sort(req.getSort())
                .leaderUserId(req.getLeaderUserId())
                .build();
        if (req.getTenantId() != null) {
            dept.setTenantId(req.getTenantId());
        } else {
            dept.setTenantId(tenantId);
        }
        Long id = departmentService.create(dept);
        return Result.success(id);
    }

    /**
     * 更新部门。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Department dept,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        dept.setId(id);
        if (dept.getTenantId() == null) dept.setTenantId(tenantId);
        departmentService.update(dept);
        return Result.success(null);
    }

    /**
     * 删除部门（校验无子部门、无关联用户）。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        departmentService.delete(tenantId, id);
        return Result.success(null);
    }
}
