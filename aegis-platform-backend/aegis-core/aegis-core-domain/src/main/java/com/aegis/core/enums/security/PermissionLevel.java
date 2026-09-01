package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 可见性权限级别。
 *
 * @author wang.zhen
 */
@Getter
public enum PermissionLevel {
    CREATOR("仅创建者"),
    DEPT("同部门"),
    ALL("全员可查看");

    private final String desc;

    PermissionLevel(String desc) {
        this.desc = desc;
    }
}
