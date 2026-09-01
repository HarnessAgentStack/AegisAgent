package com.aegis.core.domain.resource;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具实体
 *
 * <p>工具（Tool）是智能体可调用的原子能力单元，封装了输入输出 Schema 与调用方式，
 * 是技能编排与智能体执行的基础构建块。</p>
 *
 * <h3>核心特征</h3>
 * <ul>
 *     <li>平台级：继承自 {@link BaseEntity}，无 tenantId 隔离，全租户共享</li>
 *     <li>多来源：支持 MCP 服务同步（MCP）/ 平台原生（NATIVE）/ 自定义（CUSTOM）</li>
 *     <li>Schema 约束：通过 inputSchema 与 outputSchema 强类型约束调用参数</li>
 *     <li>安全分级：securityLevel 控制工具可用范围与数据访问权限</li>
 * </ul>
 *
 * <h3>与 MCP 的关系</h3>
 * <p>当 sourceType 为 MCP 时，mcpServiceId 指向来源 MCP 服务；
 * 平台会定期从 MCP 服务同步工具列表，自动维护 toolCount。</p>
 *
 * @author wang.zhen
 * @see BaseEntity
 * @see McpService
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_tool")
public class Tool extends BaseEntity {
    /** 工具唯一编码，全局唯一，由字母、数字、下划线组成，长度不超过 64 */
    private String toolCode;
    /** 工具展示名称，长度不超过 128 */
    private String toolName;
    /** 工具描述，长度不超过 512，说明工具能力与调用场景 */
    private String description;
    /** 工具类型：{@link ToolType}（READONLY/INTERNAL_API/WRITE/EXTERNAL_NETWORK/CODE_EXEC/HIGH_RISK），标识工具执行方式与风险等级 */
    private ToolType toolType;
    /** 来源类型：{@link ToolSourceType#BUILTIN}（平台内置）、{@link ToolSourceType#MCP}（MCP工具） */
    private ToolSourceType sourceType;
    /** 关联 MCP 服务 ID，当 sourceType 为 MCP 时指向 mcp_service.id，否则为 null */
    private Long mcpServiceId;
    /** 是否只读，true 表示工具仅查询不修改数据，false 表示有写操作，影响权限管控 */
    private Boolean readOnly;
    /** 输入参数 Schema，JSON Schema 字符串，描述工具入参结构、类型与约束 */
    private String inputSchema;
    /** 输出参数 Schema，JSON Schema 字符串，描述工具出参结构 */
    private String outputSchema;
    /** 安全等级：{@link SecurityLevel#L1}~{@link SecurityLevel#L4}，影响工具调用权限与数据访问范围 */
    private SecurityLevel securityLevel;
    /** 状态：{@link CommonStatus#NORMAL}（启用）、{@link CommonStatus#DISABLED}（禁用），管理员控制工具可用性 */
    private CommonStatus status;
}