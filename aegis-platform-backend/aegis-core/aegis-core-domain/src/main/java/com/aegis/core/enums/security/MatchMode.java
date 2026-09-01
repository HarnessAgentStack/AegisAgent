package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 敏感词匹配模式。
 *
 * @author wang.zhen
 */
@Getter
public enum MatchMode {
    EXACT("精确"),
    FUZZY("模糊"),
    REGEX("正则");

    private final String desc;

    MatchMode(String desc) {
        this.desc = desc;
    }
}
