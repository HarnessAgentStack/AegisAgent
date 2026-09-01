package com.aegis.core.dto.observe;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话级汇总摘要（用于会话列表展示）。
 */
@Data
@Builder
public class SessionSummary {

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

    /** 该会话下的 Trace 总数 */
    private Integer traceCount;

    /** 成功 Trace 数 */
    private Integer successCount;

    /** 失败 Trace 数 */
    private Integer failCount;

    /** 总耗时（毫秒） */
    private Long totalDurationMs;

    /** 总 Token 数 */
    private Long totalTokens;

    /** 最后活动时间 */
    private LocalDateTime lastActiveTime;
}
