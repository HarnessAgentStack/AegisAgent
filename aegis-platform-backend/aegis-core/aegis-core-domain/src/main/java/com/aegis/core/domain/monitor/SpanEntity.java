package com.aegis.core.domain.monitor;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("mon_span")
public class SpanEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField(value = "trace_id")
    private String traceId;

    @TableField(value = "span_id")
    private String spanId;

    @TableField(value = "parent_span_id")
    private String parentSpanId;

    @TableField(value = "span_type")
    private String spanType;

    @TableField(value = "name")
    private String name;

    @TableField(value = "agent_id")
    private Long agentId;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "session_id")
    private String sessionId;

    @TableField(value = "status")
    private String status;

    @TableField(value = "start_time")
    private LocalDateTime startTime;

    @TableField(value = "end_time")
    private LocalDateTime endTime;

    @TableField(value = "duration_ms")
    private Integer durationMs;

    @TableField(value = "input_summary")
    private String inputSummary;

    @TableField(value = "output_summary")
    private String outputSummary;

    @TableField(value = "token_input")
    private Integer tokenInput;

    @TableField(value = "token_output")
    private Integer tokenOutput;

    @TableField(value = "cost_amount")
    private BigDecimal costAmount;

    @TableField(value = "error_msg")
    private String errorMsg;

    @TableField(value = "meta")
    private String meta;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}