package com.aegis.runtime.service.security;

import com.aegis.core.domain.org.User;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.dal.mapper.org.UserMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 用户状态缓存（runtime 侧）。
 *
 * <p>对话前校验用户是否被禁用。基于 Caffeine 本地缓存（TTL 60s），
 * admin 禁用用户后最长 60s 内 runtime 生效。查 DB 走 UserMapper（跨租户按 ID 查）。
 *
 * <p>fail-open 策略：用户不存在或 DB 异常时放行（由后续业务校验兜底），
 * 仅 DISABLED 状态拒绝对话。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusCache {

    private final UserMapper userMapper;

    /** 用户ID → 是否禁用，TTL 60s */
    private final Cache<Long, Boolean> disabledCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .build();

    /**
     * 判断用户是否已被禁用。
     *
     * @param userId 用户ID
     * @return true=已禁用（拒绝对话），false=正常或不存在（放行）
     */
    public boolean isDisabled(Long userId) {
        if (userId == null || userId <= 0) {
            return false;
        }
        Boolean cached = disabledCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        try {
            User user = userMapper.selectById(userId);
            boolean disabled = user != null && user.getStatus() == CommonStatus.DISABLED;
            disabledCache.put(userId, disabled);
            return disabled;
        } catch (Exception e) {
            log.warn("查询用户状态失败（fail-open 放行）: userId={}", userId, e);
            return false;
        }
    }

    /**
     * 主动失效缓存（供 admin 侧禁用/启用后通过 MQ 通知调用，当前为本地缓存兜底 TTL）。
     *
     * @param userId 用户ID
     */
    public void invalidate(Long userId) {
        if (userId != null) {
            disabledCache.invalidate(userId);
        }
    }
}
