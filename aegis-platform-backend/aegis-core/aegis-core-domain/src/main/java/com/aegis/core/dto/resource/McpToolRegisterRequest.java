package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * MCP 工具注册 DTO。
 *
 * <p>用于 MCP Server 自注册时同步提交工具元信息，
 * admin 端 {@code McpManageService.registerTools} 会将其映射到 {@code res_tool} 表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolRegisterRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工具唯一编码（在 MCP 服务范围内全局唯一） */
    private String toolCode;

    /** 工具展示名称 */
    private String toolName;

    /** 工具能力描述 */
    private String description;

    /** 工具类型：READONLY / INTERNAL_API / WRITE / EXTERNAL_NETWORK / CODE_EXEC / HIGH_RISK */
    private String toolType;

    /** 是否只读 */
    private Boolean readOnly;

    /** 输入参数 JSON Schema 字符串 */
    private String inputSchema;

    /** 输出参数 JSON Schema 字符串 */
    private String outputSchema;
}
