package com.aegis.core.context;

import com.aegis.core.enums.role.RoleCode;
import com.aegis.core.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 用户上下文持有者。
 *
 * <p>提供在代码和 SpEL 表达式中获取当前登录用户信息的统一入口。
 * 支持 WebFlux 响应式环境和传统 Servlet 环境。
 *
 * <h3>SpEL 使用示例</h3>
 * <pre>
 * // 在 @PreAuthorize 中使用
 * @PreAuthorize("@userContextHolder.currentUser().userId == #authorId or hasRole('PLATFORM_ADMIN')")
 *
 * // 使用便捷方法
 * @PreAuthorize("@userContextHolder.isResourceOwner(#resourceId, 'AGENT', 'VIEW')")
 * </pre>
 *
 *  @author wang.zhen
 */
@Slf4j
public final class UserContextHolder {

    private UserContextHolder() {
    }

    /**
     * 获取当前用户上下文。
     */
    public static UserContext currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return extractUserContext(auth);
    }

    /**
     * 从 Authentication 中提取 UserContext。
     */
    public static UserContext extractUserContext(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }

        if (auth.getDetails() instanceof UserContext) {
            return (UserContext) auth.getDetails();
        }

        if (auth.getPrincipal() instanceof UserContext) {
            return (UserContext) auth.getPrincipal();
        }

        return null;
    }

    /**
     * 获取当前用户ID。
     */
    public static Long currentUserId() {
        UserContext ctx = currentUser();
        return ctx != null ? ctx.getUserId() : null;
    }

    /**
     * 获取当前租户ID。
     */
    public static Long currentTenantId() {
        UserContext ctx = currentUser();
        return ctx != null ? ctx.getTenantId() : null;
    }

    /**
     * 获取当前用户名。
     */
    public static String currentUsername() {
        UserContext ctx = currentUser();
        return ctx != null ? ctx.getUsername() : null;
    }

    /**
     * 判断当前用户是否拥有指定角色。
     */
    public static boolean hasRole(String roleCode) {
        UserContext ctx = currentUser();
        return ctx != null && ctx.hasRole(roleCode);
    }

    /**
     * 判断当前用户是否为平台管理员。
     *
     * <p>同时识别权威种子角色（SUPER_ADMIN）和兼容别名（PLATFORM_ADMIN），
     * 以兼容历史 JWT 中的旧格式角色编码。
     */
    public static boolean isPlatformAdmin() {
        return hasRole(RoleCode.SUPER_ADMIN) || hasRole(RoleCode.PLATFORM_ADMIN);
    }

    /**
     * 判断当前用户是否为租户管理员。
     *
     * <p>同时识别权威种子角色（ENTERPRISE_ADMIN）和兼容别名（TENANT_ADMIN），
     * 以及平台管理员（SUPER_ADMIN）。
     */
    public static boolean isTenantAdmin() {
        return hasRole(RoleCode.ENTERPRISE_ADMIN)
                || hasRole(RoleCode.TENANT_ADMIN)
                || isPlatformAdmin();
    }

    /**
     * 判断当前用户是否拥有指定权限。
     */
    public static boolean hasPermission(String permission) {
        UserContext ctx = currentUser();
        return ctx != null && ctx.hasPermission(permission);
    }

    /**
     * 判断当前用户是否为资源所有者。
     * 便捷方法，供 SpEL 表达式使用。
     */
    public static boolean isResourceOwner(Long resourceId, String resourceType, String permission) {
        UserContext ctx = currentUser();
        if (ctx == null || resourceId == null) {
            return false;
        }

        if (isPlatformAdmin()) {
            return true;
        }

        if (isTenantAdmin()) {
            return true;
        }

        return false;
    }
}
