package com.aegis.admin.web.agent;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.agent.AgentPublishService;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能体生命周期管理 Controller。
 *
 * <p>负责智能体的归档、订阅等生命周期端点。发布统一通过审核闭环
 * （{@link AgentReviewController} 的 submit-review / approve / reject）。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent")
@RequiredArgsConstructor
public class AgentLifecycleController {

    private final AgentPublishService agentPublishService;

    /**
     * 归档下线智能体（PUBLISHED -> ARCHIVED）。
     */
    @PostMapping("/{id}/archive")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "ARCHIVE_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> archive(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.archive(tenantId, id);
        return Result.success(null);
    }

    /**
     * 订阅智能体（仅已发布可订阅）。
     */
    @PostMapping("/{id}/subscribe")
    @Auditable(operation = "SUBSCRIBE_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> subscribe(@PathVariable Long id,
                                  @RequestHeader("X-Tenant-Id") Long tenantId,
                                  @RequestHeader("X-User-Id") Long userId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.subscribe(tenantId, id, userId);
        return Result.success(null);
    }

    /**
     * 退订智能体。
     */
    @PostMapping("/{id}/unsubscribe")
    @Auditable(operation = "UNSUBSCRIBE_AGENT", resourceType = "AGENT", resourceIdParam = "id")
    public Result<Void> unsubscribe(@PathVariable Long id,
                                   @RequestHeader("X-Tenant-Id") Long tenantId,
                                   @RequestHeader("X-User-Id") Long userId) {
        TenantContextHolder.bind(tenantId);
        agentPublishService.unsubscribe(tenantId, id, userId);
        return Result.success(null);
    }
}
