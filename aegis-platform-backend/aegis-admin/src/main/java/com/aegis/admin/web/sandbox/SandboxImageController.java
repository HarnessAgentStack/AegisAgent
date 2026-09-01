package com.aegis.admin.web.sandbox;

import com.aegis.admin.service.sandbox.SandboxBaseImageService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
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

/**
 * 沙箱基础镜像管理 Controller。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/sandbox/image")
@RequiredArgsConstructor
public class SandboxImageController {

    private final SandboxBaseImageService baseImageService;

    /**
     * 分页查询镜像。
     */
    @GetMapping("/page")
    public Result<Page<SandboxBaseImage>> page(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String imageCode,
            @RequestParam(required = false) String imageName,
            @RequestParam(required = false) String status) {
        TenantContextHolder.bind(tenantId);
        return Result.success(baseImageService.page(page, size, imageCode, imageName, status));
    }

    /**
     * 列出所有启用的镜像。
     */
    @GetMapping("/list")
    public Result<List<SandboxBaseImage>> listEnabled(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(baseImageService.listEnabled());
    }

    /**
     * 按 ID 查询镜像详情。
     */
    @GetMapping("/{id}")
    public Result<SandboxBaseImage> getById(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id) {
        TenantContextHolder.bind(tenantId);
        return Result.success(baseImageService.getById(id));
    }

    /**
     * 新建镜像。
     */
    @PostMapping
    public Result<SandboxBaseImage> create(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @Valid @RequestBody SandboxBaseImage image) {
        TenantContextHolder.bind(tenantId);
        return Result.success(baseImageService.create(image));
    }

    /**
     * 更新镜像。
     */
    @PutMapping
    public Result<SandboxBaseImage> update(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @Valid @RequestBody SandboxBaseImage image) {
        TenantContextHolder.bind(tenantId);
        return Result.success(baseImageService.update(image));
    }

    /**
     * 启用/停用镜像。
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id,
            @RequestParam String status) {
        TenantContextHolder.bind(tenantId);
        baseImageService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 删除镜像。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long id) {
        TenantContextHolder.bind(tenantId);
        baseImageService.delete(id);
        return Result.success();
    }
}