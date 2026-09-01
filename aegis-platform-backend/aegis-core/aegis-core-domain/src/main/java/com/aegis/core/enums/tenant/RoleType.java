package com.aegis.core.enums.tenant;

import lombok.Getter;

/**
 * 角色类型。
 *
 * @author wang.zhen
 */
@Getter
public enum RoleType {
    PLATFORM("平台角色"),
    RESOURCE("资源角色");

    private final String desc;

    RoleType(String desc) {
        this.desc = desc;
    }
}