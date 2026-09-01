package com.aegis.core.dto.agent;

import com.aegis.core.enums.agent.GovernanceTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 智能体更新请求。
 *
 * <p>仅 DRAFT 与 REJECTED 状态的智能体可更新主体字段；
 * PUBLISHED 状态需先创建新版本草稿再更新。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体名称 */
    private String agentName;

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

    /** 系统智能体部署目标沙箱池编码（仅 SYSTEM 类型使用） */
    private String deploymentPoolCode;

    /** 系统智能体预留副本数（仅 SYSTEM 类型使用） */
    private Integer reservedReplicas;

    /** 系统提示词 */
    private String systemPrompt;

    /** 模型档位 */
    private String modelTier;

    /** 温度参数 */
    private BigDecimal temperature;

    /** 记忆策略 */
    private String memoryStrategy;

    /** 最大对话轮数 */
    private Integer maxTurns;

    /** 启用工具ID列表 */
    private List<Long> enabledTools;

    /** 资源绑定列表（整体替换）。null 表示不修改,空列表表示清空全部绑定 */
    private List<AgentCreateRequest.BindingRequest> bindings;

    /** 系统智能体 API 发布配置（仅 agentType=SYSTEM 时传递,整体替换）。null 表示不修改 */
    private AgentApiConfigRequest apiConfig;
}
