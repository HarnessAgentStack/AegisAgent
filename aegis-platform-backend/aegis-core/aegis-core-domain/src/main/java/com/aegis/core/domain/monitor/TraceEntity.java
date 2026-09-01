package com.aegis.core.domain.monitor;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import com.aegis.core.base.TenantEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("mon_trace")
public class TraceEntity extends TenantEntity {

    @TableField(value = "trace_id")
    private String traceId;

    @TableField(value = "session_id")
    private String sessionId;

    @TableField(value = "agent_id")
    private Long agentId;

    @TableField(value = "agent_name")
    private String agentName;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "user_name")
    private String userName;

    @TableField(value = "api_path")
    private String apiPath;

    @TableField(value = "status")
    private String status;

    @TableField(value = "start_time")
    private LocalDateTime startTime;

    @TableField(value = "end_time")
    private LocalDateTime endTime;

    @TableField(value = "duration_ms")
    private Integer durationMs;

    @TableField(value = "token_input")
    private Integer tokenInput;

    @TableField(value = "token_output")
    private Integer tokenOutput;

    @TableField(value = "cost_amount")
    private BigDecimal costAmount;

    @TableField(value = "error_msg")
    private String errorMsg;

    @TableField(value = "span_count")
    private Integer spanCount;

    @TableField(value = "sse_event_count")
    private Integer sseEventCount;
}