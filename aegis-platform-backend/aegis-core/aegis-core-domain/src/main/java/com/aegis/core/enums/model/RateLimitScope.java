package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 限流作用域。
 *
 * @author wang.zhen
 */
@Getter
public enum RateLimitScope {
    PLATFORM("全平台"),
    DEPT("部门"),
    USER("个人");

    private final String desc;

    RateLimitScope(String desc) {
        this.desc = desc;
    }
}
