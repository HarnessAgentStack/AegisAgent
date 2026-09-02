package com.aegis.runtime.integration.security;

import com.aegis.core.domain.security.ToolPolicy;
import com.aegis.core.enums.resource.ToolPolicyAction;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.dal.mapper.security.ToolPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AgentScope PermissionRule DB 加载器。
 *
 * <p>从 sec_tool_policy 表加载策略矩阵，映射为 AgentScope PermissionContextState，
 * 替代旧的 evaluateToolPolicy 评估链。</p>
 *
 * <p>策略矩阵：(toolType, securityLevel) → action(ALLOW/APPROVE/REJECT)，
 * 映射为 PermissionBehavior(ALLOW/ASK/DENY)。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisPermissionRuleLoader {

    private final ToolPolicyMapper toolPolicyMapper;

    private final Cache<String, Map<PolicyKey, ToolPolicyAction>> policyCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(5, TimeUnit.MINUTES)
                    .maximumSize(500)
                    .build();

    /**
     * 加载策略矩阵（带 5 分钟本地缓存）。
     *
     * @param tenantId       租户 ID
     * @param governanceTier 治理档位 STANDARD/ENHANCED/STRICT
     * @return 策略矩阵 Map<(toolType, securityLevel), action>
     */
    public Map<PolicyKey, ToolPolicyAction> loadPolicyMatrix(Long tenantId, String governanceTier) {
        String cacheKey = tenantId + ":" + (governanceTier != null ? governanceTier : "ALL");
        return policyCache.get(cacheKey, k -> doLoadPolicyMatrix(tenantId, governanceTier));
    }

    private Map<PolicyKey, ToolPolicyAction> doLoadPolicyMatrix(Long tenantId, String governanceTier) {
        LambdaQueryWrapper<ToolPolicy> wrapper = new LambdaQueryWrapper<ToolPolicy>()
                .eq(ToolPolicy::getTenantId, tenantId)
                .eq(ToolPolicy::getEnabled, true);
        List<ToolPolicy> all = toolPolicyMapper.selectList(wrapper);

        Map<PolicyKey, ToolPolicyAction> matrix = new HashMap<>();
        for (ToolPolicy p : all) {
            if (p.getToolType() == null || p.getSecurityLevel() == null || p.getAction() == null) {
                continue;
            }
            if (!appliesToGovernanceTier(p.getGovernanceTierMin(), governanceTier)) {
                continue;
            }
            matrix.put(new PolicyKey(p.getToolType(), p.getSecurityLevel()), p.getAction());
        }
        log.info("AegisPermissionRuleLoader loaded: tenantId={}, tier={}, policies={}",
                tenantId, governanceTier, matrix.size());
        return matrix;
    }

    private boolean appliesToGovernanceTier(String governanceTierMin, String currentTier) {
        if (governanceTierMin == null || governanceTierMin.isBlank()) {
            return true;
        }
        if (currentTier == null) {
            return true;
        }
        int minRank = tierRank(governanceTierMin);
        int curRank = tierRank(currentTier);
        return curRank >= minRank;
    }

    private int tierRank(String tier) {
        return switch (tier.toUpperCase()) {
            case "STANDARD" -> 1;
            case "ENHANCED" -> 2;
            case "STRICT" -> 3;
            default -> 1;
        };
    }

    /**
     * 查询给定 (toolType, securityLevel) 的策略动作。
     *
     * @return ToolPolicyAction，未匹配时返回 null（调用方决定默认值）
     */
    public ToolPolicyAction lookupAction(Map<PolicyKey, ToolPolicyAction> matrix,
                                          ToolType toolType, int securityLevel) {
        return matrix.get(new PolicyKey(toolType, securityLevel));
    }

    /**
     * 直接评估给定工具类型+安全等级的 PermissionBehavior。
     *
     * <p>未匹配 DB 策略时按默认规则：L1/L2→ALLOW, L3→ASK, L4→DENY</p>
     */
    public PermissionBehavior evaluateBehavior(Long tenantId, String governanceTier,
                                                ToolType toolType, int securityLevel) {
        Map<PolicyKey, ToolPolicyAction> matrix = loadPolicyMatrix(tenantId, governanceTier);
        ToolPolicyAction action = matrix.get(new PolicyKey(toolType, securityLevel));
        if (action != null) {
            return toPermissionBehavior(action);
        }
        return securityLevel <= 2 ? PermissionBehavior.ALLOW
             : securityLevel == 3 ? PermissionBehavior.ASK
             : PermissionBehavior.DENY;
    }

    /**
     * 将 ToolPolicyAction 映射为 AgentScope PermissionBehavior。
     */
    public static PermissionBehavior toPermissionBehavior(ToolPolicyAction action) {
        if (action == null) return null;
        return switch (action) {
            case ALLOW -> PermissionBehavior.ALLOW;
            case APPROVE -> PermissionBehavior.ASK;
            case REJECT -> PermissionBehavior.DENY;
        };
    }

    /**
     * 使缓存失效（资源变更时调用）。
     */
    public void invalidate(Long tenantId) {
        policyCache.asMap().keySet().removeIf(k -> k.startsWith(tenantId + ":"));
        log.info("AegisPermissionRuleLoader cache invalidated: tenantId={}", tenantId);
    }

    /** 策略矩阵键 */
    public record PolicyKey(ToolType toolType, int securityLevel) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PolicyKey pk)) return false;
            return securityLevel == pk.securityLevel && toolType == pk.toolType;
        }
        @Override
        public int hashCode() {
            return toolType.hashCode() * 31 + securityLevel;
        }
    }
}
