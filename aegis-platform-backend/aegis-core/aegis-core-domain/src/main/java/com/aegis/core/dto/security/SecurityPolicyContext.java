package com.aegis.core.dto.security;

import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.ResourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 策略评估上下文（统一输入）。
 *
 * <p>封装安全引擎评估所需的全部输入维度：主体属性、客体属性、动作类型、内容、环境信息。
 * 所有中间件与工具层通过构造此上下文并调用 {@code AegisSecurityPolicyEngine} 完成策略评估。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityPolicyContext {

    // ==================== 主体 ====================

    /** 租户ID */
    private Long tenantId;

    /** 智能体ID */
    private Long agentId;

    /** 智能体治理档位 */
    private GovernanceTier governanceTier;

    /** 用户ID（HITL 审批关联） */
    private Long userId;

    /** 用户角色编码列表（身份级策略豁免，如 SECURITY_OFFICER 可访问高密级资源） */
    private List<String> userRoles;

    /** 用户部门ID（部门级策略） */
    private Long userDeptId;

    // ==================== 客体 ====================

    /** 资源类型（TOOL/SKILL/KNOWLEDGE_BASE/MCP_SERVICE/AGENT） */
    private ResourceType resourceType;

    /** 资源实例ID */
    private Long resourceId;

    /** 资源编码（toolCode/skillCode 等） */
    private String resourceCode;

    /** 资源安全等级 */
    private SecurityLevel resourceLevel;

    // ==================== 动作 ====================

    /** 动作类型 */
    private Action action;

    // ==================== 内容 ====================

    /** 原始内容（用户消息 / 模型输出 / 工具参数 / URL 等） */
    private String content;

    /** 内容摘要（审计用，截断版） */
    private String contentSummary;

    // ==================== 环境 ====================

    /** 会话ID */
    private String sessionId;

    /** Trace ID（全链路追踪） */
    private String traceId;

    /** 当前已有阻断次数（用于升级决策） */
    private Integer blockCount;

    /** 是否已有 HITL 审批工单 */
    private Boolean hasPendingApproval;

    /** 外部扩展属性（JSON，供自定义策略使用） */
    private String extAttributes;

    // ==================== 动作枚举 ====================

    /** 策略评估动作类型 */
    public enum Action {
        /** 用户输入 */
        INPUT,
        /** 模型输出 */
        OUTPUT,
        /** 工具调用 */
        TOOL_CALL,
        /** 模型调用（路由选择） */
        MODEL_CALL,
        /** 知识库检索 */
        KB_RETRIEVE,
        /** 网络访问 */
        NETWORK_ACCESS
    }
}
