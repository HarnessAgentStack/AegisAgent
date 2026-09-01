package com.aegis.core.dto.security;

import com.aegis.core.enums.common.SecurityLevel;
import lombok.Builder;
import lombok.Getter;

/**
 * 策略决策结果（统一输出）。
 *
 * <p>承载安全引擎对任意评估请求的决策输出，包含决策类型、关联策略/规则/资源标识、
 * 脱敏后内容、路由目标等元信息，供审计、HITL、执行层直接消费。
 *
 *  @author wang.zhen
 */
@Getter
@Builder
public class PolicyDecision {

    /** 决策类型 */
    private final Decision decision;

    /** 关联策略ID（策略命中时） */
    private final Long policyId;

    /** 关联规则ID（规则命中时） */
    private final String ruleId;

    /** 关联资源类型（RESOURCE 级策略） */
    private final String resourceType;

    /** 关联资源ID（RESOURCE 级策略） */
    private final Long resourceId;

    /** 决策原因（阻断/审批的具体原因，用于日志输出） */
    private final String reason;

    /** 脱敏后内容（MASK 决策时携带，直接替换原内容） */
    private final String maskedContent;

    /** 路由目标（ROUTE_LOCAL 决策时携带，指定本地模型标识） */
    private final String routeTarget;

    /** 目标安全等级（ASK/REJECT 时用于 HITL 升级判断） */
    private final SecurityLevel targetLevel;

    // ==================== 静态工厂方法 ====================

    public static PolicyDecision allow() {
        return PolicyDecision.builder().decision(Decision.ALLOW).build();
    }

    public static PolicyDecision allow(Long policyId, String ruleId, String resourceType, Long resourceId) {
        return PolicyDecision.builder()
                .decision(Decision.ALLOW)
                .policyId(policyId)
                .ruleId(ruleId)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .build();
    }

    public static PolicyDecision ask(String reason, Long policyId, String ruleId,
                                     String resourceType, Long resourceId, SecurityLevel targetLevel) {
        return PolicyDecision.builder()
                .decision(Decision.ASK)
                .reason(reason)
                .policyId(policyId)
                .ruleId(ruleId)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .targetLevel(targetLevel)
                .build();
    }

    public static PolicyDecision reject(String reason, Long policyId, String ruleId,
                                        String resourceType, Long resourceId) {
        return PolicyDecision.builder()
                .decision(Decision.REJECT)
                .reason(reason)
                .policyId(policyId)
                .ruleId(ruleId)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .build();
    }

    public static PolicyDecision mask(String maskedContent, Long policyId, String ruleId) {
        return PolicyDecision.builder()
                .decision(Decision.MASK)
                .maskedContent(maskedContent)
                .policyId(policyId)
                .ruleId(ruleId)
                .build();
    }

    public static PolicyDecision routeLocal(String routeTarget, String reason) {
        return PolicyDecision.builder()
                .decision(Decision.ROUTE_LOCAL)
                .routeTarget(routeTarget)
                .reason(reason)
                .build();
    }

    public static PolicyDecision auditOnly(String reason, Long policyId, String ruleId) {
        return PolicyDecision.builder()
                .decision(Decision.AUDIT_ONLY)
                .reason(reason)
                .policyId(policyId)
                .ruleId(ruleId)
                .build();
    }

    // ==================== 判断方法 ====================

    public boolean isAllow() { return decision == Decision.ALLOW; }
    public boolean isAsk() { return decision == Decision.ASK; }
    public boolean isReject() { return decision == Decision.REJECT; }
    public boolean isMask() { return decision == Decision.MASK; }
    public boolean isRouteLocal() { return decision == Decision.ROUTE_LOCAL; }
    public boolean isAuditOnly() { return decision == Decision.AUDIT_ONLY; }

    /** 决策枚举 */
    public enum Decision {
        /** 直接放行 */
        ALLOW,
        /** 触发 HITL 审批 */
        ASK,
        /** 阻断 */
        REJECT,
        /** 脱敏后放行 */
        MASK,
        /** 强制本地/加密模型路由 */
        ROUTE_LOCAL,
        /** 仅记录审计 */
        AUDIT_ONLY
    }
}
