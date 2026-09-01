package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 权限来源。
 *
 * <p>开源版仅保留 DIRECT（直接授予），部门继承与资源授权为后续规划，
 * 避免保留未实现的枚举值误导社区贡献者。
 *
 * @author wang.zhen
 */
@Getter
public enum PermissionSource {
    DIRECT("直接授予");

    private final String desc;

    PermissionSource(String desc) {
        this.desc = desc;
    }
}
