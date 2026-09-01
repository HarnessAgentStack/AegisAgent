package com.aegis.runtime.service.sandbox;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.core.enums.sandbox.SandboxPoolType;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * P6-3：多沙箱池路由器。
 *
 * <p>当存在多个物理沙箱池（如 CPU密集型、IO密集型、GPU池）时，
 * 根据智能体元数据（类型、计算强度、隔离要求）智能路由到合适的池。</p>
 *
 * <h3>路由策略</h3>
 * <ol>
 *   <li>显式指定：上层 API 可通过 {@code poolCode} 参数直接指定目标池</li>
 *   <li>静态映射：按 AgentType/IsolationStrategy 映射到默认池类型</li>
 *   <li>动态负载均衡：同类型多池时，按当前负载（实例数）选择最轻池</li>
 * </ol>
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>仅路由状态为 ENABLED 的池</li>
 *   <li>租户隔离：优先选择租户私有池（tenantId > 0），不存在则回退系统共享池（tenantId=0）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxPoolRouter {

    private final SandboxPoolMapper poolMapper;

    /**
     * 按池编码查找指定池（显式路由）。
     *
     * @param tenantId 租户ID
     * @param poolCode 池编码
     * @return 池配置，不存在或未启用返回 null
     */
    public SandboxPool resolveByCode(Long tenantId, String poolCode) {
        if (poolCode == null || poolCode.isEmpty()) {
            return null;
        }
        List<SandboxPool> pools = poolMapper.selectList(new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getPoolCode, poolCode)
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                .orderByAsc(SandboxPool::getTenantId));
        if (pools.isEmpty()) {
            return null;
        }
        // 优先租户私有池
        return pools.stream()
                .filter(p -> tenantId != null && tenantId.equals(p.getTenantId()))
                .findFirst()
                .orElse(pools.get(0));
    }

    /**
     * 按智能体类型路由到默认池（静态映射）。
     *
     * @param tenantId   租户ID
     * @param agentType  智能体类型（UNIVERSAL/APPLICATION/SYSTEM）
     * @param strategy   隔离策略
     * @return 池配置
     * @throws BusinessException 无可用沙箱池时抛出，强制调用方处理而非静默 null 回退
     */
    public SandboxPool resolveByAgentMeta(Long tenantId, String agentType,
                                           com.aegis.core.enums.sandbox.IsolationStrategy strategy) {
        SandboxPoolType preferredType = mapAgentTypeToPoolType(agentType, strategy);

        // 1. 首选：租户私有 + 匹配类型
        SandboxPool pool = findPool(tenantId, preferredType, true);
        if (pool != null) {
            return pool;
        }
        // 2. 次选：系统共享 + 匹配类型
        pool = findPool(0L, preferredType, false);
        if (pool != null) {
            return pool;
        }
        // 3. 兜底：租户私有任意启用池
        pool = findPool(tenantId, null, true);
        if (pool != null) {
            return pool;
        }
        // 4. 最终兜底：系统共享任意启用池
        pool = findPool(0L, null, false);
        // P1-7：沙箱池路由强制化——无可用池时 fail-fast，禁止返回 null 由上层静默回退
        if (pool == null) {
            log.error("P1-7: 无可用沙箱池: tenantId={}, agentType={}, strategy={}, preferredType={}",
                    tenantId, agentType, strategy, preferredType);
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "无可用沙箱池，请联系管理员配置沙箱池资源。tenantId=" + tenantId
                            + ", agentType=" + agentType + ", preferredType=" + preferredType);
        }
        return pool;
    }

    /**
     * 查找指定池。
     */
    private SandboxPool findPool(Long tenantId, SandboxPoolType type, boolean tenantScope) {
        LambdaQueryWrapper<SandboxPool> wrapper = new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED);
        if (tenantScope) {
            wrapper.eq(SandboxPool::getTenantId, tenantId);
        } else {
            // 系统共享池：tenantId = 0
            wrapper.eq(SandboxPool::getTenantId, 0L);
        }
        if (type != null) {
            wrapper.eq(SandboxPool::getPoolType, type);
        }
        wrapper.last("LIMIT 1");
        return poolMapper.selectOne(wrapper);
    }

    /**
     * 智能体类型 → 池类型映射。
     */
    private SandboxPoolType mapAgentTypeToPoolType(String agentType,
                                                    com.aegis.core.enums.sandbox.IsolationStrategy strategy) {
        if (strategy == com.aegis.core.enums.sandbox.IsolationStrategy.DEDICATED_PER_SESSION) {
            return SandboxPoolType.ISOLATED;
        }
        if ("UNIVERSAL".equals(agentType)) {
            return SandboxPoolType.LIGHT;
        }
        if ("SYSTEM".equals(agentType)) {
            return SandboxPoolType.HEAVY;
        }
        return SandboxPoolType.STANDARD;
    }
}