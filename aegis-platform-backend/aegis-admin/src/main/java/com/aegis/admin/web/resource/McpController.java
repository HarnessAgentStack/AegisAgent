package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.McpManageService;
import com.aegis.admin.service.resource.ReviewProcessEngine;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.dto.resource.McpServiceCreateRequest;
import com.aegis.core.dto.resource.McpServiceRegisterRequest;
import com.aegis.core.dto.resource.McpServiceVO;
import com.aegis.core.dto.resource.ToolVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 管理侧 Controller（平台级）。
 *
 * <p>面向管理员的 MCP 服务注册、审核、生命周期管理接口。
 * 路径前缀 {@code /api/admin/resource/mcp}，与用户侧 {@code /api/resource/mcp} 对应。
 *
 * @author wang.zhen
 * @see McpManageService
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/resource/mcp")
@RequiredArgsConstructor
public class McpController {

    private final McpManageService mcpManageService;
    private final ReviewProcessEngine reviewProcessEngine;

    // ============ MCP 服务管理（平台级） ============

    /**
     * 创建 MCP 服务（草稿态）+ 提交审核。
     *
     * <p>MCP Server 自动注册或管理员手动创建服务。
     * 创建后自动提交审核引擎，审核通过后变为 PUBLISHED 状态。
     *
     * @param req     MCP 服务创建请求
     * @param userId  当前用户ID
     * @return 服务ID
     */
    @PostMapping("/services")
    @Auditable(operation = "CREATE_MCP_SERVICE", resourceType = "MCP_SERVICE")
    public Result<Long> submitServiceForReview(@Valid @RequestBody McpServiceCreateRequest req,
                                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        Long id = mcpManageService.publishService(req);
        reviewProcessEngine.submit(null, "MCP_SERVICE", id);
        log.info("MCP服务提交审核: id={}, submitterId={}", id, userId);
        return Result.success(id);
    }

    /**
     * MCP Server 自注册端点（Service-to-Service）。
     *
     * <p>供外部 MCP Server（如 aegis-mcp-demo）在启动时自动上送自身元信息
     * 与工具列表到 admin，一次性完成服务创建 + 工具入库 + 提交审核。
     * 与用户侧的 {@link #submitServiceForReview} 不同，此端点不要求 JWT，
     * 交由 {@code SecurityConfig} 放行，可配合 {@code X-Server-Key} 做简单的服务间互验。
     *
     * <p>请求体 {@link McpServiceRegisterRequest} 包含：
     * <ul>
     *   <li>MCP 服务元信息（mcpCode / mcpName / endpoint / protocol 等）</li>
     *   <li>工具列表 ({@code tools})：每个工具含 toolCode / inputSchema / outputSchema 等</li>
     * </ul>
     *
     * @param req       自注册请求（服务元信息 + 工具列表）
     * @param serverKey 可选的服务端共享密钥
     * @return 服务ID
     */
    @PostMapping("/services/register")
    @Auditable(operation = "REGISTER_MCP_SERVICE", resourceType = "MCP_SERVICE")
    public Result<Long> registerFromServer(@Valid @RequestBody McpServiceRegisterRequest req,
                                           @RequestHeader(value = "X-Server-Key", required = false) String serverKey) {
        log.info("MCP Server 自注册: code={}, endpoint={}, tools={}, serverKeyProvided={}",
                req.getMcpCode(), req.getEndpoint(),
                req.getTools() != null ? req.getTools().size() : 0,
                serverKey != null && !serverKey.isBlank());
        Long id = mcpManageService.registerFromServer(req);

        // 1. 幂等校验：同一服务只允许一个活跃审核记录
        boolean hasActive = reviewProcessEngine.hasActiveReview(ResourceType.MCP_SERVICE, id, null);
        if (hasActive) {
            log.info("MCP服务已有活跃审核记录，跳过重复提交: id={}, code={}", id, req.getMcpCode());
            return Result.success(id);
        }

        // 2. 服务已在审核中或已发布，跳过（防止已批准后重复提交）
        McpServiceVO service = mcpManageService.getServiceDetail(id);
        if (service.getLifeStatus() == AgentLifeStatus.REVIEWING
                || service.getLifeStatus() == AgentLifeStatus.PUBLISHED) {
            log.info("MCP服务已在审核或已发布，跳过注册: id={}, status={}", id, service.getLifeStatus());
            return Result.success(id);
        }

        try {
            reviewProcessEngine.submit(null, "MCP_SERVICE", id);
        } catch (BusinessException e) {
            log.warn("MCP服务审核提交跳过(可能已存在): id={}, reason={}", id, e.getMessage());
        }
        return Result.success(id);
    }

