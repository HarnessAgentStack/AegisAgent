package com.aegis.core.security;

import com.aegis.core.enums.resource.ResourceType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 资源所有者权限校验注解。
 *
 * <p>用于 Controller 方法或类级别，声明当前操作需要的资源所有权校验。
 * 系统会自动从方法参数中提取资源ID，校验当前用户是否为该资源的所有者、
 * 订阅者或具有相应权限。
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 校验当前用户必须是智能体的创建者或订阅者
 * @ResourceOwner(resourceType = ResourceType.AGENT, permission = ResourcePermission.VIEW)
 * @GetMapping("/{agentId}")
 * public Result<AgentDef> getAgent(@PathVariable Long agentId) { ... }
 *
 * // 校验编辑权限
 * @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.EDIT)
 * @PutMapping("/{kbId}")
 * public Result<Void> updateKb(@PathVariable Long kbId) { ... }
 * </pre>
 *
 * <h3>校验逻辑</h3>
 * <ul>
 *   <li>创建者：资源的 authorUserId 等于当前用户ID</li>
 *   <li>订阅者：用户已订阅该资源（agent_subscription / kb_subscription 等表）</li>
 *   <li>租户管理员：TENANT_ADMIN 角色用户可管理本租户所有资源</li>
 *   <li>平台管理员：PLATFORM_ADMIN 角色用户可管理所有资源</li>
 * </ul>
 *
 *  @author wang.zhen
 * @see ResourcePermission
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceOwner {

    /**
     * 资源类型。
     */
    ResourceType resourceType();

    /**
     * 所需权限级别。
     * 默认 MANAGE 表示需要管理权限。
     */
    ResourcePermission permission() default ResourcePermission.MANAGE;

    /**
     * 资源ID在方法参数中的位置。
     * 默认 0 表示第一个参数。
     * 也可以使用参数名称指定，如 "agentId"。
     */
    String resourceIdParam() default "";

    /**
     * 是否允许创建者访问。
     * 默认 true。
     */
    boolean allowCreator() default true;

    /**
     * 是否允许订阅者访问。
     * 默认 true。
     */
    boolean allowSubscriber() default true;

    /**
     * 是否允许租户管理员访问。
     * 默认 true。
     */
    boolean allowTenantAdmin() default true;
}
