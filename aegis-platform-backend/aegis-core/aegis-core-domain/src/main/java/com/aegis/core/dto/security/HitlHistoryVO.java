package com.aegis.core.dto.security;

import com.aegis.core.enums.security.HitlAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * HITL 历史视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HitlHistoryVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 历史记录ID */
    private Long id;

    /** 租户ID */
    private Long tenantId;

    /** HITL 节点 ID */
    private Long nodeId;

    /** 智能体 ID */
    private Long agentId;

    /** 会话 ID */
    private String sessionId;

    /** 审批动作：APPROVE / REJECT / MODIFY / TIMEOUT */
    private HitlAction action;

    /** 操作人用户 ID */
    private Long operatorUserId;

    /** 操作人姓名 */
    private String operatorName;

    /** 操作详情，JSON 字符串 */
    private String detail;

    /** 发生时间 */
    private LocalDateTime occurTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
