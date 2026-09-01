package com.aegis.core.enums.session;

import lombok.Getter;

/**
 * 会话消息类型。
 *
 * @author wang.zhen
 */
@Getter
public enum MessageType {
    USER("用户消息"),
    ASSISTANT("助手消息"),
    TOOL_CALL("工具调用"),
    TOOL_RESULT("工具结果"),
    KB_REFERENCE("知识库引用");

    private final String desc;

    MessageType(String desc) {
        this.desc = desc;
    }
}
