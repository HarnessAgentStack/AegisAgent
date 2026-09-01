package com.aegis.core.enums.agent;

import lombok.Getter;

/**
 * 记忆策略。
 *
 * @author wang.zhen
 */
@Getter
public enum MemoryStrategy {
    SESSION_LEVEL("会话级"),
    LONG_TERM("长期(用户归档)");

    private final String desc;

    MemoryStrategy(String desc) {
        this.desc = desc;
    }
}
