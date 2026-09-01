package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 评测输入类型。
 *
 * @author wang.zhen
 */
@Getter
public enum InputType {
    TEXT("文本输入"),
    FILE("文件输入");

    private final String desc;

    InputType(String desc) {
        this.desc = desc;
    }
}
