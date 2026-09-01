package com.aegis.core.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import com.aegis.core.domain.org.User;
import com.aegis.core.web.resolver.ContextArgumentResolver;

/**
 * 用户ID 参数注解（WebFlux 统一上下文注入）。
 *
 * <p>由 {@code ContextArgumentResolver} 从 {@code ServerWebExchange.attributes} 解析，
 * 消除 {@code @RequestHeader("X-User-Id")} 样板代码。
 *
 * @author wang.zhen
 * @see TenantId
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface UserId {
}
