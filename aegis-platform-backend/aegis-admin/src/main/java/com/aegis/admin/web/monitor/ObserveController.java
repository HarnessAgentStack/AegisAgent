package com.aegis.admin.web.monitor;

import com.aegis.core.common.web.PageRequest;
import com.aegis.core.common.web.PageResult;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.observe.*;
import com.aegis.core.spi.TraceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/admin/observe")
@RequiredArgsConstructor
public class ObserveController {

    private final TraceStore traceStore;

    @GetMapping("/traces")
    public Result<PageResult<TraceRecord>> listTraces(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);

        TraceQuery query = TraceQuery.builder()
                .sessionId(sessionId)
                .userId(userId)
                .agentId(agentId)
                .traceId(traceId)
                .status(status)
                .startTime(startTime)
                .endTime(endTime)
                .page(page)
                .size(size)
                .build();

        return Result.success(traceStore.queryTraces(query));
    }

    @GetMapping("/traces/{traceId}")
    public Result<TraceDetail> getTraceDetail(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String traceId) {
        TenantContextHolder.bind(tenantId);
        TraceDetail detail = traceStore.getTraceDetail(traceId);
        if (detail == null) {
            return Result.success(null);
        }
        return Result.success(detail);
    }

    @GetMapping("/sessions")
    public Result<PageResult<SessionSummary>> listSessions(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(page);
        pageRequest.setPageSize(size);
        return Result.success(traceStore.querySessions(pageRequest));
    }

    @GetMapping("/sessions/{sessionId}/traces")
    public Result<PageResult<TraceRecord>> getSessionTraces(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(page);
        pageRequest.setPageSize(size);
        return Result.success(traceStore.queryBySession(sessionId, pageRequest));
    }

    /**
     * 会话详情（以 Session 为根节点的聚合视图）。
     *
     * <p>返回会话级概览信息和所有轮次的详细步骤。</p>
     */
    @GetMapping("/sessions/{sessionId}/detail")
    public Result<SessionDetailResponse> getSessionDetail(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable String sessionId) {
        TenantContextHolder.bind(tenantId);
        SessionDetailResponse detail = traceStore.getSessionDetail(sessionId);
        if (detail == null) {
            return Result.success(null);
        }
        return Result.success(detail);
    }

    @GetMapping("/users/{userId}/traces")
    public Result<PageResult<TraceRecord>> getUserTraces(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(page);
        pageRequest.setPageSize(size);
        return Result.success(traceStore.queryByUser(userId, pageRequest));
    }

    @GetMapping("/agents/{agentId}/traces")
    public Result<PageResult<TraceRecord>> getAgentTraces(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @PathVariable Long agentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPageNum(page);
        pageRequest.setPageSize(size);
        return Result.success(traceStore.queryByAgent(agentId, pageRequest));
    }

    @GetMapping("/stats")
    public Result<ObserveStats> getStats(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String scopeValue,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime endTime) {
        TenantContextHolder.bind(tenantId);

        StatsQuery query = StatsQuery.builder()
                .scope(scope)
                .scopeValue(scopeValue)
                .startTime(startTime)
                .endTime(endTime)
                .build();

        return Result.success(traceStore.stats(query));
    }
}