    /**
     * 重新提交 MCP 服务审核（驳回后重交）。
     *
     * <p>仅驳回（REJECTED）或审核中（REVIEWING）状态的服务可重新提交。
     *
     * @param id 服务ID
     */
    @PostMapping("/services/{id}/resubmit")
    @ResourceOwner(resourceType = ResourceType.MCP_SERVICE, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "RESUBMIT_MCP_SERVICE", resourceType = "MCP_SERVICE", resourceIdParam = "id")
    public Result<Void> resubmitService(@PathVariable Long id,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        McpServiceVO service = mcpManageService.getServiceDetail(id);
        if (service.getLifeStatus() == AgentLifeStatus.REJECTED
                || service.getLifeStatus() == AgentLifeStatus.DRAFT) {
            reviewProcessEngine.submit(null, "MCP_SERVICE", id);
            return Result.success(null);
        }
        throw new BusinessException(ResultCode.CONFLICT,
                "仅驳回或草稿态的 MCP 服务可重新提交审核。当前状态: " + service.getLifeStatus());
    }

    /**
     * 启用 MCP 服务（运行时激活，状态置为 ACTIVE）。
     *
     * <p>需服务已审核发布（PUBLISHED），否则返回明确的错误提示。
     *
     * @param id 服务ID
     */
    @PostMapping("/services/{id}/activate")
    @ResourceOwner(resourceType = ResourceType.MCP_SERVICE, permission = ResourcePermission.MANAGE, resourceIdParam = "id")
    @Auditable(operation = "ACTIVATE_MCP_SERVICE", resourceType = "MCP_SERVICE", resourceIdParam = "id")
    public Result<Void> activateService(@PathVariable Long id,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        McpServiceVO service = mcpManageService.getServiceDetail(id);
        if (service.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "MCP 服务需先通过审核才能激活。当前状态: " + service.getLifeStatus()
                            + "，请先提交审核并等待审核通过");
        }
        mcpManageService.activateService(id);
        return Result.success(null);
    }

    /**
     * 停用 MCP 服务（状态置为 PENDING）。
     *
     * @param id 服务ID
     */
    @PostMapping("/services/{id}/deactivate")
    @ResourceOwner(resourceType = ResourceType.MCP_SERVICE, permission = ResourcePermission.MANAGE, resourceIdParam = "id")
    @Auditable(operation = "DEACTIVATE_MCP_SERVICE", resourceType = "MCP_SERVICE", resourceIdParam = "id")
    public Result<Void> deactivateService(@PathVariable Long id,
                                          @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        mcpManageService.deactivateService(id);
        return Result.success(null);
    }

    /**
     * 分页查询 MCP 服务列表。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 服务分页列表
     */
    @GetMapping("/services/page")
    public Result<Page<McpServiceVO>> pageServices(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return Result.success(mcpManageService.pageServices(page, size));
    }

    /**
     * 查询 MCP 服务详情（含关联工具列表和审核记录）。
     *
     * @param id 服务ID
     * @return 服务详情 VO（含 tools 和 reviews）
     */
    @GetMapping("/services/{id}")
    public Result<McpServiceVO> serviceDetail(@PathVariable Long id) {
        McpServiceVO detail = mcpManageService.getServiceDetailWithTools(id);
        return Result.success(detail);
    }

    /**
     * 查询 MCP 服务提供的工具列表。
     *
     * @param id 服务ID
     * @return 工具列表
     */
    @GetMapping("/services/{id}/tools")
    public Result<List<ToolVO>> serviceTools(@PathVariable Long id) {
        return Result.success(mcpManageService.listServiceTools(id));
    }

    /**
     * 查询 MCP 服务的审核记录。
     *
     * @param id 服务ID
     * @return 审核记录列表
     */
    @GetMapping("/services/{id}/reviews")
    public Result<List<ResourceReview>> serviceReviews(@PathVariable Long id) {
        return Result.success(reviewProcessEngine.listReviewsByResource(ResourceType.MCP_SERVICE, id, null));
    }

    /**
     * 删除 MCP 服务（连带删除关联工具）。
     *
     * @param id 服务ID
     */
    @DeleteMapping("/services/{id}")
    @ResourceOwner(resourceType = ResourceType.MCP_SERVICE, permission = ResourcePermission.DELETE, resourceIdParam = "id")
    public Result<Void> deleteService(@PathVariable Long id,
                                      @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        mcpManageService.deleteService(id);
        return Result.success(null);
    }
}
