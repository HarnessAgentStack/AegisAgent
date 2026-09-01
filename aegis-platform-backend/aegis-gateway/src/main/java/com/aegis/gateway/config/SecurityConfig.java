package com.aegis.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Security 配置：JWT 无状态认证，白名单放行，其余需认证。
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(exchanges -> exchanges
                // 白名单：登录、健康检查、Actuator
                .pathMatchers("/api/admin/auth/**").permitAll()
                .pathMatchers("/actuator/**").permitAll()
                // 其余需认证（由 AuthFilter 校验 JWT）
                .anyExchange().permitAll()  // 仍由AuthFilter做JWT校验，这里不拦截以保持Filter控制
            );
        return http.build();
    }
}
