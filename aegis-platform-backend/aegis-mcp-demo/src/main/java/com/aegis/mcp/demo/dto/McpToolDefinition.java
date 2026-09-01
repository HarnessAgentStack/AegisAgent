package com.aegis.mcp.demo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * MCP 工具定义 DTO。
 *
 * <p>对齐 admin 端 {@code Tool} 实体字段，用于在 MCP 服务注册时
 * 同步上送工具元信息，admin 端 {@code McpManageService.registerTools}
 * 会将其映射到 {@code res_tool} 表。
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>toolCode - 工具唯一编码，全局唯一（在 MCP 服务范围内）</li>
 *   <li>toolName - 工具展示名称</li>
 *   <li>description - 工具能力描述</li>
 *   <li>toolType - 工具类型：READONLY / INTERNAL_API / WRITE / EXTERNAL_NETWORK / CODE_EXEC / HIGH_RISK</li>
 *   <li>readOnly - 是否只读，true 表示无写操作</li>
 *   <li>inputSchema - 输入参数 JSON Schema 字符串</li>
 *   <li>outputSchema - 输出参数 JSON Schema 字符串</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工具唯一编码（在 MCP 服务范围内全局唯一） */
    private String toolCode;

    /** 工具展示名称 */
    private String toolName;

    /** 工具能力描述 */
    private String description;

    /** 工具类型：READONLY / INTERNAL_API / WRITE / EXTERNAL_NETWORK / CODE_EXEC / HIGH_RISK */
    private String toolType;

    /** 是否只读，true 表示无写操作 */
    private Boolean readOnly;

    /** 输入参数 JSON Schema 字符串 */
    private String inputSchema;

    /** 输出参数 JSON Schema 字符串 */
    private String outputSchema;
}
