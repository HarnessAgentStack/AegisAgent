package com.aegis.core.dto.security;

import com.aegis.core.enums.security.HitlTimeoutStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * HITL 节点创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlNodeCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体 ID，关联 agent_def.id */
    private Long agentId;

    /** 节点名称，长度不超过 128 */
    private String nodeName;

    /** 触发条件，JSON 字符串 */
    private String triggerCondition;

    /** 审批人用户 ID，关联 user.id */
    private Long approverUserId;

    /** 审批角色，当 approverUserId 为空时按角色匹配 */
    private String approverRole;

    /** SLA 时限，单位小时，取值范围 1-168 */
    private Integer slaHours;

    /** 超时策略：AUTO_APPROVE / AUTO_REJECT / ESCALATE */
    private HitlTimeoutStrategy timeoutStrategy;

    /** 允许的审批操作列表（JSON 数组，如 ["APPROVE","REJECT","MODIFY"]） */
    private String allowedActions;

    /** 是否启用 */
    private Boolean enabled;
}
