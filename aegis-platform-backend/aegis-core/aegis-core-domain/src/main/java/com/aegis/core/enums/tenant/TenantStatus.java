package com.aegis.core.enums.tenant;

import lombok.Getter;

/**
 * 租户状态。
 *
 * @author wang.zhen
 */
@Getter
public enum TenantStatus {
    NORMAL("正常"),
    FROZEN("冻结");

    private final String desc;

    TenantStatus(String desc) {
        this.desc = desc;
    }
}
