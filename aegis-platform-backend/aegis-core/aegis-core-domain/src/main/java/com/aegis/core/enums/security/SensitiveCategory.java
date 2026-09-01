package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 敏感词分类。
 *
 * @author wang.zhen
 */
@Getter
public enum SensitiveCategory {
    GENERAL("通用"),
    INDUSTRY("行业"),
    ENTERPRISE("企业自定义"),
    PRIVACY("个人隐私");

    private final String desc;

    SensitiveCategory(String desc) {
        this.desc = desc;
    }
}
