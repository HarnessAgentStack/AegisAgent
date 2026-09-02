package com.aegis.admin.web.agent;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.agent.AgentPublishService;
import com.aegis.admin.service.agent.AgentSubscriptionService;
import com.aegis.admin.service.agent.AgentBindingCommandService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.common.web.Result;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.dto.agent.AgentCreateRequest;
import com.aegis.core.dto.agent.AgentUpdateRequest;
import com.aegis.core.dto.agent.AgentVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
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
import java.util.Map;

/**
 * 智能体基础管理 Controller，负责核心 CRUD、配置与绑定端点。
 *
 * <p>父路径 {@code /api/admin/agent}，与 {@link AgentLifecycleController}、
 * {@link AgentReviewController}、{@link AgentHitlController} 共享。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentPublishService agentPublishService;
    private final AgentSubscriptionService agentSubscriptionService;
    private final AgentBindingCommandService agentBindingCommandService;

    /**
     * 创建智能体（草稿态）。
     */
    @PostMapping
    @Auditable(operation = "CREATE_AGENT", resourceType = "AGENT")
    public Result<Long> create(@Valid @RequestBody AgentCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        if (req.getTenantId() == null) req.setTenantId(tenantId);
        if (req.getAuthorUserId() == null) req.setAuthorUserId(userId);
        Long id = agentPublishService.create(req);
        return Result.success(id);
    }

    /**
     * 更新智能体（整体替换语义）。
     *
     * <p>与 {@code POST /api/admin/agent} 对称，使用 {@link AgentUpdateRequest} 承接全部更新字段：
     * <ul>
     *   <li>agent_def 主体字段（agentName/icon/color/description/category/governanceTier）</li>
     *   <li>agent_config 配置（systemPrompt/modelTier/temperature/memoryStrategy/maxTurns/enabledTools）</li>
     *   <li>agent_binding 资源绑定（整体替换，null 表示不修改，空列表表示清空）</li>
     *   <li>agent_api API 发布配置（仅 SYSTEM 类型，整体替换）</li>
     * </ul>
     *
     * <p>DRAFT/REJECTED 直接覆盖当前版本；PUBLISHED 自动递增 minor 版本并创建配置快照。
     */
    @PutMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AgentUpdateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.update(id, tenantId, req);
        return Result.success(null);
    }

    /**
     * 删除智能体（仅草稿态可删除）。
     */
    @DeleteMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.DELETE, resourceIdParam = "id")
    @Auditable(operation = "DELETE_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> delete(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.delete(tenantId, id);
        return Result.success(null);
    }

    /**
     * 查询智能体详情（含配置与资源绑定）。
     */
    @GetMapping("/{id}")
    public Result<AgentVO> detail(@PathVariable Long id,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.getDetail(tenantId, id, userId));
    }

    /**
     * 分页查询智能体。
     */
    @GetMapping("/page")
    public Result<Page<AgentDef>> page(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                        @RequestParam(required = false) String lifeStatus,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        AgentLifeStatus status = null;
        if (lifeStatus != null && !lifeStatus.isEmpty()) {
            try {
                status = AgentLifeStatus.valueOf(lifeStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("无效的智能体状态参数: {}, 忽略过滤", lifeStatus);
                // 返回空列表而非 400 错误，提示用户参数无效
                Page<AgentDef> emptyPage = new Page<>(page, size);
                emptyPage.setTotal(0);
                emptyPage.setRecords(java.util.Collections.emptyList());
                return Result.success(emptyPage);
            }
        }
        return Result.success(agentPublishService.page(tenantId, status, keyword, page, size));
    }

    /**
     * 查询可订阅的智能体（市场列表）。
     */
    @GetMapping("/subscribable")
    public Result<List<AgentVO>> subscribable(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                               @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentSubscriptionService.listSubscribable(tenantId, userId));
    }

    /**
     * 更新智能体配置。
     */
    @PutMapping("/{id}/config")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_AGENT_CONFIG", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> updateConfig(@PathVariable Long id, @Valid @RequestBody AgentConfig config,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                      @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        config.setAgentId(id);
        if (config.getTenantId() == null) config.setTenantId(tenantId);
        agentPublishService.updateConfig(config);
        return Result.success(null);
    }

    /**
     * 查询智能体配置。
     */
    @GetMapping("/{id}/config")
    public Result<AgentConfig> getConfig(@PathVariable Long id,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                          @RequestParam(required = false) String version) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.getConfig(tenantId, id, version));
    }

    /**
     * 新增资源绑定。
     */
    @PostMapping("/{id}/binding")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "ADD_AGENT_BINDING", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> addBinding(@PathVariable Long id, @Valid @RequestBody AgentBinding binding,
                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        binding.setAgentId(id);
        if (binding.getTenantId() == null) binding.setTenantId(tenantId);
        agentBindingCommandService.addBinding(binding);
        return Result.success(null);
    }

    /**
     * 移除资源绑定。
     */
    @DeleteMapping("/{id}/binding/{bindingId}")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "REMOVE_AGENT_BINDING", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> removeBinding(@PathVariable Long id, @PathVariable Long bindingId,
                                       @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                       @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        agentBindingCommandService.removeBinding(tenantId, id, bindingId);
        return Result.success(null);
    }

    /**
     * 查询资源绑定列表。
     */
    @GetMapping("/{id}/bindings")
    public Result<List<AgentBinding>> listBindings(@PathVariable Long id,
                                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentBindingCommandService.listBindings(tenantId, id));
    }

    /**
     * 查询当前用户创建的智能体列表。
     */
    @GetMapping("/my")
    public Result<List<AgentVO>> myAgents(@RequestHeader("X-Tenant-Id") Long tenantId,
                                          @RequestHeader("X-User-Id") Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentSubscriptionService.listMyAgents(tenantId, userId));
    }

    /**
     * 查询当前租户的通用智能体（平台预置，每租户唯一）。
     */
    @GetMapping("/universal")
    public Result<AgentVO> universalAgent(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.getUniversalAgent(tenantId));
    }

    /**
     * 查询智能体统计信息（用于仪表盘）。
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentPublishService.stats(tenantId));
    }
}
