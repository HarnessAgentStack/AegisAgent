package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 限流命中动作。
 *
 * @author wang.zhen
 */
@Getter
public enum RateLimitAction {
    ALERT("告警"),
    LIMIT("限流"),
    PASS("放行");

    private final String desc;

    RateLimitAction(String desc) {
        this.desc = desc;
    }
}
