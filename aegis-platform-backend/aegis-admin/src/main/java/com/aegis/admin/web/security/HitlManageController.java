package com.aegis.admin.web.security;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.security.HitlService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.security.HitlHistoryVO;
import com.aegis.core.dto.security.HitlNodeCreateRequest;
import com.aegis.core.dto.security.HitlNodeUpdateRequest;
import com.aegis.core.dto.security.HitlNodeVO;
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
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * HITL 管理接口。
 *
 * <p>提供人工介入（Human-In-The-Loop）节点管理、审批工单处理与审批历史查询。
 * 支撑平台高风险操作的人工审批流程：节点配置、工单审批/驳回、审批追溯。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/hitl")
@RequiredArgsConstructor
public class HitlManageController {

    private final HitlService hitlService;

    // ==================== HITL 节点 ====================

    @GetMapping("/nodes")
    public Result<Page<HitlNodeVO>> listNodes(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(hitlService.listNodes(agentId, enabled, page, size));
    }

    @PostMapping("/nodes")
    @Auditable(operation = "CREATE_HITL_NODE", resourceType = "HITL_NODE")
    public Result<HitlNodeVO> createNode(@Valid @RequestBody HitlNodeCreateRequest req,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(hitlService.createNode(req, tenantId));
    }

    @PutMapping("/nodes/{id}")
    @Auditable(operation = "UPDATE_HITL_NODE", resourceType = "HITL_NODE", resourceIdParam = "id")
    public Result<Void> updateNode(@PathVariable Long id, @Valid @RequestBody HitlNodeUpdateRequest req,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        hitlService.updateNode(id, req, tenantId);
        return Result.success(null);
    }

    @DeleteMapping("/nodes/{id}")
    @Auditable(operation = "DELETE_HITL_NODE", resourceType = "HITL_NODE", resourceIdParam = "id")
    public Result<Void> deleteNode(@PathVariable Long id,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        hitlService.deleteNode(id, tenantId);
        return Result.success(null);
    }

    /**
     * 强制刷新指定 Agent 的 HITL 规则缓存。
     *
     * <p>当管理端修改 HITL 节点后如遇异常，可手动调用此接口强制触发刷新。
     *
     * @param agentId   智能体 ID
     * @param tenantId  租户 ID（从 Header 获取）
     */
    @PostMapping("/force-reload")
    @Auditable(operation = "FORCE_RELOAD_HITL_RULES", resourceType = "HITL")
    public Result<Void> forceReload(
            @RequestParam Long agentId,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        hitlService.forceReloadHitlRules(agentId, tenantId);
        return Result.success(null);
    }

    // ==================== 审批历史 ====================

    @GetMapping("/history")
    public Result<Page<HitlHistoryVO>> listHistory(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) Long nodeId,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(hitlService.listHistory(nodeId, agentId, action, page, size));
    }
}
