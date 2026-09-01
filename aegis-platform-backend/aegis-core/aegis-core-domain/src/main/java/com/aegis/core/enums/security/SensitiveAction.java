package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 敏感词命中动作。
 *
 * @author wang.zhen
 */
@Getter
public enum SensitiveAction {
    BLOCK("拦截"),
    REPLACE("替换"),
    MASK("脱敏"),
    ALERT("告警"),
    MARK("标记");

    private final String desc;

    SensitiveAction(String desc) {
        this.desc = desc;
    }
}
