package com.aegis.core.infrastructure.redis;

import com.aegis.core.spi.IDistributedLock;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 基于 Redisson 的分布式锁实现（跨 admin / runtime 通用）。
 *
 * <p>实现 {@link IDistributedLock} 接口，使用 Redisson 的 {@link RLock}
 * 提供跨 JVM 进程的分布式互斥能力。支持可重入、自动续租（看门狗）等语义。</p>
 *
 * <p>位于 {@code aegis-core} 模块，确保 admin / runtime 均能以库方式引用。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@ConditionalOnClass(RedissonClient.class)
public class RedisDistributedLock implements IDistributedLock {

    private final RedissonClient redissonClient;
    private final String keyPrefix;

    public RedisDistributedLock(RedissonClient redissonClient,
                                @Value("${aegis.lock.key-prefix:aegis:lock:}") String keyPrefix) {
        this.redissonClient = redissonClient;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit) {
        try {
            RLock rLock = redissonClient.getLock(keyPrefix + key);
            return rLock.tryLock(waitTime, leaseTime, unit);
        } catch (Exception e) {
            log.warn("获取分布式锁失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean unlock(String key) {
        try {
            RLock rLock = redissonClient.getLock(keyPrefix + key);
            rLock.unlock();
            return true;
        } catch (Exception e) {
            log.warn("释放分布式锁失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean renew(String key, long leaseTime, TimeUnit unit) {
        try {
            RLock rLock = redissonClient.getLock(keyPrefix + key);
            if (rLock.isHeldByCurrentThread()) {
                rLock.lock(leaseTime, unit);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("续租分布式锁失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isLocked(String key) {
        try {
            RLock rLock = redissonClient.getLock(keyPrefix + key);
            return rLock.isHeldByCurrentThread();
        } catch (Exception e) {
            log.warn("查询锁状态失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }
}
