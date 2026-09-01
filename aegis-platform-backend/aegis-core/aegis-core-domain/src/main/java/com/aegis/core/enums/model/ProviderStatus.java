package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 服务提供方接入状态。
 *
 * @author wang.zhen
 */
@Getter
public enum ProviderStatus {
    ACTIVE("已接入"),
    PENDING("待接入");

    private final String desc;

    ProviderStatus(String desc) {
        this.desc = desc;
    }
}
