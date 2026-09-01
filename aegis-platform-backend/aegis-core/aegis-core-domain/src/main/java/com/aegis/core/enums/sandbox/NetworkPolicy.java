package com.aegis.core.enums.sandbox;

import lombok.Getter;

/**
 * 网络出站策略。
 *
 * @author wang.zhen
 */
@Getter
public enum NetworkPolicy {
    ISOLATED("隔离"),
    RESTRICTED("限制出站"),
    NO_EXTERNAL("禁止外网"),
    OPEN("允许联网");

    private final String desc;

    NetworkPolicy(String desc) {
        this.desc = desc;
    }
}
