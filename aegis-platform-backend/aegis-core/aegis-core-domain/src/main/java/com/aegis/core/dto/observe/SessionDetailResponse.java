package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话详情响应 - 以 Session 为根节点的聚合视图。
 *
 * <p>包含会话级概览信息和所有轮次的详细步骤。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionDetailResponse implements Serializable {

    private static final long serialVersionUID = 1L;

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

    /** 总轮次数 */
    private Integer totalRounds;

    /** 总耗时(ms) */
    private Long totalDurationMs;

    /** 总输入Token */
    private Integer totalTokenInput;

    /** 总输出Token */
    private Integer totalTokenOutput;

    /** 会话状态 */
    private String status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 轮次列表 */
    private List<RoundDetail> rounds;
}
