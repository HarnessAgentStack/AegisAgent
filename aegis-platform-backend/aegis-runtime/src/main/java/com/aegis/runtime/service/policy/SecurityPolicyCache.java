package com.aegis.runtime.service.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 策略二级缓存（Caffeine 本地 + Redis 集群）。
 *
 * <p>策略变更时由 {@link SecurityConfigPublisher} 发布 Redis pub/sub 通知，
 * {@link SecurityPolicyCacheInvalidator} 订阅后清除本地缓存，实现 5s 内全节点刷新。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class SecurityPolicyCache {

    private final StringRedisTemplate redisTemplate;

    /** Caffeine 本地缓存：tenantId + ":" + policyType → JSON */
    private final Cache<String, String> localCache;

    /** 缓存 key 前缀 */
    private static final String CACHE_PREFIX = "aegis:security:policy:";

    /** 缓存有效期（秒） */
    private static final long EXPIRE_SECONDS = 300;

    public SecurityPolicyCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(EXPIRE_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取缓存值。
     *
     * @param tenantId   租户ID
     * @param policyType 策略类型（TOOL/CONTENT/OUTBOUND/MASK/HITL）
     * @return 缓存的 JSON 字符串，未命中返回 null
     */
    public String get(Long tenantId, String policyType) {
        String key = buildKey(tenantId, policyType);
        // 1. 本地缓存
        String local = localCache.getIfPresent(key);
        if (local != null) return local;

        // 2. Redis 缓存
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                localCache.put(key, value);
                return value;
            }
        } catch (Exception e) {
            log.warn("SecurityPolicyCache Redis 读取失败，降级为无缓存: key={}", key, e);
        }

        return null;
    }

    /**
     * 设置缓存值。
     */
    public void set(Long tenantId, String policyType, String value) {
        String key = buildKey(tenantId, policyType);
        localCache.put(key, value);
        try {
            redisTemplate.opsForValue().set(key, value, EXPIRE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("SecurityPolicyCache Redis 写入失败: key={}", key, e);
        }
    }

    /**
     * 清除指定租户 + 策略类型的缓存。
     */
    public void evict(Long tenantId, String policyType) {
        String key = buildKey(tenantId, policyType);
        localCache.invalidate(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("SecurityPolicyCache Redis 删除失败: key={}", key, e);
        }
        log.debug("SecurityPolicyCache 已清除: tenantId={}, policyType={}", tenantId, policyType);
    }

    /**
     * 清除指定租户的全部缓存。
     *
     * <p>P0-2 修复：原实现 {@code redisTemplate.delete(CACHE_PREFIX + tenantId + ":*")}
     * 把通配符 {@code :*} 当字面量 key 删除（该 key 不存在）→ Redis 侧旧数据残留，
     * 本地 Caffeine 过期（300s）后从 Redis 读回旧值，跨节点失效完全失效。
     * 现改为 SCAN 遍历 {@code aegis:security:policy:{tenantId}:*} 后逐个 delete
     * （该前缀 key 数量 ≤5，SCAN 开销可忽略，不用 keys() 避免大 keyspace 阻塞）。
     */
    public void evictAll(Long tenantId) {
        localCache.invalidateAll();
        try {
            String pattern = CACHE_PREFIX + tenantId + ":*";
            java.util.Set<String> keys = new java.util.HashSet<>();
            org.springframework.data.redis.core.ScanOptions options =
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(pattern).count(100).build();
            try (org.springframework.data.redis.core.Cursor<String> cursor =
                         redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.debug("SecurityPolicyCache Redis 批量删除: tenantId={}, keys={}", tenantId, keys.size());
            }
        } catch (Exception e) {
            log.warn("SecurityPolicyCache 全量清除失败: tenantId={}", tenantId, e);
        }
        log.debug("SecurityPolicyCache 全量清除: tenantId={}", tenantId);
    }

    private String buildKey(Long tenantId, String policyType) {
        return CACHE_PREFIX + tenantId + ":" + policyType;
    }
}
