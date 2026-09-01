package com.aegis.mcp.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具参数注解（本地自定义，替代 Spring AI 2.0 注解）。
 *
 * <p>标记在 {@link McpTool} 方法的参数上，提供参数的描述和是否必填信息。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpToolParam {

    /**
     * 参数描述。
     */
    String description() default "";

    /**
     * 是否必填。
     */
    boolean required() default false;
}
