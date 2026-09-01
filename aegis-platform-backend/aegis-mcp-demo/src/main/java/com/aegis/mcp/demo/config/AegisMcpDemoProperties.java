package com.aegis.mcp.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Aegis MCP Demo 配置属性。
 *
 * <p>覆盖 MCP Server 元信息以及注册到 aegis-admin 的相关参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aegis.mcp.demo")
public class AegisMcpDemoProperties {

    /** 是否启用自动注册到 aegis-admin */
    private boolean autoRegister = true;

    /** aegis-admin 基础地址 */
    private String adminBaseUrl = "http://127.0.0.1:8082";

    /** aegis-admin 的 X-User-Id 请求头（用于注册请求身份） */
    private Long adminUserId = 1L;

    /** 注册失败时的最大重试次数（15 次 × 5s = 75s 窗口，覆盖 admin ~22s 冷启动） */
    private int maxRetries = 15;

    /** 每次重试之间的间隔（毫秒） */
    private long retryIntervalMs = 5000L;

    /** 服务端共享密钥，用于和 admin 的 X-Server-Key 简单互验 */
    private String serverKey = "";

    // ========== MCP 服务元信息（注册时上送 admin） ==========

    private String mcpCode = "aegis-mcp-demo";

    private String mcpName = "Aegis MCP Demo";

    private String icon = "/icons/mcp-demo.png";

    private String provider = "aegis";

    private String description = "A demo MCP server exposing sample tools (calculator, utility, weather) via Spring AI annotations";

    private String version = "1.0.0";

    /** 当前 MCP Server 对外暴露的端点（SSE 端点，基于 spring-ai-starter-mcp-server-webmvc 默认路径） */
    private String endpoint = "http://127.0.0.1:8083/sse";

    /** 协议：SSE / STREAMABLE_HTTP / STDIO */
    private String protocol = "SSE";

    /** 鉴权类型：NONE / API_KEY / BEARER / OAUTH2 */
    private String authType = "NONE";

    /** 鉴权配置（JSON 字符串），默认空 */
    private String authConfig = "{}";

    /** 安全等级 L1-L4，默认 L1 */
    private String securityLevel = "L1";

    /** 状态：ACTIVE / PENDING */
    private String status = "PENDING";
}
