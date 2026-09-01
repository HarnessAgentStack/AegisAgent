package com.aegis.core.dto.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体审核请求。
 *
 * <p>审核员对处于 REVIEWING 状态的智能体进行审核，
 * 通过则版本递增并发布，驳回则填写驳回原因。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审核结论：true-通过 false-驳回 */
    private Boolean approved;

    /** 审核意见（驳回时必填） */
    private String reviewComment;

    /** 审核人ID（从上下文取，可不传） */
    private Long reviewerId;
}
