package com.aegis.admin.web.ha;

import com.aegis.admin.service.observe.BackupService;
import com.aegis.admin.service.observe.HealthCheckService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.monitor.BackupRecord;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HaAdminController。
 *
 * <p>提供组件健康检查与备份的执行/历史查询。备份连接参数由
 * {@code aegis.ha.backup.*} 配置项注入，见 {@link BackupService}。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/ha")
@RequiredArgsConstructor
public class HaAdminController {

    private final HealthCheckService healthCheckService;
    private final BackupService backupService;

    // ==================== 健康检查 ====================

    /**
     * 全组件健康检查（runtime / admin / gateway / mysql / redis / nacos）。
     */
    @GetMapping("/health")
    public Result<Map<String, Object>> health(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(healthCheckService.checkAll());
    }

    // ==================== 备份管理 ====================

    /**
     * 备份历史查询。
     */
    @GetMapping("/backup/list")
    public Result<Page<BackupRecord>> listBackup(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(backupService.list(page, size));
    }

    /**
     * 手动触发备份（mysqldump 全量备份）。
     */
    @PostMapping("/backup/execute")
    public Result<BackupRecord> executeBackup(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(backupService.execute());
    }
}
