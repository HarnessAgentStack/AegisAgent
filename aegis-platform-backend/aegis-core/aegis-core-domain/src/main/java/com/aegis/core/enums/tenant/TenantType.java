package com.aegis.core.enums.tenant;

import lombok.Getter;

/**
 * 租户类型。
 *
 * @author wang.zhen
 */
@Getter
public enum TenantType {
    HQ("集团总部"),
    SUBSIDIARY("子公司"),
    DIVISION("事业部");

    private final String desc;

    TenantType(String desc) {
        this.desc = desc;
    }
}
