package com.aegis.admin.web.agent;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.agent.AgentApiDocService;
import com.aegis.admin.service.agent.AgentApiKeyService;
import com.aegis.admin.service.agent.AgentApiManageService;
import com.aegis.admin.service.agent.AgentApiVersionService;
import com.aegis.admin.service.agent.AgentPublishService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentApi;
import com.aegis.core.domain.agent.AgentApiKey;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.agent.AgentApiVersionInfo;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体开放 API 管理控制器。
 *
 * <p>管理系统智能体对外 API 的配置，包括：
 * <ul>
 *   <li>API 密钥管理（创建/重置/禁用/轮换）</li>
 *   <li>限流配置（QPS/超时）</li>
 *   <li>鉴权方式（API_KEY / BEARER / OAUTH2）</li>
 *   <li>数据出境合规（IP白名单/部门范围）</li>
 *   <li>OpenAPI 规范生成</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent-api")
@RequiredArgsConstructor
public class AgentApiController {

    private final AgentApiManageService agentApiManageService;
    private final AgentApiKeyService agentApiKeyService;
    private final AgentApiDocService agentApiDocService;
    private final AgentApiVersionService agentApiVersionService;
    private final AgentPublishService agentPublishService;

    /**
     * 查询智能体的 API 配置列表。
     */
    @GetMapping
    public Result<List<AgentApi>> listByAgent(@RequestParam Long agentId,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        List<AgentApi> apis = agentApiManageService.listByAgent(agentId);
        return Result.success(apis);
    }

