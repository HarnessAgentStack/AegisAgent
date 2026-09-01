package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Span 记录。
 *
 * <p>表示链路中的一个操作片段，覆盖 HTTP 入口、智能体编排、LLM 调用、工具调用、
 * RAG 检索、沙箱执行、人在等待、SSE 输出、记忆召回等环节。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpanRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Span 唯一ID */
    private String spanId;

    /** 所属链路ID */
    private String traceId;

    /** 父 Span ID */
    private String parentSpanId;

    /** Span 类型：HTTP_IN / AGENT_ASSEMBLY / LLM_CALL / TOOL_CALL / RAG_RETRIEVE / SANDBOX_EXEC / HITL_WAIT / SSE_OUT / MEMORY_RECALL */
    private String spanType;

    /** Span 名称 */
    private String name;

    /** 智能体ID */
    private Long agentId;

    /** 用户ID */
    private Long userId;

    /** 会话ID */
    private String sessionId;

    /** 状态：SUCCESS / FAILED / SKIPPED */
    private String status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 输入摘要 */
    private String inputSummary;

    /** 输出摘要 */
    private String outputSummary;

    /** 输入 Token 数 */
    private Integer tokenInput;

    /** 输出 Token 数 */
    private Integer tokenOutput;

    /** 错误信息 */
    private String errorMsg;

    /** 扩展元数据（JSON） */
    private String meta;

    /** 轮次索引（从 0 开始，用于工作流视图分组） */
    private Integer roundIndex;

    /** 步骤索引（轮次内的序号，从 0 开始） */
    private Integer stepIndex;

    /** 友好显示名（如 LLM 模型名：豆包标准版） */
    private String displayName;

    /** LLM 模型名称（从 meta JSON 顶层提取） */
    private String modelName;

    /** 缓存命中 Token（从 meta JSON 提取，暂无数据源时为 null） */
    private Integer cacheHitTokens;

    /** 缓存未命中 Token（从 meta JSON 提取，暂无数据源时为 null） */
    private Integer cacheMissTokens;

    /** 推理 Token（从 meta JSON 提取，暂无数据源时为 null） */
    private Integer reasoningTokens;
}