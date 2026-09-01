package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 模型降级策略。
 *
 * @author wang.zhen
 */
@Getter
public enum DegradeStrategy {
    BASIC_ONLY("仅基础问答"),
    SWITCH_MODEL("切换备用模型"),
    DISABLE_TOOLS("禁用部分工具");

    private final String desc;

    DegradeStrategy(String desc) {
        this.desc = desc;
    }
}
