package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 组织数据同步来源。
 *
 * @author wang.zhen
 */
@Getter
public enum SyncSource {
    HR("HR同步"),
    OA("OA同步"),
    LDAP("LDAP同步"),
    MANUAL("手动创建");

    private final String desc;

    SyncSource(String desc) {
        this.desc = desc;
    }
}