    /**
     * 幂等初始化/修复系统智能体的 API 发布配置。
     *
     * <p>场景：创建/审核链路异常或历史数据缺失导致"智能体已发布但无 API 记录"，
     * 前端 API 管理页显示"API 未配置"时，由此端点手动触发自愈：
     * <ul>
     *   <li>已有有效记录 -> 启用并补齐沙箱池与 API Key（幂等返回）；</li>
     *   <li>仅有逻辑删除残留行 -> 恢复复用（规避唯一键冲突）；</li>
     *   <li>完全无记录 -> 按默认值补建（NORMAL + API Key + 沙箱池匹配）。</li>
     * </ul>
     *
     * @param agentId 智能体ID（须为 SYSTEM 类型）
     */
    @PostMapping("/init/{agentId}")
    @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.EDIT, resourceIdParam = "agentId")
    @Auditable(operation = "INIT_AGENT_API", resourceType = "AGENT", resourceIdParam = "agentId")
    public Result<AgentApi> initAgentApi(@PathVariable Long agentId,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                     @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentPublishService.initSystemAgentApi(agentId);
        return Result.success(api);
    }

    /**
     * 查询单个 API 配置详情。
     */
    @GetMapping("/{id}")
    public Result<AgentApi> detail(@PathVariable Long id,
                               @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        return Result.success(api);
    }

    /**
     * 更新 API 配置（限流/鉴权/出境等）。
     */
    @PutMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_AGENT_API", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody AgentApi api,
                           @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                           @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi existing = agentApiManageService.getById(id);
        if (existing == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        api.setId(id);
        agentApiManageService.update(api);
        return Result.success(null);
    }

    /**
     * 启用/禁用 API。
     */
    @PostMapping("/{id}/status")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_AGENT_API_STATUS", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestParam boolean enabled,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        api.setStatus(enabled ? CommonStatus.NORMAL : CommonStatus.DISABLED);
        agentApiManageService.update(api);
        return Result.success(null);
    }

    /**
     * 分页查询 API 配置（管理员视图）。
     */
    @GetMapping("/page")
    public Result<Page<AgentApi>> page(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(agentApiManageService.page(page, size, tenantId));
    }

    /**
     * 更新 API 的 Schema 配置（入参/出参/示例）。
     */
    @PutMapping("/{id}/schema")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_AGENT_API_SCHEMA", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<Void> updateSchema(@PathVariable Long id, @RequestBody AgentApi api,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi existing = agentApiManageService.getById(id);
        if (existing == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        api.setId(id);
        agentApiManageService.update(api);
        return Result.success(null);
    }

    /**
     * 在线测试 API（仅记录时间，实际执行由 runtime 服务处理）。
     */
    @PostMapping("/{id}/test")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "TEST_AGENT_API", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<AgentApi> testApi(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        api.setLastTestedAt(LocalDateTime.now());
        agentApiManageService.update(api);
        return Result.success(api);
    }

    // ============ API Key 管理 ============

    /**
     * 为指定 API 生成新的 API Key。
     */
    @PostMapping("/{id}/keys")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "GENERATE_AGENT_API_KEY", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<Map<String, Object>> generateKey(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        String label = body.get("label");
        String validityType = body.getOrDefault("validityType", "PERMANENT");
        Long actualTenantId = tenantId != null ? tenantId : api.getTenantId();
        Map<String, Object> result = agentApiKeyService.generateKey(
                id, api.getAgentId(), actualTenantId, label, validityType);
        return Result.success(result);
    }

    /**
     * 查询指定 API 的 Key 列表。
     */
    @GetMapping("/{id}/keys")
    public Result<List<AgentApiKey>> listKeys(@PathVariable Long id,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        List<AgentApiKey> keys = agentApiKeyService.listByApiId(id);
        return Result.success(keys);
    }

    /**
     * 吊销指定 API Key。
     */
    @PostMapping("/keys/{keyId}/revoke")
    @Auditable(operation = "REVOKE_AGENT_API_KEY", resourceType = "AGENT_API_KEY", resourceIdParam = "keyId")
    public Result<Void> revokeKey(@PathVariable Long keyId,
                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        agentApiKeyService.revokeKey(keyId);
        return Result.success(null);
    }

    /**
     * 轮换 API Key（生成新 Key，吊销旧 Key）。
     */
    @PostMapping("/{id}/rotate-key")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "ROTATE_AGENT_API_KEY", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<Map<String, Object>> rotateKey(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        Long oldKeyId = toLong(body.get("oldKeyId"));
        if (oldKeyId == null) {
            return Result.fail(ResultCode.PARAM_ERROR, "oldKeyId 不能为空");
        }
        Long actualTenantId = tenantId != null ? tenantId : api.getTenantId();
        Map<String, Object> result = agentApiKeyService.rotateKey(
                id, oldKeyId, api.getAgentId(), actualTenantId);
        return Result.success(result);
    }

    /**
     * 生成 OpenAPI 3.0 规范文档。
     */
    @GetMapping("/{id}/openapi.json")
    public Result<Map<String, Object>> getOpenApiSpec(@PathVariable Long id,
                                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        Map<String, Object> spec = agentApiDocService.generateOpenApiSpec(api);
        return Result.success(spec);
    }

    /**
     * 查询 API 错误码定义。
     */
    @GetMapping("/{id}/error-codes")
    public Result<List<Map<String, Object>>> getErrorCodes(@PathVariable Long id,
                                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        AgentApi api = agentApiManageService.getById(id);
        if (api == null) {
            return Result.fail(ResultCode.NOT_FOUND, "API配置不存在: " + id);
        }
        List<Map<String, Object>> errorCodes = agentApiDocService.getErrorCodes();
        return Result.success(errorCodes);
    }

    /**
     * 查询 API 当前版本信息。
     */
    @GetMapping("/{id}/version")
    public Result<AgentApiVersionInfo> getVersion(@PathVariable Long id,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        AgentApiVersionInfo versionInfo = agentApiVersionService.getCurrentVersion(id);
        return Result.success(versionInfo);
    }

    /**
     * 递增 API 版本号（minor 版本 +1）。
     */
    @PostMapping("/{id}/bump-version")
    @ResourceOwner(resourceType = ResourceType.AGENT_API, permission = ResourcePermission.PUBLISH, resourceIdParam = "id")
    @Auditable(operation = "BUMP_AGENT_API_VERSION", resourceType = "AGENT_API", resourceIdParam = "id")
    public Result<AgentApiVersionInfo> bumpVersion(@PathVariable Long id,
                                               @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                               @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        AgentApiVersionInfo versionInfo = agentApiVersionService.bumpVersion(id);
        return Result.success(versionInfo);
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Long l) return l;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }
}