package com.aegis.core.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 任务执行结果。
 *
 * <p>由 {@code TaskExecutionService} 在 SSE 流结束后汇总，
 * 用于后置中间件（成本统计、审计日志）的输入。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务ID */
    private String taskId;

    /** 会话ID */
    private String sessionId;

    /** 智能体ID */
    private Long agentId;

    /** 租户ID */
    private Long tenantId;

    /** 用户ID */
    private Long userId;

    /** 模型名称 */
    private String modelName;

    /** 输入 Token 数 */
    private Integer tokenInput;

    /** 输出 Token 数 */
    private Integer tokenOutput;

    /** 执行耗时（毫秒） */
    private Long latencyMs;

    /** 是否成功 */
    private Boolean success;

    /** 错误信息（失败时） */
    private String errorMessage;
}
