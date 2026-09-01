package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 工具来源类型。
 *
 * @author wang.zhen
 */
@Getter
public enum ToolSourceType {
    BUILTIN("平台内置"),
    MCP("MCP工具");

    private final String desc;

    ToolSourceType(String desc) {
        this.desc = desc;
    }
}
