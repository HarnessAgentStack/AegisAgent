package com.aegis.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 认证权限配置属性。
 *
 * <p>将原硬编码在 AuthService 中的角色-权限映射外部化到配置（可经 Nacos 热更新），
 * 消除权限列表写死源码的安全隐患。后续可平滑迁移至 role_permission 表数据库驱动加载。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code aegis.auth.admin-roles}：管理员角色编码集合（命中其一即授予管理权限）</li>
 *   <li>{@code aegis.auth.admin-permissions}：管理员权限编码列表</li>
 *   <li>{@code aegis.auth.employee-permissions}：普通员工权限编码列表</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.auth")
public class AuthProperties {

    /** 管理员角色编码集合（命中其一即授予 admin-permissions） */
    private List<String> adminRoles = List.of("SUPER_ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");

    /** 管理员权限编码列表 */
    private List<String> adminPermissions = List.of(
            "agent:view", "agent:create", "agent:edit", "agent:delete", "agent:publish",
            "resource:view", "resource:manage", "model:view", "monitor:view",
            "audit:view", "security:view", "review:view", "tenant:manage", "system:model:view");

    /** 普通员工权限编码列表 */
    private List<String> employeePermissions = List.of(
            "agent:view", "agent:create", "agent:edit", "agent:delete", "agent:publish",
            "resource:view", "resource:manage", "model:view");
}
