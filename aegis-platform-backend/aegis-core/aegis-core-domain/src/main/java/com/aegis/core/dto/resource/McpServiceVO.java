package com.aegis.core.dto.resource;

import com.aegis.core.domain.resource.ResourceReview;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.common.SecurityLevel;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 服务视图对象。
 *
 * <p>排除 authConfig 敏感字段，用于对外展示。
 *
 * <p>所有 Long 类型 ID 字段通过 {@code @JsonSerialize(ToStringSerializer)} 序列化为字符串，
 * 防止前端 JavaScript Number 精度丢失（雪花ID超过 JS Number.MAX_SAFE_INTEGER）。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServiceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 服务ID（雪花ID，序列化为字符串防止JS精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** MCP 服务唯一编码 */
    private String mcpCode;

    /** MCP 服务展示名称 */
    private String mcpName;

    /** 服务图标 URL */
    private String icon;

    /** 服务提供方 */
    private String provider;

    /** 服务描述 */
    private String description;

    /** 版本号 */
    private String version;

    /** 服务接入端点 */
    private String endpoint;

    /** 传输协议 */
    private McpProtocol protocol;

    /** 鉴权类型 */
    private ApiAuthType authType;

    /** 工具数量 */
    private Integer toolCount;

    /** 安全等级 */
    private SecurityLevel securityLevel;

    /** 订阅数 */
    private Integer subsCount;

    /** 当前用户是否已订阅（运行时计算，仅市场查询时填充） */
    private Boolean subscribed;

    /** 状态：{@link ProviderStatus#ACTIVE} / {@link ProviderStatus#PENDING} */
    private ProviderStatus status;

    /** 生命周期状态：{@link AgentLifeStatus#DRAFT} / {@link AgentLifeStatus#REVIEWING} / {@link AgentLifeStatus#PUBLISHED} / {@link AgentLifeStatus#ARCHIVED} / {@link AgentLifeStatus#REJECTED} */
    private AgentLifeStatus lifeStatus;

    /** 最近发布时间 */
    private LocalDateTime publishedTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 关联工具列表（详情页填充） */
    private List<ToolVO> tools;

    /** 关联审核记录列表（详情页填充） */
    private List<ResourceReview> reviews;

    /** 工具名称摘要（列表页填充，最多5个工具名称预览） */
    private List<String> toolPreview;

    /** 最后一次工具同步时间 */
    private LocalDateTime lastSyncTime;
}
