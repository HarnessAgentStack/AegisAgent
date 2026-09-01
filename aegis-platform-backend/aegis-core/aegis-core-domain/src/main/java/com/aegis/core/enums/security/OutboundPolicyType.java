package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * 出站策略类型。
 *
 * @author wang.zhen
 */
@Getter
public enum OutboundPolicyType {
    WHITELIST_DOMAIN("白名单域名"),
    BLACKLIST_IP("黑名单IP");

    private final String desc;

    OutboundPolicyType(String desc) {
        this.desc = desc;
    }
}
