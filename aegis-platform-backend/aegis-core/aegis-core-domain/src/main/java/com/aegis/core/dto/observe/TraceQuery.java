package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路查询条件。
 *
 * <p>支持按会话、用户、智能体、链路ID、状态、时间范围等维度组合查询，
 * 内置分页参数。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话ID */
    private String sessionId;

    /** 用户ID */
    private Long userId;

    /** 智能体ID */
    private Long agentId;

    /** 链路ID */
    private String traceId;

    /** 状态 */
    private String status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 页码（从1开始） */
    private Integer page;

    /** 每页条数 */
    private Integer size;
}