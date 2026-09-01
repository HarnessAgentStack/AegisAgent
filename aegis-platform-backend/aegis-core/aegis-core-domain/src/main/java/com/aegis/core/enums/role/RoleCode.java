package com.aegis.core.enums.role;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * 角色编码常量。
 *
 * <p>所有角色编码的唯一权威来源，与 org_role 种子数据保持一致。
 * 同时提供 Spring Security hasRole() 检查所需的"别名"映射层：
 * DB 角色 → Spring Security authority（兼容旧代码中使用的 PLATFORM_ADMIN/TENANT_ADMIN）。
 */
public final class RoleCode {

    private RoleCode() {}

    // ===== 权威角色编码（与 org_role 种子数据一致）=====
    /** 超级管理员 - 拥有所有权限 */
    public static final String SUPER_ADMIN       = "SUPER_ADMIN";
    /** 企业/租户管理员 - 租户内最高权限 */
    public static final String ENTERPRISE_ADMIN  = "ENTERPRISE_ADMIN";
    /** 安全管理员 - 安全策略/审计/脱敏 */
    public static final String SECURITY_ADMIN    = "SECURITY_ADMIN";
    /** 资源管理员 - SKILL/MCP/KB/TOOL 审核发布 */
    public static final String RESOURCE_ADMIN    = "RESOURCE_ADMIN";
    /** 智能体审核员 - 智能体发布审核 */
    public static final String AGENT_REVIEWER    = "AGENT_REVIEWER";
    /** 智能体创建者 - 创建/编辑/发布智能体 */
    public static final String AGENT_CREATOR     = "AGENT_CREATOR";
    /** 普通员工 - 使用通用智能体 */
    public static final String EMPLOYEE          = "EMPLOYEE";

    // ===== 兼容别名（旧代码中 PLATFORM_ADMIN/TENANT_ADMIN 的等价物）=====
    /** 平台管理员别名（SUPER_ADMIN 的语义等价） */
    public static final String PLATFORM_ADMIN    = "PLATFORM_ADMIN";
    /** 租户管理员别名（ENTERPRISE_ADMIN 的语义等价） */
    public static final String TENANT_ADMIN      = "TENANT_ADMIN";

    // ===== 便捷集合 =====
    /** 所有管理员角色（用于 yml auth.admin-roles 配置） */
    public static final Set<String> ALL_ADMIN_ROLES = Collections.unmodifiableSet(
        new java.util.HashSet<>(Arrays.asList(
            SUPER_ADMIN, ENTERPRISE_ADMIN, SECURITY_ADMIN,
            RESOURCE_ADMIN, AGENT_REVIEWER, AGENT_CREATOR
        ))
    );

    /** 拥有平台最高权限的角色集合 */
    public static final Set<String> PLATFORM_ADMIN_EQUIVALENTS = Collections.unmodifiableSet(
        new java.util.HashSet<>(Arrays.asList(SUPER_ADMIN))
    );

    /** 拥有租户管理权限的角色集合 */
    public static final Set<String> TENANT_ADMIN_EQUIVALENTS = Collections.unmodifiableSet(
        new java.util.HashSet<>(Arrays.asList(SUPER_ADMIN, ENTERPRISE_ADMIN))
    );
}
