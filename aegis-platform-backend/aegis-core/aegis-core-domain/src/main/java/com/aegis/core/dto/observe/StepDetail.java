package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 步骤详情 - 轮次中的单个执行步骤。
 *
 * <p>支持多种步骤类型：LLM_CALL、TOOL_CALL、AGENT_ASSEMBLY 等，
 * 每种类型有特定的扩展字段。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 步骤索引（轮次内从 0 开始） */
    private Integer stepIndex;

    /** Span ID */
    private String spanId;

    /** Span 类型：LLM_CALL / TOOL_CALL / AGENT_ASSEMBLY 等 */
    private String spanType;

    /** 状态 */
    private String status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 耗时(ms) */
    private Long durationMs;

    /** 友好显示名（如：豆包标准版 / web_search） */
    private String displayName;

    /** 原始名称（模型版本名或工具名） */
    private String name;

    // ===== LLM_CALL 专用字段 =====

    /** 模型名（如 doubao-pro-32k） */
    private String modelName;

    /** 模型版本（更精确的标识） */
    private String modelVersion;

    /** 输入 Token */
    private Integer tokenInput;

    /** 输出 Token */
    private Integer tokenOutput;

    /** 缓存命中 Token */
    private Integer cachedTokens;

    /** 请求上下文摘要（System 消息数、Messages 数、Tools 数） */
    private Map<String, Object> requestSummary;

    /** 请求详情（messages 列表，截断） */
    private List<Map<String, Object>> requestMessages;

    /** 请求消息是否已截断 */
    private Boolean requestMessagesTruncated;
    
    /** 请求消息原始数量（截断前） */
    private Integer requestMessagesOriginalCount;

    /** 请求中的工具定义（截断） */
    private List<Map<String, Object>> requestTools;
    
    /** 请求工具是否已截断 */
    private Boolean requestToolsTruncated;
    
    /** 请求工具原始数量（截断前） */
    private Integer requestToolsOriginalCount;
    
    /** 消息列表是否为 fallback 数据 */
    private Boolean messagesFallback;

    /** 响应文本预览 */
    private String responseTextPreview;

    /** 完整响应文本 */
    private String responseText;

    /** 响应文本是否已截断 */
    private Boolean responseTextTruncated;

    /** 响应文本原始长度（截断前） */
    private Integer responseTextOriginalLength;

    /** 思考过程预览 */
    private String reasoningPreview;

    /** 本次调用产生的工具调用列表 */
    private List<Map<String, Object>> responseToolCalls;

    // ===== TOOL_CALL 专用字段 =====

    /** 工具调用ID */
    private String toolCallId;

    /** 工具参数 */
    private Map<String, Object> toolArguments;

    /** 工具参数 JSON 字符串（原始） */
    private String toolArgumentsJson;

    /** 工具结果摘要 */
    private String toolResultPreview;

    /** 工具结果 */
    private Object toolResult;

    /** 工具结果是否已截断 */
    private Boolean toolResultTruncated;

    /** 工具结果原始长度（截断前） */
    private Integer toolResultOriginalLength;

    /** 工具状态 */
    private String toolStatus;

    // ===== 通用扩展 =====

    /** 扩展元数据 */
    private Map<String, Object> extraMeta;
}
