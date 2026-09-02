package com.aegis.core.web.filter;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.jwt.JwtPayload;
import com.aegis.core.jwt.JwtProperties;
import com.aegis.core.jwt.JwtUtil;
import com.aegis.core.web.resolver.ContextArgumentResolver;
import com.alibaba.fastjson2.JSON;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Tenant context Web filter (core module).
 *
 * <p>fail-closed 安全治理：在解析 X-Tenant-Id / X-User-Id / X-Dept-Id 头的基础上，
 * 强制校验 tenantId/userId 必须存在且合法，否则返回 401 Unauthorized 拒绝请求，
 * 杜绝匿名访问与跨租户越权风险。
 *
 * <h3>白名单</h3>
 * <p>与网关 {@code SecurityConfig} 白名单对齐：
 * <ul>
 *   <li>{@code /api/admin/auth/**} - 登录认证接口（登录前无 tenantId/userId）</li>
 *   <li>{@code /api/runtime/internal/**} - 服务间内部端点（Admin→Runtime 缓存失效通知等）</li>
 *   <li>{@code /actuator/**} - 健康检查与监控探针</li>
 *   <li>{@code /favicon.ico} - 浏览器图标</li>
 * </ul>
 * 白名单内请求跳过校验，但仍尝试解析 header（供后续业务使用）。
 *
 * @author wang.zhen
 * @see TenantContextHolder
 * @see ContextArgumentResolver
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CoreTenantContextWebFilter implements WebFilter {

    private static final String HEADER_TENANT_ID = "X-Tenant-Id";
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_DEPT_ID = "X-Dept-Id";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Autowired
    private JwtProperties jwtProperties;

    /**
     * 白名单路径前缀/精确匹配（与网关 SecurityConfig 白名单对齐）。
     * 白名单内请求不强制要求 tenantId/userId（如登录、健康检查）。
     */
    private static final Set<String> WHITELIST_EXACT = Set.of(
            "/favicon.ico",
            "/api/admin/resource/mcp/services/register"
    );
    private static final Set<String> WHITELIST_PREFIX = Set.of(
            "/api/admin/auth/",
            "/actuator/",
            "/api/admin/resource/mcp/services/",
            // 服务间内部端点（如 Admin 通知 Runtime 失效模板缓存）：
            // 无用户会话上下文（WebClient 直连，不携带 X-Tenant-Id/X-User-Id），
            // 端点自身以必填参数显式声明租户，无匿名越权面
            "/api/runtime/internal/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // CORS 预检请求（OPTIONS）直接放行，不校验 tenantId/userId
        // 浏览器跨域请求会先发 OPTIONS 预检，此时无认证头，需跳过 fail-closed 校验
        if (request.getMethod() == org.springframework.http.HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        // 解析 header（无论是否白名单都尝试解析，供后续业务使用）
        String tenantIdStr = request.getHeaders().getFirst(HEADER_TENANT_ID);
        String userIdStr = request.getHeaders().getFirst(HEADER_USER_ID);
        String deptIdStr = request.getHeaders().getFirst(HEADER_DEPT_ID);

        Long tenantId = parseLong(HEADER_TENANT_ID, tenantIdStr);
        Long userId = parseLong(HEADER_USER_ID, userIdStr);
        Long deptId = parseLong(HEADER_DEPT_ID, deptIdStr);

        // 从 JWT fallback 提取 tenantId / userId（网关 AuthFilter 未注入时使用）
        if ((tenantId == null || userId == null) && !isWhitelisted(path)) {
            Long[] jwtIds = extractFromJwt(request);
            if (jwtIds != null) {
                if (tenantId == null) tenantId = jwtIds[0];
                if (userId == null) userId = jwtIds[1];
            }
        }

        // 下载接口支持 query param 认证（浏览器 <a href> 直访问不携带 header）
        // 兼容两种路径：
        //   - /api/runtime/task/download/xxx  （后端 generate_file 工具返回的标准路径）
        //   - /api/runtime/download/xxx       （LLM 偶尔会丢 task/ 前缀，也要放行）
        boolean isDownloadPath = path.startsWith("/api/runtime/task/download/")
                || path.startsWith("/api/runtime/download/");
        if (isDownloadPath && tenantId == null) {
            String qpTenant = request.getQueryParams().getFirst(HEADER_TENANT_ID);
            String qpUser = request.getQueryParams().getFirst(HEADER_USER_ID);
            if (qpTenant != null) {
                tenantId = parseLong(HEADER_TENANT_ID, qpTenant);
            }
            if (qpUser != null && userId == null) {
                userId = parseLong(HEADER_USER_ID, qpUser);
            }
        }

        // 注入到 exchange.attributes 供 ContextArgumentResolver 使用
        if (tenantId != null) {
            exchange.getAttributes().put(ContextArgumentResolver.ATTR_TENANT_ID, tenantId);
        }
        if (userId != null) {
            exchange.getAttributes().put(ContextArgumentResolver.ATTR_USER_ID, userId);
        }
        if (deptId != null) {
            exchange.getAttributes().put(ContextArgumentResolver.ATTR_DEPT_ID, deptId);
        }

        // fail-closed：非白名单请求强制校验 tenantId/userId
        if (!isWhitelisted(path)) {
            if (tenantId == null || tenantId <= 0) {
                log.warn("TenantContextWebFilter 拒绝请求（缺 X-Tenant-Id）: path={}, ip={}",
                        path, request.getRemoteAddress() != null
                                ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown");
                return rejectUnauthorized(exchange, "Missing or invalid X-Tenant-Id header");
            }
            // 下载路径放宽 userId 校验
            // generate_file 工具生成的文件 userId 为 null（租户级文件），
            // 浏览器直接访问下载 URL 时无 userId，但 tenantId 通过 query param 提供
            if (!isDownloadPath && (userId == null || userId <= 0)) {
                log.warn("TenantContextWebFilter 拒绝请求（缺 X-User-Id）: path={}, ip={}",
                        path, request.getRemoteAddress() != null
                                ? request.getRemoteAddress().getAddress().getHostAddress() : "unknown");
                return rejectUnauthorized(exchange, "Missing or invalid X-User-Id header");
            }
        }

        if (tenantId != null) {
            log.debug("TenantContextWebFilter: tenantId={}, userId={}, path={}", tenantId, userId, path);
        }

        // 关键：绑定 ThreadLocal → MyBatis-Plus TenantLineInnerInterceptor 从此取值
        // 之前只注入 exchange.attributes（供 ContextArgumentResolver 反射 @TenantId 参数），
        // 但租户插件从不读 exchange.attributes，只看 TenantContextHolder ThreadLocal，
        // 导致所有未显式写 @TenantId 的方法 SQL 被静默加 WHERE tenant_id=0 过滤。
        if (tenantId != null) {
            TenantContextHolder.bind(tenantId);
        }

        return chain.filter(exchange)
                .doFinally(signal -> {
                    // Cleanup: prevent ThreadLocal leakage on thread pool reuse
                    TenantContextHolder.clear();
                });
    }

    /**
     * 安全解析 Long 类型的 header 值。
     *
     * @param headerName header 名称（仅用于日志）
     * @param value      header 值
     * @return 解析后的 Long，null 表示缺失或解析失败
     */
    private Long parseLong(String headerName, String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("Invalid {} header: {}", headerName, value);
            return null;
        }
    }

    /**
     * 判断请求路径是否在白名单内。
     */
    private boolean isWhitelisted(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (WHITELIST_EXACT.contains(path)) {
            return true;
        }
        for (String prefix : WHITELIST_PREFIX) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 JWT Token 提取 tenantId 和 userId 作为 fallback。
     *
     * @return [tenantId, userId] 或 null
     */
    private Long[] extractFromJwt(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst(HEADER_AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            Claims claims = JwtUtil.parse(token, jwtProperties.getSecret());
            if (claims == null) {
                return null;
            }
            JwtPayload payload = JwtUtil.toPayload(claims);
            return new Long[]{payload.getTenantId(), payload.getUserId()};
        } catch (Exception e) {
            log.debug("JWT fallback 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 返回 401 Unauthorized 响应（fail-closed）。
     *
     * <p>响应体为标准 {@link Result} JSON，与全局异常处理保持一致。
     */
    private Mono<Void> rejectUnauthorized(ServerWebExchange exchange, String message) {
        Result<Void> body = Result.fail(ResultCode.UNAUTHORIZED, message);
        byte[] bytes = JSON.toJSONBytes(body);
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        exchange.getResponse().getHeaders().setContentLength(bytes.length);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
}
