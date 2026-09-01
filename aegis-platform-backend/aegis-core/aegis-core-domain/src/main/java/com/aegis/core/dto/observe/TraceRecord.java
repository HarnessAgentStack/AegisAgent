package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路主记录。
 *
 * <p>表示一次完整的智能体调用链路，包含从请求入口到最终响应的全过程摘要信息，
 * 包括状态、耗时、Token 消耗、费用等关键指标。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 链路唯一ID */
    private String traceId;

    /** 会话ID */
    private String sessionId;

    /** 智能体ID */
    private Long agentId;

    /** 智能体名称 */
    private String agentName;

    /** 用户ID */
    private Long userId;

    /** 用户名 */
    private String userName;

    /** 租户ID */
    private Long tenantId;

    /** API路径 */
    private String apiPath;

    /** 链路状态：RUNNING / SUCCESS / FAILED / TIMEOUT */
    private String status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 输入 Token 数 */
    private Integer tokenInput;

    /** 输出 Token 数 */
    private Integer tokenOutput;

    /** 错误信息 */
    private String errorMsg;

    /** Span 数量 */
    private Integer spanCount;

    /** SSE 事件数量 */
    private Integer sseEventCount;
}