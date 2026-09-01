package com.aegis.core.dto.agent;

import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.GovernanceTier;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体视图对象。
 *
 * <p>管理平面与运行平面统一对外暴露的智能体视图，
 * 包含智能体定义、当前版本配置与绑定摘要。
 *
 * <p>所有 Long 类型 ID 字段通过 {@code @JsonSerialize(ToStringSerializer)} 序列化为字符串，
 * 防止前端 JavaScript Number 精度丢失（雪花ID超过 JS Number.MAX_SAFE_INTEGER）。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体ID（雪花ID，序列化为字符串防止JS精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 租户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 智能体编码 */
    private String agentCode;

    /** 智能体名称 */
    private String agentName;

    /** 智能体类型 */
    private AgentType agentType;

    /** 图标URL */
    private String icon;

    /** 主题色 */
    private String color;

    /** 描述 */
    private String description;

    /** 分类 */
    private String category;

    /** 治理档位 */
    private GovernanceTier governanceTier;

    /** 生命周期状态 */
    private AgentLifeStatus lifeStatus;

    /** 当前版本号 */
    private String version;

    /** 创建者用户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorUserId;

    /** 订阅数（客观排序参考，非评分） */
    private Integer subsCount;

    /** 当前用户是否已订阅（详情/市场列表返回） */
    private Boolean subscribed;

    /** 发布时间 */
    private LocalDateTime publishedTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 系统提示词（详情接口返回） */
    private String systemPrompt;

    /** 模型档位（详情接口返回） */
    private String modelTier;

    /** 温度（详情接口返回） */
    private BigDecimal temperature;

    /** 最大对话轮数（详情接口返回） */
    private Integer maxTurns;

    /** 记忆策略（详情接口返回） */
    private String memoryStrategy;

    /** 启用工具ID列表 JSON 数组字符串（详情接口返回） */
    private String enabledTools;

    /** 资源绑定列表（详情接口返回） */
    private List<BindingVO> bindings;

    /**
     * 绑定关系视图。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BindingVO implements Serializable {
        private static final long serialVersionUID = 1L;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long id;
        private String resourceType;
        @JsonSerialize(using = ToStringSerializer.class)
        private Long resourceId;
        private String resourceVersion;
        private String bindingType;
        private Boolean enabled;
    }
}
