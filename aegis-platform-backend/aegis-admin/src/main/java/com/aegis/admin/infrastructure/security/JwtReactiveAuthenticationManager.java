package com.aegis.admin.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * JWT 响应式认证管理器。
 *
 * <p>校验 JWT 认证令牌，确保用户已认证。
 * 实际的 Token 解析已在 {@link JwtServerAuthenticationConverter} 中完成，
 * 此管理器主要负责认证状态的最终确认。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class JwtReactiveAuthenticationManager implements ReactiveAuthenticationManager {

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            if (jwtToken.isAuthenticated()) {
                log.debug("JWT 认证成功: userId={}, tenantId={}",
                        jwtToken.getUserId(), jwtToken.getTenantId());
                return Mono.just(authentication);
            }
        }

        return Mono.empty();
    }
}
