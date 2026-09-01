package com.aegis.core.dto.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档处理进度VO。
 *
 *  @author wang.zhen  
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessProgressVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long docId;

    private String step;
    private String stepDisplayName;
    private Integer stepOrder;
    private String status;
    private Integer progressPercent;
    private String message;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorDetail;
}
