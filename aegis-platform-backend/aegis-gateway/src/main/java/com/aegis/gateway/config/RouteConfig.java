package com.aegis.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关路由配置（编程式定义）。
 *
 * <p>使用 {@code lb://服务名} 通过 Nacos DiscoveryClient 解析实例地址，
 * 不再硬编码 localhost:port，支持服务实例多副本与动态发现。
 *
 * <h3>路由规则</h3>
 * <ul>
 *   <li>{@code /api/admin/**} → {@code lb://aegis-admin}</li>
 *   <li>{@code /api/resource/**} → {@code lb://aegis-admin} - 用户端资源API</li>
 *   <li>{@code /api/runtime/**} → {@code lb://aegis-runtime} (由 SessionIdFilter 实现基于 sessionId 的 sticky 路由)</li>
 * </ul>
 *
 * <p>Runtime 路由经 Spring Cloud LoadBalancer 解析为单个实例 URL 后，
 * 由 {@link com.aegis.gateway.filter.SessionIdFilter} 通过 DiscoveryClient
 * 在多副本间按 sessionId 实现一致性 hash 路由；单实例时直接放行。
 *
 * @author wang.zhen
 */
@Slf4j
@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        log.info("Loading gateway routes: /api/admin/** -> lb://aegis-admin, /api/resource/** -> lb://aegis-admin, /api/runtime/** -> lb://aegis-runtime (sticky routing via SessionIdFilter)");
        return builder.routes()
                .route("aegis-admin", r -> r
                        .path("/api/admin/**")
                        .uri("lb://aegis-admin"))
                .route("aegis-resource", r -> r
                        .path("/api/resource/**")
                        .uri("lb://aegis-admin"))
                .route("aegis-runtime", r -> r
                        .path("/api/runtime/**")
                        .uri("lb://aegis-runtime"))
                .build();
    }
}
