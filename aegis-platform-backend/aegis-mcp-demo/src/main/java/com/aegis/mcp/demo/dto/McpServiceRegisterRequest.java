package com.aegis.mcp.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * MCP 服务注册请求 DTO。
 *
 * <p>对齐 admin 端 {@code McpServiceCreateRequest} 字段定义，
 * 同时扩展 tools 字段用于一次性提交 MCP 工具列表。
 *
 * <h3>字段映射</h3>
 * <ul>
 *   <li>mcpCode / mcpName / icon / provider / description / version / endpoint → McpService</li>
 *   <li>protocol → McpProtocol (STDIO / SSE / STREAMABLE_HTTP)</li>
 *   <li>authType → ApiAuthType (NONE / API_KEY / BEARER / OAUTH2 / BASIC)</li>
 *   <li>securityLevel → SecurityLevel (L1~L4)，admin 可后向补充</li>
 *   <li>status → ProviderStatus (ACTIVE / PENDING)</li>
 *   <li>tools → 待注册到 res_tool 表的工具列表</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServiceRegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String mcpCode;

    private String mcpName;

    private String icon;

    private String provider;

    private String description;

    private String version;

    private String endpoint;

    /** 传输协议：STDIO / SSE / STREAMABLE_HTTP */
    private String protocol;

    /** 鉴权类型：NONE / API_KEY / BEARER / OAUTH2 / BASIC */
    private String authType;

    /** 鉴权配置，JSON 字符串 */
    private String authConfig;

    /** 安全等级：L1~L4，admin 审核时可后向补充 */
    private String securityLevel;

    /** 状态：ACTIVE / PENDING */
    private String status;

    /** MCP 服务暴露的工具列表，admin 会自动注册到 res_tool 表 */
    private List<McpToolDefinition> tools;
}
