package com.aegis.core.domain.resource;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * MCP 服务实体
 *
 * <p>MCP 服务（McpService）是平台级 MCP（Model Context Protocol）服务注册中心实体，
 * 由 MCP Server 自动注册到 ADMIN 平台，经管理员审核发布后全租户共享，
 * 为智能体提供标准化工具能力接入。</p>
 *
 * <h3>核心特征</h3>
 * <ul>
 *     <li>平台级：继承自 {@link BaseEntity}（非 TenantEntity），无 tenantId 隔离</li>
 *     <li>自动注册：MCP Server 启动时自动注册到 ADMIN 平台</li>
 *     <li>管理员审核：审核时需配置安全等级，审核通过后进入 MCP 市场</li>
 *     <li>多协议支持：支持 SSE / STDIO / HTTP 等多种 MCP 传输协议</li>
 *     <li>统一鉴权：通过 authType 与 authConfig 配置接入鉴权方式</li>
 *     <li>即订即用：用户订阅后无需审核，可立即在智能体中使用</li>
 * </ul>
 *
 * @author wang.zhen
 * @see BaseEntity
 * @see Tool
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_mcp_service")
public class McpService extends BaseEntity {
    /** MCP 服务唯一编码，全局唯一，由字母、数字、下划线组成，长度不超过 64 */
    private String mcpCode;
    /** MCP 服务展示名称，长度不超过 128 */
    private String mcpName;
    /** 服务图标 URL，可选 */
    private String icon;
    /** 服务提供方，如"官方"、"合作厂商"，标识服务来源 */
    private String provider;
    /** 服务描述，长度不超过 512，说明服务能力与适用场景 */
    private String description;
    /** 版本号，语义化版本如 1.0.0 */
    private String version;
    /** 服务接入端点，SSE/HTTP 协议为 URL，STDIO 协议为可执行命令路径 */
    private String endpoint;
    /** 传输协议：{@link McpProtocol}（MCP_1_0/HTTP_REST/GRPC） */
    private McpProtocol protocol;
    /** 鉴权类型：{@link ApiAuthType}（API_KEY/BEARER/OAUTH2/NONE），决定 authConfig 结构 */
    private ApiAuthType authType;
    /** 鉴权配置，JSON 字符串，依据 authType 不同结构不同，如 {"apiKey":"xxx"} */
    private String authConfig;
    /** 工具数量，该 MCP 服务暴露的可调用工具总数，由系统自动统计 */
    private Integer toolCount;
    /** 安全等级：{@link SecurityLevel#L1}~{@link SecurityLevel#L4}，影响服务可用范围与调用权限 */
    private SecurityLevel securityLevel;
    /** 订阅数，该服务被订阅的总次数 */
    private Integer subsCount;
    /** 状态：{@link ProviderStatus#ACTIVE}（已接入）、{@link ProviderStatus#PENDING}（待接入），管理员控制服务可用性 */
    private ProviderStatus status;
    /** 生命周期状态：{@link AgentLifeStatus#DRAFT}（草稿）、{@link AgentLifeStatus#REVIEWING}（审核中）、{@link AgentLifeStatus#PUBLISHED}（已发布）、{@link AgentLifeStatus#ARCHIVED}（已归档）、{@link AgentLifeStatus#REJECTED}（已驳回），管理审核状态机 */
    private AgentLifeStatus lifeStatus;
    /** 最近发布时间，服务首次发布或重新发布时写入 */
    private LocalDateTime publishedTime;
}