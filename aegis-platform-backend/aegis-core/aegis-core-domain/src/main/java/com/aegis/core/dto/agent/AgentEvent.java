package com.aegis.core.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 智能体执行事件。
 *
 * <p>SSE 流式协议的事件载荷，由 {@code TaskExecutionService} 产出，
 * 经 {@code AgentController} 序列化为 SSE 帧推送。
 *
 * <h3>事件类型</h3>
 * <ul>
 *   <li>{@code agent_start}：任务开始，data 含 taskId/sessionId/agentName/model</li>
 *   <li>{@code text_delta}：LLM 流式输出文本片段</li>
 *   <li>{@code reasoning}：思维链输出片段</li>
 *   <li>{@code tool_call}：工具调用发起</li>
 *   <li>{@code tool_result}：工具执行完成</li>
 *   <li>{@code kb_ref}：知识库引用</li>
 *   <li>{@code error}：执行异常</li>
 *   <li>{@code agent_end}：任务完成，data 含 token 用量与费用</li>
 *   <li>{@code done}：SSE 关闭信号</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件类型：agent_start / text_delta / reasoning / tool_call / tool_result / kb_ref / error / agent_end / done */
    private String event;

    /** 事件数据（任意类型，由事件类型决定结构） */
    private Object data;

    /** 事件时间戳（毫秒） */
    private Long timestamp;

    /**
     * 构造简单事件。
     *
     * @param event 事件类型
     * @param data  事件数据
     * @return 事件对象
     */
    public static AgentEvent of(String event, Object data) {
        return AgentEvent.builder()
                .event(event)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
