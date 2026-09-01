package com.aegis.runtime.service.metering;

import com.aegis.core.domain.tenant.TenantQuota;
import com.aegis.core.domain.tenant.TenantUsage;
import com.aegis.dal.mapper.tenant.TenantQuotaMapper;
import com.aegis.dal.mapper.tenant.TenantUsageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 租户配额领域服务。
 *
 * <p>收口 {@link TenantQuotaMapper} 与 {@link TenantUsageMapper} 的数据访问，
 * 供 {@code AegisSandboxCoordinator}、{@code SandboxHealthMonitor} 等集成层组件调用，
 * 避免 integration 层直接持有 DAL Mapper。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>查询租户配额配置（tenant_quota 表）</li>
 *   <li>查询或创建当日用量记录（tenant_usage 表）</li>
 *   <li>更新用量统计</li>
 *   <li>统计指定状态的实例数（用于沙箱配额校验）</li>
 * </ul>
 *
 * <p><b>注</b>：本服务关注租户级配额/用量，与预算维度服务不同，两者不可合并。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQuotaService {

    private final TenantQuotaMapper tenantQuotaMapper;
    private final TenantUsageMapper tenantUsageMapper;

    /**
     * 按租户ID查询配额配置。
     *
     * @param tenantId 租户ID
     * @return 配额配置，不存在时返回 null
     */
    public TenantQuota findQuotaByTenant(Long tenantId) {
        if (tenantId == null) {
            return null;
        }
        return tenantQuotaMapper.selectOne(
                new LambdaQueryWrapper<TenantQuota>()
                        .eq(TenantQuota::getTenantId, tenantId));
    }

    /**
     * 查询或创建当日用量记录。
     *
     * <p>若当日记录不存在，则创建一条新的用量记录并插入。
     *
     * <p>P1 MW-06 修复：原 select-then-insert 在无唯一约束保证时存在并发重复插入风险。
     * 改为 try-insert-catch-DuplicateKeyException-then-select 模式，先尝试插入，
     * 并发冲突时捕获 {@link DuplicateKeyException} 后回退查询，确保原子性。
     *
     * @param tenantId 租户ID
     * @return 当日用量记录
     */
    public TenantUsage getOrCreateTodayUsage(Long tenantId) {
        LocalDate today = LocalDate.now();
        TenantUsage usage = TenantUsage.builder()
                .tenantId(tenantId)
                .statDate(today)
                .agentCount(0)
                .resourceCount(0)
                .concurrentSessionCount(0)
                .tokenUsedToday(0L)
                .tokenUsedThisMonth(0L)
                .sandboxUsed(0)
                .build();
        // P1 MW-06 修复：先尝试插入，并发冲突时回退查询，避免 select-then-insert 竞态
        try {
            tenantUsageMapper.insert(usage);
            return usage;
        } catch (DuplicateKeyException e) {
            // 并发场景下另一线程已插入，回退查询已存在记录
            log.info("getOrCreateTodayUsage 并发冲突，回退查询: tenantId={}, statDate={}", tenantId, today);
            return tenantUsageMapper.selectOne(
                    new LambdaQueryWrapper<TenantUsage>()
                            .eq(TenantUsage::getTenantId, tenantId)
                            .eq(TenantUsage::getStatDate, today));
        }
    }

    /**
     * 更新用量记录。
     *
     * @param usage 用量记录
     */
    public void updateUsage(TenantUsage usage) {
        tenantUsageMapper.updateById(usage);
    }

    /**
     * P0 MW-04 修复：原子递增 Token 用量（DB 原子自增，避免并发超卖）。
     *
     * <p>替代原 read-then-write 模式，确保高并发下配额不会被突破。
     * 若当日记录不存在，先创建再递增。
     *
     * @param tenantId 租户ID
     * @param delta    递增量（token 数）
     */
    public void incrementTokenUsage(Long tenantId, long delta) {
        if (tenantId == null || delta <= 0) {
            return;
        }
        LocalDate today = LocalDate.now();
        // 确保当日记录存在
        getOrCreateTodayUsage(tenantId);
        int rows = tenantUsageMapper.incrementTokenUsage(tenantId, today, delta);
        if (rows == 0) {
            log.warn("incrementTokenUsage 影响行数为0: tenantId={}, statDate={}", tenantId, today);
        }
    }
}
