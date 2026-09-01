package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 通用启用/禁用状态。
 *
 * @author wang.zhen
 */
@Getter
public enum CommonStatus {
    NORMAL("正常"),
    DISABLED("禁用");

    private final String desc;

    CommonStatus(String desc) {
        this.desc = desc;
    }
}
