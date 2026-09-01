package com.aegis.gateway.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * SessionId 提取与路由过滤器。
 *
 * <p>从多个来源提取 sessionId，通过 DiscoveryClient 获取 Runtime 实例，
 * 基于 sessionId hash 选择目标实例，实现多副本会话粘性路由。
 *
 * <h3>路由优先级</h3>
 * <ol>
 *   <li>有 X-Session-Id → hash(sessionId) 路由到固定实例</li>
 *   <li>无 sessionId → hash(userId) 路由（若有 X-User-Id）</li>
 *   <li>无标识 → 轮询（基于时间戳）</li>
 * </ol>
 *
 * <h3>提取来源</h3>
 * <ol>
 *   <li>请求头 {@code X-Session-Id}</li>
 *   <li>路径变量（如 {@code /api/runtime/session/{id}}）</li>
 *   <li>查询参数（如 {@code ?sessionId=xxx}）</li>
 * </ol>
 *
 * <h3>故障转移</h3>
 * <ul>
 *   <li>当目标实例不可用时，Gateway 自动切换到其他实例</li>
 *   <li>实例数变化时（扩缩容），hash 重新分布，会话可能迁移</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER_SESSION_ID = "X-Session-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    private static final String RUNTIME_PATH_PREFIX = "/api/runtime/";
    private static final String RUNTIME_SERVICE_ID = "aegis-runtime";

    private final DiscoveryClient discoveryClient;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // Only process runtime paths
        if (!path.startsWith(RUNTIME_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        // Extract sessionId
        String sessionId = extractSessionId(request);

        // Get all runtime instances from Nacos
        List<ServiceInstance> instances = discoveryClient.getInstances(RUNTIME_SERVICE_ID);
        if (instances == null || instances.isEmpty()) {
            log.warn("[SessionId] No instances found for service: {}, forwarding to default", RUNTIME_SERVICE_ID);
            return chain.filter(exchange);
        }

        // Single instance (typical dev): skip hash routing, let lb:// LoadBalancerClientFilter resolve the only instance
        if (instances.size() <= 1) {
            return chain.filter(exchange);
        }

        // Select target instance based on sessionId hash
        ServiceInstance target = selectInstance(instances, sessionId, request);

        // Build target URL
        String targetBaseUrl = normalizeUrl(target.getUri().toString());
        String fullUrl = targetBaseUrl + path;

        log.info("[SessionId] Routing sessionId={} to instance={} (uri={})",
                sessionId, target.getInstanceId(), fullUrl);

        // Create new request with the target instance URL
        ServerHttpRequest newRequest = request.mutate()
                .uri(URI.create(fullUrl))
                .header(HEADER_SESSION_ID, sessionId != null ? sessionId : "")
                .build();

        return chain.filter(exchange.mutate().request(newRequest).build());
    }

    private String extractSessionId(ServerHttpRequest request) {
        // 1. From header (highest priority)
        String sessionId = request.getHeaders().getFirst(HEADER_SESSION_ID);
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }

        // 2. From path variable (e.g., /api/runtime/session/{id})
        sessionId = extractFromPath(request.getPath().value());
        if (sessionId != null) {
            return sessionId;
        }

        // 3. From query parameter (e.g., ?sessionId=xxx)
        sessionId = request.getQueryParams().getFirst("sessionId");
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }

        return null;
    }

    private String extractFromPath(String path) {
        String[] patterns = {
                "/api/runtime/session/",
                "/api/runtime/chat/",
                "/api/runtime/task/"
        };
        for (String pattern : patterns) {
            if (path.startsWith(pattern)) {
                String remaining = path.substring(pattern.length());
                int end = remaining.indexOf('/');
                int queryEnd = remaining.indexOf('?');
                if (end > 0 && queryEnd > 0) {
                    return remaining.substring(0, Math.min(end, queryEnd));
                } else if (end > 0) {
                    return remaining.substring(0, end);
                } else if (queryEnd > 0) {
                    return remaining.substring(0, queryEnd);
                } else if (!remaining.isEmpty()) {
                    return remaining;
                }
            }
        }
        return null;
    }

    /**
     * Select target instance based on sessionId/userId hash or round-robin.
     */
    private ServiceInstance selectInstance(List<ServiceInstance> instances, String sessionId, ServerHttpRequest request) {
        if (instances.size() == 1) {
            return instances.get(0);
        }

        // 1. sessionId hash (highest priority) - ensures session stickiness
        if (sessionId != null && !sessionId.isEmpty()) {
            int index = Math.abs(sessionId.hashCode() % instances.size());
            log.debug("[SessionId] hash(sessionId={}) → index={}", sessionId, index);
            return instances.get(index);
        }

        // 2. userId hash - ensures user stickiness
        String userId = request.getHeaders().getFirst(HEADER_USER_ID);
        if (userId != null && !userId.isEmpty()) {
            int index = Math.abs(userId.hashCode() % instances.size());
            log.debug("[SessionId] hash(userId={}) → index={}", userId, index);
            return instances.get(index);
        }

        // 3. Round-robin (fallback for unidentified requests)
        int index = (int) (System.currentTimeMillis() % instances.size());
        log.debug("[SessionId] round-robin → index={}", index);
        return instances.get(index);
    }

    private String normalizeUrl(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }

    @Override
    public int getOrder() {
        // Run after AuthFilter (HIGHEST_PRECEDENCE) but before TenantResolveFilter (HIGHEST_PRECEDENCE + 100)
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }
}
