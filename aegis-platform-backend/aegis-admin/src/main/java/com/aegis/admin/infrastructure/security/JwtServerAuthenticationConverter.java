package com.aegis.admin.infrastructure.security;

import com.aegis.core.jwt.JwtPayload;
import com.aegis.core.jwt.JwtProperties;
import com.aegis.core.jwt.JwtUtil;
import com.aegis.core.security.UserContext;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * JWT 服务器认证转换器。
 *
 * <p>仅从 Authorization Bearer Token 解析构建认证对象（fail-closed）。
 * 不再信任网关注入的身份 Header 作为认证凭据——Gateway 已透传原始 JWT，
 * Admin 服务必须重新解析验证，防止绕过 Gateway 直连 Admin 时伪造身份。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class JwtServerAuthenticationConverter implements ServerAuthenticationConverter {

    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        ServerWebExchange request = exchange;

        // 跳过 OPTIONS 预检请求
        if (request.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return Mono.empty();
        }

        String path = request.getRequest().getPath().value();

        // 跳过认证相关端点
        if (path.startsWith("/api/admin/auth/")) {
            return Mono.empty();
        }

        // 跳过 MCP Server 自注册端点（Service-to-Service，由 SecurityConfig permitAll）
        if (path.equals("/api/admin/resource/mcp/services/register")) {
            return Mono.empty();
        }

        // 仅从 Authorization Bearer Token 解析（fail-closed，见类注释）
        String authHeader = request.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return parseJwtToken(token);
        }

        // 无认证令牌
        return Mono.empty();
    }

    /**
     * 解析 JWT Token。
     */
    private Mono<Authentication> parseJwtToken(String token) {
        try {
            Claims claims = JwtUtil.parse(token, jwtProperties.getSecret());
            if (claims == null) {
                log.warn("JWT Token 无效或已过期");
                return Mono.empty();
            }

            JwtPayload payload = JwtUtil.toPayload(claims);
            UserContext userContext = UserContext.builder()
                    .userId(payload.getUserId())
                    .tenantId(payload.getTenantId())
                    .username(payload.getUsername())
                    .roles(payload.getRoles())
                    .permissions(payload.getPermissions())
                    .build();

            return Mono.just(new JwtAuthenticationToken(userContext, token));
        } catch (Exception e) {
            log.error("解析 JWT Token 失败: {}", e.getMessage());
            return Mono.empty();
        }
    }
}
