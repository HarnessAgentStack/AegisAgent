package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 敏感词检测范围。
 *
 * @author wang.zhen
 */
@Getter
public enum SensitiveScope {
    INPUT("用户输入"),
    OUTPUT("模型输出"),
    TOOL_RESULT("工具返回"),
    ALL("全部");

    private final String desc;

    SensitiveScope(String desc) {
        this.desc = desc;
    }
}
