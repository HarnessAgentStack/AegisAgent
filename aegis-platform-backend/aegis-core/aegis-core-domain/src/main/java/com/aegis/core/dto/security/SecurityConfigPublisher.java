package com.aegis.core.dto.security;

/**
 * 策略变更事件发布接口。
 *
 * <p>管理控制台（aegis-admin）通过此接口发布策略变更事件，
 * 运行时（aegis-runtime）提供实现，通过 Redis pub/sub 通知所有节点刷新缓存。
 *
 * <p>接口定义在 aegis-core 层，避免 admin 直接依赖 runtime 模块。
 *
 *  @author wang.zhen
 */
public interface SecurityConfigPublisher {

    /**
     * 发布策略变更事件。
     *
     * @param tenantId   租户ID
     * @param policyType 策略类型（TOOL/CONTENT/OUTBOUND/MASK/HITL）
     * @param policyId   变更的策略ID
     * @param eventType  事件类型（CREATE/UPDATE/DELETE）
     */
    void publishPolicyChangedEvent(Long tenantId, String policyType, Long policyId, String eventType);

    /**
     * 发布 HITL 规则变更事件。
     *
     * @param tenantId 租户ID
     * @param agentId  智能体ID
     */
    void publishHitlRuleChangedEvent(Long tenantId, Long agentId);
}
