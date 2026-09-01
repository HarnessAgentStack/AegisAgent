package com.aegis.admin.infrastructure.security;

import com.aegis.core.enums.role.RoleCode;
import com.aegis.core.security.UserContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * JWT 认证令牌。
 *
 * <p>封装从 JWT Token 中解析的用户信息和权限，
 * 作为 Spring Security 认证流程中的认证对象。
 *
 * <p>buildAuthorities() 负责将 DB 角色编码（权威来源：org_role 种子数据）
 * 映射为 Spring Security authority。所有角色自动获得 ROLE_{角色编码} 基础权限，
 * 同时按角色层级追加"别名"，使 SecurityConfig 中 hasRole("PLATFORM_ADMIN") /
 * hasRole("TENANT_ADMIN") 路径授权能正确生效。
 *
 * <h3>角色别名映射表</h3>
 * <ul>
 *   <li>SUPER_ADMIN → ROLE_SUPER_ADMIN + ROLE_PLATFORM_ADMIN + ROLE_TENANT_ADMIN + ROLE_SECURITY_ADMIN + ROLE_RESOURCE_ADMIN</li>
 *   <li>ENTERPRISE_ADMIN → ROLE_ENTERPRISE_ADMIN + ROLE_TENANT_ADMIN</li>
 *   <li>SECURITY_ADMIN → ROLE_SECURITY_ADMIN</li>
 *   <li>RESOURCE_ADMIN → ROLE_RESOURCE_ADMIN</li>
 *   <li>AGENT_REVIEWER → ROLE_AGENT_REVIEWER</li>
 *   <li>AGENT_CREATOR → ROLE_AGENT_CREATOR</li>
 *   <li>EMPLOYEE → ROLE_EMPLOYEE</li>
 * </ul>
 *
 * @author wang.zhen
 */
public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final UserContext userContext;
    private final String token;

    public JwtAuthenticationToken(UserContext userContext, String token) {
        super(buildAuthorities(userContext));
        this.userContext = userContext;
        this.token = token;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return userContext.getUsername();
    }

    @Override
    public Object getCredentials() {
        return token;
    }

    @Override
    public Object getDetails() {
        return userContext;
    }

    public UserContext getUserContext() {
        return userContext;
    }

    public String getToken() {
        return token;
    }

    public Long getUserId() {
        return userContext.getUserId();
    }

    public Long getTenantId() {
        return userContext.getTenantId();
    }

    /**
     * 根据 UserContext 的角色列表构建 Spring Security authorities。
     *
     * <p>每个 DB 角色编码自动获得 ROLE_{编码} 基础权限，同时追加
     * 跨角色别名使 SecurityConfig 路径授权保持兼容。
     */
    private static Collection<GrantedAuthority> buildAuthorities(UserContext ctx) {
        List<String> roles = ctx.getRoles();
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }

        List<GrantedAuthority> authorities = new ArrayList<>();

        // 1. 基础映射：每个 DB 角色 → ROLE_{角色编码}
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }

        // 2. 兼容别名映射：追加 SecurityConfig hasRole() 所需的跨角色 authority
        //    SUPER_ADMIN → 平台最高权限别名（同时具备租户/安全/资源管理能力）
        if (roles.contains(RoleCode.SUPER_ADMIN)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + RoleCode.PLATFORM_ADMIN));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + RoleCode.TENANT_ADMIN));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + RoleCode.SECURITY_ADMIN));
            authorities.add(new SimpleGrantedAuthority("ROLE_" + RoleCode.RESOURCE_ADMIN));
        }

        //    ENTERPRISE_ADMIN → 租户管理权限别名
        if (roles.contains(RoleCode.ENTERPRISE_ADMIN)) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + RoleCode.TENANT_ADMIN));
        }

        return authorities;
    }
}
