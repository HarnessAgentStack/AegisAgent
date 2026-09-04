package com.aegis.runtime.service.sandbox;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.security.SandboxPolicy;
import com.aegis.dal.mapper.security.SandboxPolicyMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱命令策略解析器。
 * <p>
 * 按 tenantId + toolCode 判定工具是否强制进沙箱。
 * Caffeine 缓存 5min，通过 SecurityConfigPublisher 监听策略变更自动失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxPolicyResolver {

    private final SandboxPolicyMapper sandboxPolicyMapper;

    /** tenantId -> (toolCode -> sandboxExecution) 缓存 */
    private final Cache<Long, Map<String, Boolean>> policyCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100)
            .build();

    /**
     * 判定工具是否进沙箱。
     *
     * @param tenantId 租户ID
     * @param toolCode 工具编码
     * @return true=强制进沙箱 / false=不进 / null=未配置走默认
     */
    public Boolean resolve(Long tenantId, String toolCode) {
        if (tenantId == null || toolCode == null) return null;
        Map<String, Boolean> tenantMap = policyCache.get(tenantId, this::loadTenantPolicies);
        if (tenantMap == null) return null;
        return tenantMap.get(toolCode);
    }

    /**
     * 批量筛选给定工具集合中需要进沙箱的工具编码。
     * 仅返回 sec_sandbox_policy 中 sandbox_execution=true 且 enabled=true 的工具。
     * 未配置或 false 的不进沙箱（与"配置不走或没配的都不走"诉求对齐）。
     */
    public java.util.Set<String> resolveSandboxTools(Long tenantId, java.util.Set<String> toolCodes) {
        if (tenantId == null || toolCodes == null || toolCodes.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        Map<String, Boolean> tenantMap = policyCache.get(tenantId, this::loadTenantPolicies);
        if (tenantMap == null || tenantMap.isEmpty()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> sandboxTools = new java.util.HashSet<>();
        for (String code : toolCodes) {
            Boolean exec = tenantMap.get(code);
            if (Boolean.TRUE.equals(exec)) {
                sandboxTools.add(code);
            }
        }
        return sandboxTools;
    }

    /** 主动失效（供 SecurityConfigPublisher 事件回调） */
    public void invalidate(Long tenantId) {
        policyCache.invalidate(tenantId);
        log.info("SandboxPolicyResolver: 缓存已失效 tenantId={}", tenantId);
    }

    private Map<String, Boolean> loadTenantPolicies(Long tenantId) {
        // 双保险：WebFlux publishOn/subscribeOn 切换线程后 ThreadLocal 租户上下文会丢失，
        // SandboxPolicyResolver 被 Reactor boundedElastic 线程池中的 ToolExecutor 触发时，
        // MyBatis-Plus 租户插件 fail-closed 抛 IllegalStateException 导致所有工具执行链路崩溃。
        // 即使 sec_sandbox_policy 已加入 TENANT_IGNORE_TABLES（插件不拦截），也显式 bind/clear
        // 防止未来改表结构后再次踩同样的坑。
        TenantContextHolder.bind(tenantId);
        try {
            // 平台基线（tenant_id=0，种子预置 27 工具）+ 租户覆盖（tenant_id=X，管理页创建）合并：
            // 先装平台行再装租户行，租户行按 toolCode 覆盖平台基线（与 admin 列表
            // "in (0, tenantId)" 的展示语义及 update/delete 仅限本级行的权限语义对齐）。
            List<SandboxPolicy> list = sandboxPolicyMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SandboxPolicy>()
                            .in(SandboxPolicy::getTenantId, 0L, tenantId)
                            .eq(SandboxPolicy::getEnabled, true)
                            .orderByAsc(SandboxPolicy::getTenantId)
            );
            Map<String, Boolean> map = new HashMap<>();
            for (SandboxPolicy p : list) {
                map.put(p.getToolCode(), p.getSandboxExecution());
            }
            log.debug("SandboxPolicyResolver: 加载 tenantId={} 策略 {} 条（含平台基线）", tenantId, map.size());
            return map.isEmpty() ? null : map;
        } finally {
            TenantContextHolder.clear();
        }
    }
}