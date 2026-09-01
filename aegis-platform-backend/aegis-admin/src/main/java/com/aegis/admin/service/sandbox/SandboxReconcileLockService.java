package com.aegis.admin.service.sandbox;

import com.aegis.core.spi.IDistributedLock;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱 Reconcile 分布式锁服务。
 *
 * <p>优先使用 {@link IDistributedLock}（Redis 锁）实现跨 JVM 实例的分布式互斥，
 * 当 Redis 不可用时自动降级为基于数据库 {@code lastReconcileTime} 的幂等性检查。</p>
 *
 * <h3>锁粒度</h3>
 * <ul>
 *   <li>池级锁：单个池的操作互斥，通过 Redis 分布式锁 + DB 幂等降级实现</li>
 * </ul>
 *
 * <h3>锁机制</h3>
 * <ol>
 *   <li>优先 Redis 锁：tryLock(waitTime=0, leaseTime=120s)，获取成功则执行 Reconcile</li>
 *   <li>DB 降级：Redis 锁不可用时，回退到 lastReconcileTime 幂等检查</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class SandboxReconcileLockService {

    private static final long RECONCILE_MIN_INTERVAL_SECONDS = 30;
    private static final long REDIS_LEASE_SECONDS = 120;
    private static final long LEADER_LEASE_SECONDS = 110;

    private final SandboxPoolMapper poolMapper;

    @Autowired(required = false)
    private IDistributedLock distributedLock;

    public SandboxReconcileLockService(SandboxPoolMapper poolMapper) {
        this.poolMapper = poolMapper;
    }

    /**
     * 检查池是否需要执行 Reconcile。
     *
     * <p>优先使用 Redis 分布式锁（tryLock 非阻塞获取），获取成功则执行；
     * 若 Redis 锁不可用，回退到 lastReconcileTime 幂等检查。</p>
     *
     * @param poolId 池 ID
     * @return 是否需要执行 Reconcile
     */
    public boolean shouldReconcile(Long poolId) {
        if (distributedLock != null) {
            try {
                return distributedLock.tryLock("reconcile:pool:" + poolId, 0, REDIS_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Redis 锁获取异常，回退到 DB 检查: poolId={}, error={}", poolId, e.getMessage());
            }
        }
        return checkLastReconcileTime(poolId);
    }

    /**
     * 标记池已完成 Reconcile。
     *
     * <p>优先释放 Redis 分布式锁，若 Redis 锁不可用则更新 lastReconcileTime。</p>
     *
     * @param poolId 池 ID
     */
    public void markReconcileComplete(Long poolId) {
        if (distributedLock != null) {
            try {
                distributedLock.unlock("reconcile:pool:" + poolId);
                return;
            } catch (Exception e) {
                log.warn("Redis 锁释放异常，回退到 DB 更新: poolId={}, error={}", poolId, e.getMessage());
            }
        }
        updateLastReconcileTime(poolId);
    }

    private boolean checkLastReconcileTime(Long poolId) {
        try {
            LocalDateTime lastReconcile = poolMapper.selectLastReconcileTime(poolId);
            if (lastReconcile == null) {
                return true;
            }
            LocalDateTime threshold = LocalDateTime.now()
                    .minusSeconds(RECONCILE_MIN_INTERVAL_SECONDS);
            return lastReconcile.isBefore(threshold);
        } catch (Exception e) {
            log.warn("检查 Reconcile 状态异常: poolId={}, error={}", poolId, e.getMessage());
            return true;
        }
    }

    private void updateLastReconcileTime(Long poolId) {
        try {
            poolMapper.updateLastReconcileTime(poolId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("更新 Reconcile 时间异常: poolId={}, error={}", poolId, e.getMessage());
        }
    }

    /**
     * 尝试获取全局 Leader 锁（多 Admin 实例部署时保证只有一个执行 Reconcile）。
     *
     * @return true 表示获取成功，当前节点为 Leader
     */
    public boolean tryAcquireLeader() {
        if (distributedLock != null) {
            try {
                return distributedLock.tryLock("sandbox:reconcile:leader", 0, LEADER_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("获取 Leader 锁异常，降级为 DB 检查: error={}", e.getMessage());
            }
        }
        return checkGlobalLastReconcileTime();
    }

    /**
     * 释放 Leader 锁。
     */
    public void releaseLeader() {
        if (distributedLock != null) {
            try {
                distributedLock.unlock("sandbox:reconcile:leader");
                return;
            } catch (Exception e) {
                log.warn("释放 Leader 锁异常，回退到 DB 更新: error={}", e.getMessage());
            }
        }
        updateGlobalLastReconcileTime();
    }

    private boolean checkGlobalLastReconcileTime() {
        try {
            LocalDateTime last = poolMapper.selectGlobalLastReconcileTime();
            if (last == null) {
                return true;
            }
            LocalDateTime threshold = LocalDateTime.now().minusSeconds(RECONCILE_MIN_INTERVAL_SECONDS);
            return last.isBefore(threshold);
        } catch (Exception e) {
            log.warn("检查全局 Reconcile 时间异常: error={}", e.getMessage());
            return true;
        }
    }

    private void updateGlobalLastReconcileTime() {
        try {
            poolMapper.updateGlobalLastReconcileTime(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("更新全局 Reconcile 时间异常: error={}", e.getMessage());
        }
    }
}