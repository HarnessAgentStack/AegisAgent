package com.aegis.gateway.filter;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 租户解析过滤器：从 AuthFilter 注入的请求头中提取租户/用户信息，
 * 构建 TenantContext 并注入 ThreadLocal（供网关内部使用）。
 *
 * 注意：下游服务的租户ID来源是 AuthFilter 设置的 X-Tenant-Id 头，
 * 而非客户端原始传入的头（已被网关覆写）。
 */
@Slf4j
@Component
public class TenantResolveFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String tenantIdStr = request.getHeaders().getFirst("X-Tenant-Id");
        String userIdStr = request.getHeaders().getFirst("X-User-Id");

        if (tenantIdStr != null) {
            try {
                Long tenantId = Long.parseLong(tenantIdStr);
                TenantContext context = TenantContext.builder().tenantId(tenantId).build();
                TenantContextHolder.set(context);
            } catch (NumberFormatException e) {
                log.warn("Invalid X-Tenant-Id header: {}", tenantIdStr);
            }
        }

        return chain.filter(exchange)
                .doFinally(signalType -> TenantContextHolder.clear());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
