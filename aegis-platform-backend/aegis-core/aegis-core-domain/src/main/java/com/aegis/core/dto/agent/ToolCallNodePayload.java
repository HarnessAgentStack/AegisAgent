package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 工具调用节点事件载荷。
 * 用于流式事件中传递工具调用的详细信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallNodePayload implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 调用唯一ID */
    private String id;

    /** 工具名称 */
    private String name;

    /** 触发原因说明 */
    private String triggerReason;

    /** 状态：PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 调用参数 */
    private Map<String, Object> arguments;

    /** 调用结果 */
    private Object result;

    /** 耗时（毫秒） */
    private Long durationMs;

    /** 是否为并行调用 */
    private Boolean isParallel;

    /** 并行组ID */
    private String groupId;
}
