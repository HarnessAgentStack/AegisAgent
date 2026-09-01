package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 技能审批请求。
 *
 * <p>用于多级审批链中的单级审批操作，支持 approve / reject 两种动作。
 * 审批级别由审批人角色与技能 visibility 决定。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillApproveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审核单ID */
    private Long reviewId;

    /** 技能ID */
    private Long skillId;

    /** 审批动作：approve（通过）/ reject（驳回） */
    private String action;

    /** 审批级别：L1（初审）/ L2（终审），对 PUBLIC 技能需要两级审批 */
    private String approveLevel;

    /** 驳回原因（仅 reject 时必填） */
    private String rejectReason;

    /** 审批人用户ID */
    private Long approverUserId;
}