package com.aegis.core.web.resolver;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.web.annotation.DeptId;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;
import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import com.aegis.core.web.filter.CoreTenantContextWebFilter;

/**
 * Context argument resolver for TenantId / UserId / DeptId annotations.
 *
 * Reads from ServerWebExchange.attributes (pre-populated by CoreTenantContextWebFilter).
 * For TenantId, also binds ThreadLocal via TenantContextHolder.bind().
 *
 * @author wang.zhen
 */
public class ContextArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String ATTR_TENANT_ID = "aegis.context.tenantId";
    public static final String ATTR_USER_ID = "aegis.context.userId";
    public static final String ATTR_DEPT_ID = "aegis.context.deptId";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(TenantId.class)
                || parameter.hasParameterAnnotation(UserId.class)
                || parameter.hasParameterAnnotation(DeptId.class);
    }

    @Override
    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext,
                                          ServerWebExchange exchange) {
        if (parameter.hasParameterAnnotation(TenantId.class)) {
            Long tenantId = exchange.getAttribute(ATTR_TENANT_ID);
            if (tenantId != null) {
                TenantContextHolder.bind(tenantId);
            }
            return Mono.justOrEmpty(tenantId);
        }
        if (parameter.hasParameterAnnotation(UserId.class)) {
            Long userId = exchange.getAttribute(ATTR_USER_ID);
            return Mono.justOrEmpty(userId);
        }
        if (parameter.hasParameterAnnotation(DeptId.class)) {
            Long deptId = exchange.getAttribute(ATTR_DEPT_ID);
            return Mono.justOrEmpty(deptId);
        }
        return Mono.empty();
    }
}
