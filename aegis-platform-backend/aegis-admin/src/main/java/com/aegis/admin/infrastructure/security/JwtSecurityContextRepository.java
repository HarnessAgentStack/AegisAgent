package com.aegis.admin.infrastructure.security;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * JWT 安全上下文仓库。
 *
 * <p>在响应式请求中保存和加载安全上下文，使 Spring Security
 * 的 SecurityContextHolder.getContext() 可以获取到当前认证信息。
 *
 * @author wang.zhen
 */
@Component
public class JwtSecurityContextRepository implements ServerSecurityContextRepository {

    @Override
    public Mono<Void> save(ServerWebExchange exchange, SecurityContext context) {
        // 无状态 JWT 模式，不保存上下文
        return Mono.empty();
    }

    @Override
    public Mono<SecurityContext> load(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .map(principal -> {
                    SecurityContext context = new SecurityContextImpl();
                    context.setAuthentication((org.springframework.security.core.Authentication) principal);
                    return context;
                })
                .defaultIfEmpty(new SecurityContextImpl());
    }
}
