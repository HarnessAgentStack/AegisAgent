package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 思考步骤事件载荷。
 * 用于流式事件中传递AI思考过程的步骤信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThinkingStepPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 步骤序号 (1, 2, 3...) */
    private int stepIndex;

    /** 步骤标题 */
    private String stepTitle;

    /** 步骤详情（可选） */
    private String stepDetail;

    /** 状态：RUNNING / SUCCESS / FAILED */
    private String status;

    /** 耗时（毫秒） */
    private Long durationMs;
}
