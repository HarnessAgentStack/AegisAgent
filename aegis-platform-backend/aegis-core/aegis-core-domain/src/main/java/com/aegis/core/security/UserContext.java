package com.aegis.core.security;

import com.aegis.core.enums.role.RoleCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 当前用户安全上下文。
 *
 * <p>在 Spring Security 认证成功后，从 JWT 中解析用户信息，
 * 并封装为此对象，供 SpEL 表达式和业务代码使用。
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>SpEL: @PreAuthorize("@userContext.userId == #userId")</li>
 *   <li>代码: UserContextHolder.currentUser()</li>
 * </ul>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    /** 用户ID */
    private Long userId;

    /** 租户ID */
    private Long tenantId;

    /** 用户名 */
    private String username;

    /** 角色列表 */
    private List<String> roles;

    /** 权限列表 */
    private List<String> permissions;

    /**
     * 判断是否为指定角色。
     */
    public boolean hasRole(String roleCode) {
        return roles != null && roles.contains(roleCode);
    }

    /**
     * 判断是否为平台管理员。
     *
     * <p>同时识别权威种子角色（SUPER_ADMIN）和兼容别名（PLATFORM_ADMIN），
     * 以兼容历史 JWT 中的旧格式角色编码。
     */
    public boolean isPlatformAdmin() {
        return hasRole(RoleCode.SUPER_ADMIN) || hasRole(RoleCode.PLATFORM_ADMIN);
    }

    /**
     * 判断是否为租户管理员。
     *
     * <p>同时识别权威种子角色（ENTERPRISE_ADMIN）和兼容别名（TENANT_ADMIN），
     * 以及平台管理员（SUPER_ADMIN）。
     */
    public boolean isTenantAdmin() {
        return hasRole(RoleCode.ENTERPRISE_ADMIN)
                || hasRole(RoleCode.TENANT_ADMIN)
                || isPlatformAdmin();
    }

    /**
     * 判断是否为创建者或订阅者（通过 permission 细粒度判断）。
     */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
