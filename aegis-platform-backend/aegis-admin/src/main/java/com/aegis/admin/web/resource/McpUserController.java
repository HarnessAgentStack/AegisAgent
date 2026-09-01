package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.McpManageService;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.dto.resource.McpServiceVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 用户侧 Controller。
 *
 * <p>面向终端用户的 MCP 服务市场、订阅（即订即用）接口，
 * 路径前缀 {@code /api/resource/mcp}，与管理侧 {@code /api/admin/resource/mcp} 对应。
 *
 * @author wang.zhen
 * @see McpManageService
 * @see com.aegis.core.domain.resource.McpService
 */
@Slf4j
@RestController
@RequestMapping("/api/resource/mcp")
@RequiredArgsConstructor
public class McpUserController {

    private final McpManageService mcpManageService;

    // ============ MCP 服务市场 ============

    /**
     * 服务市场（仅展示已审核发布 PUBLISHED 的 MCP 服务）。
     *
     * @param keyword 关键字搜索（可选）
     * @param page    页码
     * @param size    每页条数
     * @return 服务分页列表
     */
    @GetMapping("/market")
    public Result<Page<McpServiceVO>> market(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        // 租户上下文由租户插件自动处理
        TenantContextHolder.bind(tenantId);
        Page<McpServiceVO> result = mcpManageService.pageMarketServices(tenantId, userId, keyword, page, size);
        return Result.success(result);
    }

    // ============ 订阅操作（即订即用） ============

    /**
     * 订阅 MCP 服务（即订即用，无需审核）。
     *
     * @param id       MCP 服务ID
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @return 订阅结果
     */
    @PostMapping("/subscribe/{id}")
    @Auditable(operation = "SUBSCRIBE_MCP_SERVICE", resourceType = "MCP_SERVICE", resourceIdParam = "id")
    public Result<Map<String, Object>> subscribe(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (tenantId == null) {
            throw new BusinessException(com.aegis.core.common.web.ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (userId == null) {
            throw new BusinessException(com.aegis.core.common.web.ResultCode.PARAM_ERROR, "用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);
        mcpManageService.subscribeService(tenantId, userId, id);
        Map<String, Object> result = new HashMap<>(4);
        result.put("serviceId", id);
        result.put("subscribed", true);
        return Result.success(result);
    }

    /**
     * 取消订阅 MCP 服务。
     *
     * @param id       MCP 服务ID
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @return 取消结果
     */
    @DeleteMapping("/subscribe/{id}")
    @Auditable(operation = "UNSUBSCRIBE_MCP_SERVICE", resourceType = "MCP_SERVICE", resourceIdParam = "id")
    public Result<Map<String, Object>> unsubscribe(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        if (tenantId == null || userId == null) {
            throw new BusinessException(com.aegis.core.common.web.ResultCode.PARAM_ERROR, "租户ID或用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);
        mcpManageService.unsubscribeService(tenantId, userId, id);
        Map<String, Object> result = new HashMap<>(2);
        result.put("serviceId", id);
        result.put("unsubscribed", true);
        return Result.success(result);
    }

    /**
     * 查询当前用户对 MCP 服务的订阅状态。
     *
     * @param id       MCP 服务ID
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @return 订阅状态
     */
    @GetMapping("/subscribe/{id}/status")
    public Result<Map<String, Object>> subscribeStatus(
            @PathVariable Long id,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        boolean subscribed = mcpManageService.isSubscribed(tenantId, userId, id);
        Map<String, Object> result = new HashMap<>(2);
        result.put("subscribed", subscribed);
        return Result.success(result);
    }

    /**
     * 我的 MCP 服务订阅列表。
     *
     * @param tenantId 租户ID
     * @param userId   当前用户ID
     * @param page     页码
     * @param size     每页条数
     * @return 订阅的MCP服务分页列表
     */
    @GetMapping("/mine")
    public Result<Page<McpServiceVO>> mine(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (tenantId == null || userId == null) {
            throw new BusinessException(com.aegis.core.common.web.ResultCode.PARAM_ERROR, "租户ID或用户ID不能为空");
        }
        TenantContextHolder.bind(tenantId);
        Page<McpServiceVO> result = mcpManageService.pageSubscribedServices(tenantId, userId, page, size);
        return Result.success(result);
    }
}
