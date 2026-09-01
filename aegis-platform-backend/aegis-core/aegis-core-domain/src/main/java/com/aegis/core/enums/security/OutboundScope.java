package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 出站策略作用域。
 *
 * @author wang.zhen
 */
@Getter
public enum OutboundScope {
    ALL("全部智能体"),
    AGENT("特定智能体"),
    DEPT("特定部门");

    private final String desc;

    OutboundScope(String desc) {
        this.desc = desc;
    }
}
