package com.aegis.core.enums.agent;

import lombok.Getter;

/**
 * 资源绑定类型。
 *
 * @author wang.zhen
 */
@Getter
public enum BindingType {
    FIXED("固定绑定"),
    DYNAMIC("动态加载"),
    AUTO("自动绑定");

    private final String desc;

    BindingType(String desc) {
        this.desc = desc;
    }
}
