package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统计查询条件。
 *
 * <p>支持按用户 / 智能体 / 会话维度进行统计查询，可指定时间范围。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatsQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 统计范围：user / agent / session */
    private String scope;

    /** 范围值（如 userId / agentId / sessionId） */
    private String scopeValue;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}