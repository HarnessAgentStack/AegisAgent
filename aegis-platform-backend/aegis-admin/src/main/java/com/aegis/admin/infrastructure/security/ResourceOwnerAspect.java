package com.aegis.admin.infrastructure.security;

import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import com.aegis.core.security.UserContext;
import com.aegis.core.context.UserContextHolder;
import com.aegis.admin.service.resource.ResourceOwnerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 资源所有者权限校验切面。
 *
 * <p>拦截标注 {@link ResourceOwner} 的 Controller 方法，在执行前校验资源所有权。
 * 校验逻辑：
 * <ol>
 *   <li>平台管理员直接放行</li>
 *   <li>租户管理员可访问本租户内所有资源</li>
 *   <li>资源创建者可访问自己创建的资源</li>
 *   <li>资源订阅者可访问已订阅的资源（限 VIEW 权限）</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ResourceOwnerAspect {

    private final ResourceOwnerService resourceOwnerService;

    /**
     * 环绕拦截标注 {@link ResourceOwner} 的方法，执行资源访问权限校验。
     */
    @Around("@annotation(resourceOwner)")
    public Object aroundResourceOwner(ProceedingJoinPoint pjp, ResourceOwner resourceOwner) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        checkPermission(resourceOwner, pjp.getArgs(), method);
        return pjp.proceed();
    }

    /**
     * 校验资源访问权限。
     *
     * @param annotation @ResourceOwner注解实例
     * @param args       方法参数数组
     * @param method     被调用的方法
     */
    public void checkPermission(ResourceOwner annotation, Object[] args, Method method) {
        if (annotation == null) {
            return;
        }

        UserContext currentUser = UserContextHolder.currentUser();
        // P0 修复：SecurityContext 未注入时，反射方法参数 @RequestHeader fallback
        // admin 服务 JwtAuthFilter 只向 HTTP Header 注入用户信息，
        // 无额外 Filter 向 SecurityContext 写入 Authentication
        if (currentUser == null) {
            currentUser = resolveFromRequestHeaders(method, args);
        }
        if (currentUser == null) {
            log.warn("资源访问被拒绝：用户未登录，方法={}", method.getName());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }

        // P0 修复：Aspect 层先绑定 TenantContext，确保后续 MyBatis-Plus 租户插件能正确取到 tenantId
        // 之前 TenantContextHolder.bind() 只在 Controller 方法里调用，Aspect 切面里 Mapper 查询会走 tenant_id=0
        if (currentUser.getTenantId() != null) {
            com.aegis.core.common.tenant.TenantContextHolder.bind(currentUser.getTenantId());
        }
        log.debug("ResourceOwnerAspect: 已绑定 TenantContext, tenantId={}, userId={}, method={}",
                currentUser.getTenantId(), currentUser.getUserId(), method.getName());

        // 平台管理员直接放行
        if (currentUser.isPlatformAdmin()) {
            log.debug("平台管理员访问资源: userId={}, method={}", currentUser.getUserId(), method.getName());
            return;
        }

        // 获取方法参数中的资源ID
        Long resourceId = resolveResourceId(annotation, args, method);

        if (resourceId == null) {
            // 资源 ID 解析失败时，非管理员一律拒绝（fail-closed）
            log.warn("资源访问被拒绝（资源ID解析失败）: userId={}, method={}", currentUser.getUserId(), method.getName());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "权限不足，无权访问此资源");
        }

        // 执行资源访问校验（显式传入 UserContext，避免 Service 内部 UserContextHolder 在 WebFlux 返回 null）
        ResourceType resourceType = annotation.resourceType();
        ResourcePermission permission = annotation.permission();

        boolean hasAccess = resourceOwnerService.checkResourceAccess(resourceId, resourceType, permission, currentUser);

        if (!hasAccess) {
            log.warn("资源访问被拒绝: userId={}, resourceType={}, resourceId={}, permission={}",
                    currentUser.getUserId(), resourceType, resourceId, permission);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "权限不足，无权访问此资源");
        }
    }

    /**
     * 解析资源ID。
     * 支持两种方式：
     * 1. 通过 resourceIdParam 指定参数名
     * 2. 默认使用第一个 Long 类型参数
     */
    private Long resolveResourceId(ResourceOwner annotation, Object[] args, Method method) {
        String paramName = annotation.resourceIdParam();

        // 如果指定了参数名
        if (paramName != null && !paramName.isEmpty()) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].getName().equals(paramName) && args[i] instanceof Long) {
                    return (Long) args[i];
                }
            }
        }

        // 默认查找第一个 Long 类型参数
        for (Object arg : args) {
            if (arg instanceof Long) {
                return (Long) arg;
            }
        }

        return null;
    }

    /**
     * 从请求上下文构造 UserContext。
     *
     * <p>admin 服务 JwtAuthFilter 只向 HTTP Header 注入 X-User-Id / X-Tenant-Id / X-Username，
     * 不向 Spring SecurityContext 写入 Authentication 对象，导致 UserContextHolder.currentUser()
     * 始终返回 null。本方法按以下优先级构造 UserContext：
     * <ol>
     *   <li>方法参数中的 {@link ServerWebExchange} / {@link ServerHttpRequest}（WebFlux 全量请求头）</li>
     *   <li>方法参数上的 @RequestHeader 注解（与 AuditAspect.extractHeaders() 做法一致）</li>
     * </ol>
     *
     * @param method Controller 方法
     * @param args   方法调用参数
     * @return 构造的 UserContext，至少包含 userId/tenantId；两者都找不到时返回 null
     */
    private UserContext resolveFromRequestHeaders(Method method, Object[] args) {
        Long userId = null;
        Long tenantId = null;
        String username = null;

        // 优先级1：从 ServerWebExchange / ServerHttpRequest 参数读取全部请求头（无需方法逐一声明 @RequestHeader）
        for (Object arg : args) {
            HttpHeaders headers = null;
            if (arg instanceof ServerWebExchange exchange) {
                headers = exchange.getRequest().getHeaders();
            } else if (arg instanceof ServerHttpRequest request) {
                headers = request.getHeaders();
            }
            if (headers != null) {
                userId = parseLongOrNull(headers.getFirst("X-User-Id"));
                tenantId = parseLongOrNull(headers.getFirst("X-Tenant-Id"));
                username = headers.getFirst("X-Username");
                break;
            }
        }

        // 优先级2：反射 @RequestHeader 方法参数（仅补充优先级1未解析到的值）
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            RequestHeader rh = parameters[i].getAnnotation(RequestHeader.class);
            if (rh == null || args[i] == null) {
                continue;
            }
            String headerName = rh.value().isEmpty() ? rh.name() : rh.value();
            switch (headerName) {
                case "X-User-Id":
                    if (userId == null) userId = parseLongOrNull(args[i].toString());
                    break;
                case "X-Tenant-Id":
                    if (tenantId == null) tenantId = parseLongOrNull(args[i].toString());
                    break;
                case "X-Username":
                    if (username == null) username = args[i].toString();
                    break;
                default:
                    break;
            }
        }

        if (userId == null) {
            return null;
        }

        log.debug("ResourceOwnerAspect: 从请求上下文构造 UserContext, userId={}, tenantId={}, method={}",
                userId, tenantId, method.getName());

        return UserContext.builder()
                .userId(userId)
                .tenantId(tenantId)
                .username(username)
                .build();
    }

    /** 解析 Long，失败返回 null */
    private Long parseLongOrNull(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
