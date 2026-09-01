package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * MCP Server 自注册请求 DTO（Service-to-Service）。
 *
 * <p>扩展自 {@link McpServiceCreateRequest}，增加 tools 字段用于
 * MCP Server 在自注册时同步提交工具列表，admin 端会自动将工具注册到 {@code res_tool} 表。
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

    /** 安全等级：L1~L4 */
    private String securityLevel;

    /** 状态：ACTIVE / PENDING */
    private String status;

    /** MCP 服务暴露的工具列表，admin 会自动注册到 res_tool 表 */
    private List<McpToolRegisterRequest> tools;
}
