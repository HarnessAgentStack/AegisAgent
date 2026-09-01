package com.aegis.admin.infrastructure.sandbox;

import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.core.enums.sandbox.SandboxPoolType;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 沙箱池自动匹配器。
 *
 * <p>根据智能体的治理档位（GovernanceTier）自动匹配最合适的沙箱池，
 * 实现"用户选档位 → 系统自动分配池"的零配置体验。
 *
 * <h3>匹配规则</h3>
 * <ul>
 *   <li>STANDARD → 通用池（GENERAL/STANDARD），共享模式</li>
 *   <li>ENHANCED → 隔离池（ISOLATED），独占模式</li>
 *   <li>STRICT → 隔离池（ISOLATED），独占 + 物理隔离</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <ol>
 *   <li>优先租户私有池（tenantId 匹配）</li>
 *   <li>降级至系统共享池（tenantId=0）</li>
 *   <li>最终兜底：任意启用的池</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxPoolMatcher {

    private final SandboxPoolMapper poolMapper;

    /**
     * 根据治理档位匹配沙箱池。
     *
     * @param tenantId       租户ID
     * @param tier           治理档位
     * @param reservedReplicas 预留副本数（用于校验池容量）
     * @return 匹配到的沙箱池，找不到返回 null
     */
    public SandboxPool match(Long tenantId, GovernanceTier tier, Integer reservedReplicas) {
        SandboxPoolType preferredType = mapTierToPoolType(tier);

        log.info("Matching sandbox pool: tenantId={}, tier={}, preferredType={}, replicas={}",
                tenantId, tier, preferredType, reservedReplicas);

        SandboxPool pool = findPool(tenantId, preferredType, true, reservedReplicas);
        if (pool != null) {
            log.info("Matched tenant-private pool: poolCode={}, type={}", pool.getPoolCode(), pool.getPoolType());
            return pool;
        }

        pool = findPool(tenantId, preferredType, false, reservedReplicas);
        if (pool != null) {
            log.info("Matched system-shared pool: poolCode={}, type={}", pool.getPoolCode(), pool.getPoolType());
            return pool;
        }

        pool = findFallbackPool(tenantId, reservedReplicas);
        if (pool != null) {
            log.warn("No exact match found, using fallback pool: poolCode={}", pool.getPoolCode());
            return pool;
        }

        log.error("No sandbox pool available for tenantId={}, tier={}", tenantId, tier);
        return null;
    }

    /**
     * 治理档位 → 沙箱池类型映射。
     */
    private SandboxPoolType mapTierToPoolType(GovernanceTier tier) {
        if (tier == null) {
            return SandboxPoolType.STANDARD;
        }
        return switch (tier) {
            case STANDARD -> SandboxPoolType.STANDARD;
            case ENHANCED -> SandboxPoolType.ISOLATED;
            case STRICT -> SandboxPoolType.ISOLATED;
        };
    }

    /**
     * 查找指定类型的池。
     */
    private SandboxPool findPool(Long tenantId, SandboxPoolType type, boolean tenantScope, Integer reservedReplicas) {
        LambdaQueryWrapper<SandboxPool> wrapper = new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                .eq(SandboxPool::getPoolType, type);

        if (tenantScope) {
            wrapper.eq(SandboxPool::getTenantId, tenantId != null ? tenantId : 0L);
        } else {
            wrapper.eq(SandboxPool::getTenantId, 0L);
        }

        if (reservedReplicas != null && reservedReplicas > 0) {
            wrapper.apply("(max_instances IS NULL OR max_instances >= {0})", reservedReplicas);
        }

        wrapper.last("LIMIT 1");
        return poolMapper.selectOne(wrapper);
    }

    /**
     * 查找兜底池（任意启用的池）。
     */
    private SandboxPool findFallbackPool(Long tenantId, Integer reservedReplicas) {
        LambdaQueryWrapper<SandboxPool> wrapper = new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED);

        if (reservedReplicas != null && reservedReplicas > 0) {
            wrapper.apply("(max_instances IS NULL OR max_instances >= {0})", reservedReplicas);
        }

        List<SandboxPool> pools = poolMapper.selectList(wrapper);
        if (pools.isEmpty()) {
            return null;
        }

        return pools.stream()
                .filter(p -> tenantId != null && tenantId.equals(p.getTenantId()))
                .findFirst()
                .orElse(pools.get(0));
    }
}
