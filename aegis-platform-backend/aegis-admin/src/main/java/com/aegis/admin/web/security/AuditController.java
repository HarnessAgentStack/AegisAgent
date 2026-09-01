package com.aegis.admin.web.security;

import com.aegis.admin.service.observe.AuditService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.monitor.AuditLog;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

/**
 * 审计日志 Controller：分页查询、导出、统计。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    /**
     * 分页查询审计日志（全维度 + keyword + sessionId/agentId 结构化查询）。
     */
    @GetMapping("/logs")
    public Result<Page<AuditLog>> listLogs(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String resourceName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(auditService.listLogs(logType, userId, result, operation,
                resourceName, keyword, sessionId, agentId, startTime, endTime, page, size));
    }

    /**
     * 导出审计日志为 CSV（全维度过滤，单次上限 10000 条，CSV 含 username 列）。
     */
    @GetMapping("/logs/export")
    public ResponseEntity<byte[]> exportLogs(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestHeader(value = "X-Username", required = false) String username,
            @RequestParam(required = false) String logType,
            @RequestParam(required = false) Long filterUserId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String resourceName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        TenantContextHolder.bind(tenantId);
        AuditService.AuditExportResult exportResult =
                auditService.exportLogs(tenantId, userId, username, logType, filterUserId,
                        result, operation, resourceName, keyword, startTime, endTime);

        String filename = "audit-logs-" + LocalDate.now() + ".csv";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(exportResult.content());
    }

    /**
     * 审计日志统计（按日志类型聚合 + 安全事件子计数，支持时间范围联动）。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        TenantContextHolder.bind(tenantId);
        return Result.success(auditService.stats(startTime, endTime));
    }
}
