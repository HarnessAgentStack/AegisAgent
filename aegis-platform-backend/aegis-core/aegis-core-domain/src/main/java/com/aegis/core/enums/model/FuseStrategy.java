package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 熔断降级策略。
 *
 * @author wang.zhen
 */
@Getter
public enum FuseStrategy {
    BASIC_ONLY("仅保留基础问答"),
    FULLY_DISABLED("完全禁用");

    private final String desc;

    FuseStrategy(String desc) {
        this.desc = desc;
    }
}
