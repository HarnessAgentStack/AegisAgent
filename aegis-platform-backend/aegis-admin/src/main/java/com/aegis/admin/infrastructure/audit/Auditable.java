package com.aegis.admin.infrastructure.audit;

import com.aegis.core.enums.monitor.AuditLogType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计注解：标注于 admin 写接口方法，由 {@link AuditAspect} 切面拦截并落审计日志。
 *
 * <p>切面在方法执行后（正常返回或抛异常）写入 {@code mon_audit_log}，
 * 记录操作人、操作类型、资源类型、结果与耗时，支持安全合规追溯。
 *
 * @author wang.zhen
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {

    /** 操作类型，如 CREATE_USER / UPDATE_AGENT / DELETE_KB / APPROVE */
    String operation();

    /** 资源类型，如 USER / AGENT / SKILL / KNOWLEDGE_BASE / TENANT */
    String resourceType() default "";

    /** 日志类型，默认 SECURITY（管理操作归安全审计，保留 365 天） */
    AuditLogType logType() default AuditLogType.SECURITY;

    /** 资源ID参数名，用于记录被操作资源主键；未指定则不记录 */
    String resourceIdParam() default "";

    /** 保留天数，默认 365 天 */
    int retentionDays() default 365;
}
