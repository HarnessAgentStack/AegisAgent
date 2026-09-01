package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 轮次详情 - 会话中的一次用户请求及其完整执行链路。
 *
 * <p>每个 Round 对应一个 Trace（一次用户请求），包含多个 Step（LLM_CALL / TOOL_CALL 等）。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoundDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 轮次索引（从 0 开始） */
    private Integer roundIndex;

    /** 轮次标题（用户问题摘要） */
    private String roundTitle;

    /** 轮次类型：USER_QUERY / TOOL_EXECUTION / FINAL_RESPONSE */
    private String roundType;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 耗时(ms) */
    private Long durationMs;

    /** 模型调用次数 */
    private Integer llmCallCount;

    /** 工具调用次数 */
    private Integer toolCallCount;

    /** 输入Token */
    private Integer tokenInput;

    /** 输出Token */
    private Integer tokenOutput;

    /** 状态 */
    private String status;

    /** 步骤列表 */
    private List<StepDetail> steps;
}
