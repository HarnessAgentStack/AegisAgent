package com.aegis.admin.config.security;

import com.aegis.admin.infrastructure.security.JwtAccessDeniedHandler;
import com.aegis.admin.infrastructure.security.JwtAuthenticationEntryPoint;
import com.aegis.admin.infrastructure.security.JwtReactiveAuthenticationManager;
import com.aegis.admin.infrastructure.security.JwtSecurityContextRepository;
import com.aegis.admin.infrastructure.security.JwtServerAuthenticationConverter;
import com.aegis.core.enums.role.RoleCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;

/**
 * Security 配置。
 *
 * <p>管理平面 Spring Security（响应式）配置，定义 JWT 认证、RBAC 授权与方法级权限。
 *
 * <h3>三层权限架构</h3>
 * <ul>
 *   <li>接口级：JWT + RBAC 路径授权，按角色控制接口访问</li>
 *   <li>资源级：@ResourceOwner 注解 + AOP 切面，校验资源所有权</li>
 *   <li>运行时：AgentScope PermissionSystem，工具调用安全策略（Phase 1: delegated to AgentScope PermissionSystem）</li>
 * </ul>
 *
 * <h3>路径权限矩阵</h3>
 *
 * <p>角色别名说明：
 * <ul>
 *   <li>{@code PLATFORM_ADMIN} 别名 = SUPER_ADMIN（权威种子角色）自动注入的 ROLE_PLATFORM_ADMIN</li>
 *   <li>{@code TENANT_ADMIN} 别名 = SUPER_ADMIN / ENTERPRISE_ADMIN 自动注入的 ROLE_TENANT_ADMIN</li>
 * </ul>
 *
 * <p>具体路径规则：
 * <ul>
 *   <li>/api/admin/auth/**：公开访问（登录/刷新Token等）</li>
 *   <li>/api/admin/security/**：PLATFORM_ADMIN + SECURITY_ADMIN</li>
 *   <li>/api/admin/security-admin/**：PLATFORM_ADMIN + SECURITY_ADMIN</li>
 *   <li>/api/admin/tenant/**：PLATFORM_ADMIN（= SUPER_ADMIN）</li>
 *   <li>/api/admin/audit/**：PLATFORM_ADMIN + TENANT_ADMIN（= SUPER_ADMIN + ENTERPRISE_ADMIN）</li>
 *   <li>/api/admin/review/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/model/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/role/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/user/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/department/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/agent/**：所有已认证用户</li>
 *   <li>/api/admin/skill/**：所有已认证用户</li>
 *   <li>/api/admin/kb/**：所有已认证用户</li>
 *   <li>/api/admin/mcp/**：所有已认证用户</li>
 *   <li>/api/admin/tool/**：所有已认证用户</li>
 *   <li>/api/admin/sandbox/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/budget/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/hitl/**：PLATFORM_ADMIN + TENANT_ADMIN</li>
 *   <li>/api/admin/ha/**：PLATFORM_ADMIN（= SUPER_ADMIN）</li>
 *   <li>其他所有 API：已认证用户可访问</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwtServerAuthenticationConverter authenticationConverter,
            JwtReactiveAuthenticationManager authenticationManager,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JwtAccessDeniedHandler accessDeniedHandler,
            JwtSecurityContextRepository securityContextRepository) {

        // 创建 JWT 认证过滤器
        AuthenticationWebFilter jwtFilter = new AuthenticationWebFilter(authenticationManager);
        jwtFilter.setServerAuthenticationConverter(authenticationConverter);
        jwtFilter.setSecurityContextRepository(securityContextRepository);

        http
                // 关闭 CSRF（无状态 JWT 不需要）
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // 配置 JWT 认证
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                // 设置认证入口点和访问拒绝处理器
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                // 注册 JWT 认证过滤器
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)

                // 路径授权规则
                .authorizeExchange(exchanges -> exchanges
                        // 公开端点：认证相关接口 + 健康检查 + MCP 自注册
                        .pathMatchers(
                                "/api/admin/auth/login",
                                "/api/admin/auth/refresh",
                                "/api/admin/auth/logout",
                                "/api/admin/actuator/**",
                                // MCP Server 自注册端点（供 aegis-mcp-demo 等 MCP Server 启动时自动上送元信息）
                                "/api/admin/resource/mcp/services/register"
                        ).permitAll()

                        // 安全管控：平台管理员 + 安全管理员（SECURITY_ADMIN 拥有 ROLE_SECURITY_ADMIN）
                        .pathMatchers(
                                "/api/admin/security/**",
                                "/api/admin/security-admin/**"
                        ).hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.SECURITY_ADMIN)

                        // 平台管理员专属接口（= SUPER_ADMIN）
                        .pathMatchers("/api/admin/tenant/**")
                        .hasRole(RoleCode.PLATFORM_ADMIN)

                        // 审计日志：平台管理员和租户管理员
                        .pathMatchers("/api/admin/audit/**")
                        .hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // 审核相关：平台管理员和租户管理员
                        .pathMatchers(
                                "/api/admin/review/**",
                                "/api/admin/agent-review/**",
                                "/api/admin/security-approval/**"
                        ).hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // 模型管理：平台管理员和租户管理员
                        .pathMatchers(
                                "/api/admin/model/**",
                                "/api/admin/model-admin/**"
                        ).hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // 角色和用户管理：平台管理员和租户管理员
                        .pathMatchers(
                                "/api/admin/role/**",
                                "/api/admin/user/**",
                                "/api/admin/department/**"
                        ).hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // 智能体管理：所有已认证用户
                        .pathMatchers(
                                "/api/admin/agent/**",
                                "/api/admin/agent-api/**",
                                "/api/admin/agent-lifecycle/**",
                                "/api/admin/agent-admin/**",
                                "/api/admin/agent-hitl/**"
                        ).authenticated()

                        // 技能管理：所有已认证用户
                        .pathMatchers(
                                "/api/admin/skill/**",
                                "/api/admin/skill-admin/**",
                                "/api/admin/skill-user/**",
                                "/api/admin/resource/skill/**"
                        ).authenticated()

                        // 知识库管理：所有已认证用户
                        .pathMatchers(
                                "/api/admin/kb/**",
                                "/api/admin/kb-user/**",
                                "/api/admin/resource/kb/**",
                                "/api/admin/resource/knowledge/**"
                        ).authenticated()

                        // MCP 管理：所有已认证用户
                        .pathMatchers(
                                "/api/admin/mcp/**",
                                "/api/admin/mcp-user/**",
                                "/api/admin/resource/mcp/**"
                        ).authenticated()

                        // 工具管理：所有已认证用户
                        .pathMatchers("/api/admin/tool/**")
                        .authenticated()

                        // 观测中心：平台管理员和租户管理员
                        .pathMatchers(
                                "/api/admin/observe/**"
                        ).hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // 沙箱管理：平台管理员和租户管理员
                        .pathMatchers(
                                "/api/admin/sandbox/**",
                                "/api/admin/sandbox-pool/**",
                                "/api/admin/sandbox-image/**",
                                "/api/admin/sandbox-instance/**"
                        ).hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // 预算管理：平台管理员和租户管理员
                        .pathMatchers("/api/admin/budget/**")
                        .hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // HITL 管理：平台管理员和租户管理员
                        .pathMatchers("/api/admin/hitl/**")
                        .hasAnyRole(RoleCode.PLATFORM_ADMIN, RoleCode.TENANT_ADMIN)

                        // HA 管理：平台管理员（= SUPER_ADMIN）
                        .pathMatchers("/api/admin/ha/**")
                        .hasRole(RoleCode.PLATFORM_ADMIN)

                        // 其他所有 API：已认证用户可访问
                        .anyExchange().authenticated()
                );

        return http.build();
    }
}
