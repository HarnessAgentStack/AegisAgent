package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 执行统计事件载荷。
 * 用于流式事件中传递执行过程的统计信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionStatsPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 总耗时（毫秒） */
    private Long totalDurationMs;

    /** 工具调用总数 */
    private int toolCount;

    /** 并行组数 */
    private int parallelGroups;

    /** 思考步骤数 */
    private int thinkingSteps;
}
