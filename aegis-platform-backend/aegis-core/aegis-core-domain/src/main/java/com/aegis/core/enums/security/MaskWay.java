package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 脱敏方式。
 *
 * @author wang.zhen
 */
@Getter
public enum MaskWay {
    MIDDLE4("中间4位*"),
    KEEP_HEAD_TAIL("保留首尾"),
    KEEP_LAST4("保留后4位"),
    ALL("全部替换"),
    HASH("哈希脱敏");

    private final String desc;

    MaskWay(String desc) {
        this.desc = desc;
    }
}
