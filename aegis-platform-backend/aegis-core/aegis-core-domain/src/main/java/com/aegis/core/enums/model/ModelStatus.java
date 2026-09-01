package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 模型启用状态。
 *
 * @author wang.zhen
 */
@Getter
public enum ModelStatus {
    ENABLED("启用"),
    DISABLED("禁用");

    private final String desc;

    ModelStatus(String desc) {
        this.desc = desc;
    }
}
