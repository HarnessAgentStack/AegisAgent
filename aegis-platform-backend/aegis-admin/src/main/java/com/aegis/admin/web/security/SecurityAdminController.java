package com.aegis.admin.web.security;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.security.SecurityService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.security.MaskRuleCreateRequest;
import com.aegis.core.dto.security.MaskRuleUpdateRequest;
import com.aegis.core.dto.security.MaskRuleVO;
import com.aegis.core.dto.security.OutboundPolicyCreateRequest;
import com.aegis.core.dto.security.OutboundPolicyUpdateRequest;
import com.aegis.core.dto.security.OutboundPolicyVO;
import com.aegis.core.dto.security.SensitiveWordCreateRequest;
import com.aegis.core.dto.security.SensitiveWordUpdateRequest;
import com.aegis.core.dto.security.SensitiveWordVO;
import com.aegis.core.dto.security.ToolPolicyCreateRequest;
import com.aegis.core.dto.security.ToolPolicyUpdateRequest;
import com.aegis.core.dto.security.ToolPolicyVO;
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

/**
 * 安全管理接口。
 *
 * <p>提供安全策略、敏感词、脱敏规则、出站策略的管理接口，
 * 支撑平台安全运营。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/security")
@RequiredArgsConstructor
public class SecurityAdminController {

    private final SecurityService securityService;

    // ==================== 工具策略 ====================

    @GetMapping("/tool-policies")
    public Result<Page<ToolPolicyVO>> listToolPolicies(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String toolType,
            @RequestParam(required = false) Integer securityLevel,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.listToolPolicies(toolType, securityLevel, enabled, tenantId, page, size));
    }

    @PostMapping("/tool-policies")
    @Auditable(operation = "CREATE_TOOL_POLICY", resourceType = "TOOL_POLICY")
    public Result<ToolPolicyVO> createToolPolicy(@Valid @RequestBody ToolPolicyCreateRequest req,
                                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.createToolPolicy(req, tenantId));
    }

    @PutMapping("/tool-policies/{id}")
    @Auditable(operation = "UPDATE_TOOL_POLICY", resourceType = "TOOL_POLICY", resourceIdParam = "id")
    public Result<Void> updateToolPolicy(@PathVariable Long id, @Valid @RequestBody ToolPolicyUpdateRequest req,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.updateToolPolicy(id, req, tenantId);
        return Result.success(null);
    }

    @DeleteMapping("/tool-policies/{id}")
    @Auditable(operation = "DELETE_TOOL_POLICY", resourceType = "TOOL_POLICY", resourceIdParam = "id")
    public Result<Void> deleteToolPolicy(@PathVariable Long id,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.deleteToolPolicy(id, tenantId);
        return Result.success(null);
    }

    // ==================== 敏感词 ====================

    @GetMapping("/sensitive-words")
    public Result<Page<SensitiveWordVO>> listSensitiveWords(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String matchMode,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.listSensitiveWords(category, matchMode, action, enabled, tenantId, page, size));
    }

    @PostMapping("/sensitive-words")
    @Auditable(operation = "CREATE_SENSITIVE_WORD", resourceType = "SENSITIVE_WORD")
    public Result<SensitiveWordVO> createSensitiveWord(@Valid @RequestBody SensitiveWordCreateRequest req,
                                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.createSensitiveWord(req, tenantId));
    }

    @PutMapping("/sensitive-words/{id}")
    @Auditable(operation = "UPDATE_SENSITIVE_WORD", resourceType = "SENSITIVE_WORD", resourceIdParam = "id")
    public Result<Void> updateSensitiveWord(@PathVariable Long id, @Valid @RequestBody SensitiveWordUpdateRequest req,
                                             @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.updateSensitiveWord(id, req, tenantId);
        return Result.success(null);
    }

    @DeleteMapping("/sensitive-words/{id}")
    @Auditable(operation = "DELETE_SENSITIVE_WORD", resourceType = "SENSITIVE_WORD", resourceIdParam = "id")
    public Result<Void> deleteSensitiveWord(@PathVariable Long id,
                                             @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.deleteSensitiveWord(id, tenantId);
        return Result.success(null);
    }

    // ==================== 脱敏规则 ====================

    @GetMapping("/mask-rules")
    public Result<Page<MaskRuleVO>> listMaskRules(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String dataType,
            @RequestParam(required = false) String maskWay,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.listMaskRules(dataType, maskWay, enabled, tenantId, page, size));
    }

    @PostMapping("/mask-rules")
    @Auditable(operation = "CREATE_MASK_RULE", resourceType = "MASK_RULE")
    public Result<MaskRuleVO> createMaskRule(@Valid @RequestBody MaskRuleCreateRequest req,
                                            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.createMaskRule(req, tenantId));
    }

    @PutMapping("/mask-rules/{id}")
    @Auditable(operation = "UPDATE_MASK_RULE", resourceType = "MASK_RULE", resourceIdParam = "id")
    public Result<Void> updateMaskRule(@PathVariable Long id, @Valid @RequestBody MaskRuleUpdateRequest req,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.updateMaskRule(id, req, tenantId);
        return Result.success(null);
    }

    @DeleteMapping("/mask-rules/{id}")
    @Auditable(operation = "DELETE_MASK_RULE", resourceType = "MASK_RULE", resourceIdParam = "id")
    public Result<Void> deleteMaskRule(@PathVariable Long id,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.deleteMaskRule(id, tenantId);
        return Result.success(null);
    }

    // ==================== 出站策略 ====================

    @GetMapping("/outbound-policies")
    public Result<Page<OutboundPolicyVO>> listOutboundPolicies(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
            @RequestParam(required = false) String policyType,
            @RequestParam(required = false) String applicableScope,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.listOutboundPolicies(policyType, applicableScope, enabled, tenantId, page, size));
    }

    @PostMapping("/outbound-policies")
    @Auditable(operation = "CREATE_OUTBOUND_POLICY", resourceType = "OUTBOUND_POLICY")
    public Result<OutboundPolicyVO> createOutboundPolicy(@Valid @RequestBody OutboundPolicyCreateRequest req,
                                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(securityService.createOutboundPolicy(req, tenantId));
    }

    @PutMapping("/outbound-policies/{id}")
    @Auditable(operation = "UPDATE_OUTBOUND_POLICY", resourceType = "OUTBOUND_POLICY", resourceIdParam = "id")
    public Result<Void> updateOutboundPolicy(@PathVariable Long id, @Valid @RequestBody OutboundPolicyUpdateRequest req,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.updateOutboundPolicy(id, req, tenantId);
        return Result.success(null);
    }

    @DeleteMapping("/outbound-policies/{id}")
    @Auditable(operation = "DELETE_OUTBOUND_POLICY", resourceType = "OUTBOUND_POLICY", resourceIdParam = "id")
    public Result<Void> deleteOutboundPolicy(@PathVariable Long id,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        securityService.deleteOutboundPolicy(id, tenantId);
        return Result.success(null);
    }
}
