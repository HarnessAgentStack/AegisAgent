package com.aegis.core.dto.security;

import com.aegis.core.enums.security.HitlTimeoutStrategy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * HITL 节点更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlNodeUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 智能体 ID */
    private Long agentId;

    /** 节点名称 */
    private String nodeName;

    /** 触发条件 */
    private String triggerCondition;

    /** 审批人用户 ID */
    private Long approverUserId;

    /** 审批角色 */
    private String approverRole;

    /** SLA 时限，单位小时 */
    private Integer slaHours;

    /** 超时策略 */
    private HitlTimeoutStrategy timeoutStrategy;

    /** 允许的审批操作列表 */
    private String allowedActions;

    /** 是否启用 */
    private Boolean enabled;
}
