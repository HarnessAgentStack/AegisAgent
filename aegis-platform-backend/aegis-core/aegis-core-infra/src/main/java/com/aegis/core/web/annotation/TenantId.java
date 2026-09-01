package com.aegis.core.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.web.filter.CoreTenantContextWebFilter;
import com.aegis.core.web.resolver.ContextArgumentResolver;

/**
 * TenantId parameter annotation (WebFlux unified context injection).
 *
 * <p>Annotated on Controller method parameters, resolved by ContextArgumentResolver:
 * reads tenantId from ServerWebExchange.attributes (pre-populated by CoreTenantContextWebFilter),
 * and calls TenantContextHolder.bind(Long) to set ThreadLocal for MyBatis-Plus tenant plugin.
 *
 * <p>Replaces 162 occurrences of @RequestHeader + TenantContextHolder.bind boilerplate.
 *
 * @author wang.zhen
 * @see UserId
 * @see DeptId
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantId {
}
