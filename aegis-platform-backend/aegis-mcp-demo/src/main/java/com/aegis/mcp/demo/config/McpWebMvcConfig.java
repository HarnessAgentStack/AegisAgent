package com.aegis.mcp.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MCP Demo WebMvc 配置。
 *
 * <p>确保自定义 REST 控制器能正常工作，不受 Spring AI MCP Server 自动配置影响。
 */
@Configuration
public class McpWebMvcConfig implements WebMvcConfigurer {
    // 空实现，确保默认 MVC 配置正常工作
}
