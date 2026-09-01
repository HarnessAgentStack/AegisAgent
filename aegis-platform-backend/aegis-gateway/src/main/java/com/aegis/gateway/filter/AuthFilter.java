package com.aegis.gateway.filter;

import com.aegis.core.jwt.JwtPayload;
import com.aegis.core.jwt.JwtProperties;
import com.aegis.core.jwt.JwtUtil;
import com.aegis.gateway.config.AegisGatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 认证过滤器：JWT 解析 + 白名单放行 + 身份注入。
 */
@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String HEADER_TENANT_ID = "X-Tenant-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USERNAME = "X-Username";
    public static final String HEADER_ROLES = "X-Roles";
    public static final String HEADER_PERMISSIONS = "X-Permissions";

    private final AegisGatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final JwtProperties jwtProperties;

    public AuthFilter(AegisGatewayProperties properties, ObjectMapper objectMapper,
                      JwtProperties jwtProperties) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jwtProperties = jwtProperties;
        log.info("AuthFilter 构造函数被调用, whitelist={}, jwtSecret长度={}",
                properties.getWhitelist(),
                jwtProperties.getSecret() != null ? jwtProperties.getSecret().length() : 0);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        log.info("[AuthFilter] 开始处理请求, path={}, method={}, hasAuth={}",
                path, request.getMethod(), request.getHeaders().getFirst(HEADER_AUTHORIZATION) != null);

        // 直接使用链式调用注入头信息（不改变 filter 形态）
        try {
            // 1. 白名单路径直接放行
            if (isWhitelisted(path)) {
                log.info("[AuthFilter] 白名单路径, 直接放行, path={}, method={}", path, request.getMethod());
                return chain.filter(exchange)
                        .doOnSuccess(v -> log.info("[AuthFilter] 白名单请求完成, path={}, status={}",
                                path, exchange.getResponse().getStatusCode()))
                        .doOnError(e -> log.error("[AuthFilter] 白名单请求异常, path={}, error={}", path, e.getMessage()));
            }

            // 2. 提取 Authorization 头
            String authHeader = request.getHeaders().getFirst(HEADER_AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
                log.warn("AuthFilter: 缺少认证令牌, path={}", path);
                return unauthorized(exchange, "缺少认证令牌");
            }

            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            // 3. 解析 JWT
            Claims claims = JwtUtil.parse(token, jwtProperties.getSecret());
            if (claims == null) {
                log.warn("AuthFilter: JWT 解析失败 or 过期, path={}", path);
                return unauthorized(exchange, "认证令牌无效或已过期");
            }

            // 4. 提取身份信息
            JwtPayload payload;
            try {
                payload = JwtUtil.toPayload(claims);
            } catch (Exception e) {
                log.error("AuthFilter: 解析 payload 异常, path={}, error={}", path, e.getMessage(), e);
                return unauthorized(exchange, "认证令牌解析失败");
            }
            String tenantIdStr = payload.getTenantId() != null ? String.valueOf(payload.getTenantId()) : "";
            String userIdStr = payload.getUserId() != null ? String.valueOf(payload.getUserId()) : "";
            String username = payload.getUsername() != null ? payload.getUsername() : "";
            String roles = payload.getRoles() != null ? String.join(",", payload.getRoles()) : "";
            String permissions = payload.getPermissions() != null ? String.join(",", payload.getPermissions()) : "";

            // 直接在 exchange 上添加头，然后传给 chain
            // 安全加固：先移除客户端可能伪造的身份头，再写入网关解析的可信值，
            // 防止客户端通过 X-Tenant-Id / X-User-Id 等头越权冒用身份
            ServerWebExchange mutatedExchange = exchange.mutate()
                    .request(req -> req
                            .headers(h -> {
                                h.remove(HEADER_TENANT_ID);
                                h.remove(HEADER_USER_ID);
                                h.remove(HEADER_USERNAME);
                                h.remove(HEADER_ROLES);
                                h.remove(HEADER_PERMISSIONS);
                            })
                            .header(HEADER_TENANT_ID, tenantIdStr)
                            .header(HEADER_USER_ID, userIdStr)
                            .header(HEADER_USERNAME, username)
                            .header(HEADER_ROLES, roles)
                            .header(HEADER_PERMISSIONS, permissions))
                    .build();

            log.info("AuthFilter: 注入头信息, path={}, userId={}, tenantId={}, roles={}, permsCount={}", path,
                    userIdStr, tenantIdStr, roles,
                    payload.getPermissions() != null ? payload.getPermissions().size() : 0);

            return chain.filter(mutatedExchange)
                    .doOnError(e -> log.error("AuthFilter: 下游调用异常, path={}, error={}", path, e.getMessage()))
                    .doFinally(signalType -> log.info("AuthFilter: 请求完成, path={}, signal={}", path, signalType));
        } catch (Exception e) {
            log.error("AuthFilter: 处理请求异常, path={}, error={}", path, e.getMessage(), e);
            return unauthorized(exchange, "网关内部错误: " + e.getMessage());
        }
    }

    /** 判断路径是否在白名单中 */
    private boolean isWhitelisted(String path) {
        if (properties.getWhitelist() == null) {
            log.warn("AuthFilter: whitelist 为 null");
            return false;
        }
        for (String pattern : properties.getWhitelist()) {
            if (pattern == null) continue;
            if (path.equals(pattern) || path.startsWith(pattern.replace("/**", ""))) {
                log.debug("AuthFilter: 路径 {} 匹配白名单模式 {}", path, pattern);
                return true;
            }
        }
        return false;
    }

    /** 返回401 */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("code", 401);
            body.put("message", message);
            body.put("data", null);
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public String toString() {
        return "AuthFilter{order=" + getOrder() + "}";
    }
}
