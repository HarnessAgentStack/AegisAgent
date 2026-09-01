package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 授权范围类型。
 *
 * @author wang.zhen
 */
@Getter
public enum ScopeType {
    INTERNAL_IP("企业内部白名单IP"),
    DEPT("指定部门"),
    PARTNER("指定外部合作伙伴");

    private final String desc;

    ScopeType(String desc) {
        this.desc = desc;
    }
}
