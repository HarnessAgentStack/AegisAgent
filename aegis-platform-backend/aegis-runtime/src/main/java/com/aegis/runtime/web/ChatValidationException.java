package com.aegis.runtime.web;

import com.aegis.core.dto.agent.AgentEvent;

/**
 * 对话请求校验异常。
 *
 * <p>由 {@link ChatRequestValidator} 在校验失败时抛出，
 * 携带错误事件供 Controller 层直接写入 SSE 流。
 *
 * @author wang.zhen
 */
public class ChatValidationException extends RuntimeException {

    private final AgentEvent errorEvent;

    public ChatValidationException(AgentEvent errorEvent) {
        super(errorEvent != null && errorEvent.getData() instanceof java.util.Map<?, ?> m
                ? String.valueOf(m.get("message") != null ? m.get("message") : "校验失败")
                : "校验失败");
        this.errorEvent = errorEvent;
    }

    public AgentEvent getErrorEvent() {
        return errorEvent;
    }
}
