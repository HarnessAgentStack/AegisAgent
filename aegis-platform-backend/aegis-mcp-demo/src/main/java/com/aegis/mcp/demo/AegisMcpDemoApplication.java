package com.aegis.mcp.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Aegis MCP Demo 启动类。
 *
 * <p>该工程用于演示基于 Spring AI MCP Server 注解方式暴露 MCP 工具，并
 * 在应用启动时自动向 aegis-admin 注册 MCP 服务，模拟一个标准 MCP Server 工程。
 *
 * <p>注意：排除了 McpServerAutoConfiguration，因为它会与自定义 REST 控制器冲突。
 * MCP Server 功能由工具注解自动扫描实现。
 *
 * @author wang.zhen
 */
@SpringBootApplication
@EnableFeignClients
public class AegisMcpDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AegisMcpDemoApplication.class, args);
    }
}
