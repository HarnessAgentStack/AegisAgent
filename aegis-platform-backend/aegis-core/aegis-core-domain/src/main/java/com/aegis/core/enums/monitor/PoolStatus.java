package com.aegis.core.enums.monitor;

import lombok.Getter;

/**
 * 沙箱池状态。
 *
 * @author wang.zhen
 */
@Getter
public enum PoolStatus {
    ENABLED("启用"),
    DISABLED("禁用"),
    MAINTAINING("维护中");

    private final String desc;

    PoolStatus(String desc) {
        this.desc = desc;
    }
}
