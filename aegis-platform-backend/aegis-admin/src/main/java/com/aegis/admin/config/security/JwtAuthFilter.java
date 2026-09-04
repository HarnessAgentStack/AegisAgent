package com.aegis.admin.config.security;

import com.aegis.core.jwt.JwtProperties;
import com.aegis.core.jwt.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

@Slf4j
@Component
@Order(-1)
public class JwtAuthFilter implements WebFilter {

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // CORS 预检请求直接放行
        if (request.getMethod() == org.springframework.http.HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        // Skip public auth endpoints（与 SecurityConfig permitAll 严格对齐）。
        // 不能 startsWith 前缀跳过——/me、/profile、/change-password 同在 /api/admin/auth/
        // 前缀下但需要 JWT 认证与 X-* 头注入，前缀跳过会导致 Controller 拿不到用户身份
        if (path.equals("/api/admin/auth/login")
                || path.equals("/api/admin/auth/refresh")
                || path.equals("/api/admin/auth/logout")) {
            return chain.filter(exchange);
        }

        // Skip MCP Server self-registration endpoint (service-to-service, no JWT required)
        if (path.equals("/api/admin/resource/mcp/services/register")) {
            return chain.filter(exchange);
        }

        // Check if X-Tenant-Id already present (gateway-injected)
        String existingTenantId = request.getHeaders().getFirst("X-Tenant-Id");
        if (existingTenantId != null && !existingTenantId.isEmpty()) {
            return chain.filter(exchange);
        }

        // Check Authorization header
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Claims claims = JwtUtil.parse(token, jwtProperties.getSecret());
            if (claims != null) {
                // Valid JWT - inject headers
                String tenantId = String.valueOf(claims.get("tenantId"));
                String userId = claims.getSubject();
                String username = claims.get("username", String.class);

                ServerHttpRequest mutated = request.mutate()
                        .header("X-Tenant-Id", tenantId)
                        .header("X-User-Id", userId)
                        .header("X-Username", username != null ? username : "")
                        .build();
                return chain.filter(exchange.mutate().request(mutated).build());
            } else {
                return unauthorizedResponse(exchange, "Token invalid or expired");
            }
        }

        // No auth at all
        return unauthorizedResponse(exchange, "Authentication required");
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"" + message + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
