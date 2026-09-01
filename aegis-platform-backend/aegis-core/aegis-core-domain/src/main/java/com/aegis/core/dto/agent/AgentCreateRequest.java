package com.aegis.core.dto.agent;

import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.GovernanceTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 智能体创建请求。
 *
 * <p>由管理平面接收，创建智能体定义、初始配置与资源绑定。创建后统一进入 DRAFT 草稿态，
 * 作者本人可立即对话自用；对外可见 / 可被订阅需经审核闭环。
 *
 * <p>安全与治理统一以 {@link GovernanceTier} 治理档位表达，用户无需理解底层安全开关。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体编码，租户内唯一，创建后不可修改 */
    private String agentCode;

    /** 智能体名称 */
    private String agentName;

    /** 智能体类型：UNIVERSAL（通用，平台预置）/ APPLICATION（应用）/ SYSTEM（系统），用户不可创建 UNIVERSAL */
    private AgentType agentType;

    /** 图标URL */
    private String icon;

    /** 主题色 */
    private String color;

    /** 描述 */
    private String description;

    /** 分类 */
    private String category;

    /** 治理档位：STANDARD（默认）/ ENHANCED / STRICT，统一驱动安全策略 */
    private GovernanceTier governanceTier;

    /** 系统智能体预留副本数（仅 SYSTEM 类型使用，默认 1）。沙箱池将在审核通过后根据治理档位自动匹配。 */
    private Integer reservedReplicas;

    /** 系统提示词 */
    private String systemPrompt;

    /** 模型档位：LIGHT / STANDARD / STRONG */
    private String modelTier;

    /** 温度参数（0-2） */
    private BigDecimal temperature;

    /** 记忆策略：SESSION_LEVEL / LONG_TERM */
    private String memoryStrategy;

    /** 最大对话轮数 */
    private Integer maxTurns;

    /** 启用工具ID列表 */
    private List<Long> enabledTools;

    /** 资源绑定列表 */
    private List<BindingRequest> bindings;

    /**
     * 系统智能体 API 发布配置（仅 agentType=SYSTEM 时传递）。
     * 创建时若 apiName 未填,后端自动用 agentName + " API" 填充。
     */
    private AgentApiConfigRequest apiConfig;

    /** 租户ID（由后端从请求头 X-Tenant-Id 注入，前端不传） */
    private Long tenantId;

    /** 创建者用户ID（由后端从请求头 X-User-Id 注入，前端不传） */
    private Long authorUserId;

    /** 创建者部门ID（可选，从上下文取） */
    private Long authorDeptId;

    /**
     * 资源绑定请求。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BindingRequest implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 资源类型：SKILL / KNOWLEDGE_BASE / MCP / TOOL / DATASET */
        private String resourceType;
        /** 资源ID */
        private Long resourceId;
        /** 资源版本，固定绑定为具体版本号，动态绑定为 latest */
        private String resourceVersion;
        /** 绑定类型：FIXED / DYNAMIC */
        private String bindingType;
        /** 是否启用 */
        private Boolean enabled;
    }
}
