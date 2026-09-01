package com.aegis.mcp.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具注解（本地自定义，替代 Spring AI 2.0 注解）。
 *
 * <p>标记在 Spring Bean 的公开方法上，表示该方法需要暴露为 MCP 工具。
 * 由 {@code McpToolCollector} 通过反射采集元数据。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpTool {

    /**
     * 工具名称（MCP Tool Name）。
     */
    String name();

    /**
     * 工具描述。
     */
    String description() default "";
}
