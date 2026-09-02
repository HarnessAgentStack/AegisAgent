package com.aegis.runtime.service.policy;

import com.aegis.core.domain.model.ModelRateLimit;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.model.RateLimitAction;
import com.aegis.core.enums.model.RateLimitScope;
import com.aegis.dal.mapper.model.ModelRateLimitMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模型限流检查器。
 *
 * <p>基于 Redis ZSET 滑动窗口实现租户级模型调用 QPS 限流，
 * 防止单一对象过度消耗模型资源，保障平台整体稳定性。
 *
 * <h3>限流机制</h3>
 * <ul>
 *   <li>查询 ModelRateLimit 配置，按 scope + scopeTargetId 匹配限流对象</li>
 *   <li>Redis ZSET 滑动窗口计数：以时间戳为 score，1 秒窗口内请求计数</li>
 *   <li>超限按 action 处理：ALERT→放行+日志告警，LIMIT→拒绝，PASS→放行</li>
 * </ul>
 *
 * <h3>Redis Key 设计</h3>
 * <pre>rate_limit:{tenantId}:{scope}:{scopeTargetId}:{tier}</pre>
 *
 * @author wang.zhen
 * @see ModelRateLimit
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;
    private final ModelRateLimitMapper modelRateLimitMapper;

    /** Redis 限流 key 前缀 */
    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";
    /** 滑动窗口大小（毫秒） */
    private static final long WINDOW_SIZE_MS = 1000L;
    /** key 过期时间 */
    private static final Duration KEY_TTL = Duration.ofSeconds(2);

    /**
     * P1 SEC-07 修复：原子限流 Lua 脚本。
     *
     * <p>将 removeRangeByScore → zCard → 比较 → add → expire 封装为单次 Redis 往返的原子操作，
     * 避免多命令间的竞态导致限流不准（并发可绕过限流）。
     * <ul>
     *   <li>KEYS[1]=限流 key</li>
     *   <li>ARGV[1]=now（score），ARGV[2]=windowStart，ARGV[3]=qpsLimit，ARGV[4]=member，ARGV[5]=ttlMillis</li>
     *   <li>返回 1=允许并已记录，0=超限未记录</li>
     * </ul>
     */
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        // P1 SEC-07 修复：原子完成清理过期成员、计数、比较、写入、设置TTL
        RATE_LIMIT_SCRIPT.setScriptText(
                "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[2])) " +
                "local count = redis.call('ZCARD', KEYS[1]) " +
                "if count >= tonumber(ARGV[3]) then return 0 end " +
                "redis.call('ZADD', KEYS[1], tonumber(ARGV[1]), ARGV[4]) " +
                "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) " +
                "return 1"
        );
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    /**
     * 检查模型调用是否被限流。
     *
     * @param tenantId      租户ID
     * @param tier          模型层级（LIGHT/STANDARD/STRONG）
     * @param scope         限流作用域（PLATFORM/DEPT/USER）
     * @param scopeTargetId 限流对象ID（部门/用户ID，PLATFORM 时为 null）
     * @return true 表示允许调用，false 表示被限流
     */
    public boolean check(Long tenantId, String tier, String scope, Long scopeTargetId) {
        RateLimitScope rateLimitScope;
        try {
            rateLimitScope = RateLimitScope.valueOf(scope);
        } catch (IllegalArgumentException e) {
            log.warn("限流检查失败：无效的限流作用域 scope={}", scope);
            return true;
        }

        // 查询限流配置
        ModelRateLimit config = modelRateLimitMapper.selectOne(
                new LambdaQueryWrapper<ModelRateLimit>()
                        .eq(ModelRateLimit::getTenantId, tenantId)
                        .eq(ModelRateLimit::getScope, rateLimitScope)
                        .eq(scopeTargetId != null, ModelRateLimit::getScopeTargetId, scopeTargetId)
                        .last("LIMIT 1"));

        if (config == null) {
            // 无限流配置，放行
            return true;
        }

        // 获取对应层级的 QPS 限制
        int qpsLimit = getQpsLimit(config, tier);
        if (qpsLimit <= 0) {
            return true;
        }

        // Redis ZSET 滑动窗口计数
        String key = buildKey(tenantId, scope, scopeTargetId, tier);
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_SIZE_MS;
        // P1 SEC-07 修复：member 使用 now + 随机数，避免同毫秒请求 ZSET 成员互相覆盖
        String member = now + "-" + ThreadLocalRandom.current().nextLong();

        try {
            // P1 SEC-07 修复：用 Lua 脚本原子完成清理→计数→比较→写入→设置TTL，避免多命令竞态
            Long allowed = redisTemplate.execute(RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(now),
                    String.valueOf(windowStart),
                    String.valueOf(qpsLimit),
                    member,
                    String.valueOf(KEY_TTL.toMillis()));
            if (allowed == null || allowed == 0) {
                // 超限，按 action 处理（count 仅用于日志，单独查询即可）
                Long count = redisTemplate.opsForZSet().zCard(key);
                return handleExceeded(config.getAction(), tenantId, tier, scope, scopeTargetId, count);
            }
            return true;
        } catch (Exception e) {
            log.error("限流检查异常（fail-closed 拒绝）: key={}", key, e);
            return false;
        }
    }

    /**
     * 根据模型层级获取 QPS 限制值。
     */
    private int getQpsLimit(ModelRateLimit config, String tier) {
        ModelTier modelTier;
        try {
            modelTier = ModelTier.valueOf(tier);
        } catch (IllegalArgumentException e) {
            return 0;
        }
        switch (modelTier) {
            case LIGHT:
                return config.getLightQps() != null ? config.getLightQps() : 0;
            case STANDARD:
                return config.getStandardQps() != null ? config.getStandardQps() : 0;
            case STRONG:
                return config.getStrongQps() != null ? config.getStrongQps() : 0;
            default:
                return 0;
        }
    }

    /**
     * 超限处理：按 action 决定是否放行。
     */
    private boolean handleExceeded(RateLimitAction action, Long tenantId, String tier,
                                    String scope, Long scopeTargetId, Long count) {
        switch (action) {
            case ALERT:
                log.warn("模型调用超限告警（放行）: tenantId={}, tier={}, scope={}, targetId={}, count={}",
                        tenantId, tier, scope, scopeTargetId, count);
                return true;
            case LIMIT:
                log.info("模型调用被限流: tenantId={}, tier={}, scope={}, targetId={}, count={}",
                        tenantId, tier, scope, scopeTargetId, count);
                return false;
            case PASS:
                return true;
            default:
                return true;
        }
    }

    /**
     * 构建 Redis 限流 key。
     */
    private String buildKey(Long tenantId, String scope, Long scopeTargetId, String tier) {
        return RATE_LIMIT_KEY_PREFIX + tenantId + ":" + scope + ":"
                + (scopeTargetId != null ? scopeTargetId : "0") + ":" + tier;
    }
}
