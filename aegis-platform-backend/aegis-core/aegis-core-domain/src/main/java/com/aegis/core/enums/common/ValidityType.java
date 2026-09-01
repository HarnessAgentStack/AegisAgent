package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 凭证有效期类型。
 *
 * @author wang.zhen
 */
@Getter
public enum ValidityType {
    PERMANENT("永久"),
    DAYS_7("7天"),
    DAYS_30("30天"),
    CUSTOM("自定义");

    private final String desc;

    ValidityType(String desc) {
        this.desc = desc;
    }
}
