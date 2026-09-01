package com.aegis.core.enums.monitor;

import lombok.Getter;

/**
 * 审计处置结果。
 *
 * @author wang.zhen
 */
@Getter
public enum AuditResult {
    SUCCESS("成功"),
    BLOCKED("拦截"),
    ALERT("告警"),
    RECORDED("已记录");

    private final String desc;

    AuditResult(String desc) {
        this.desc = desc;
    }
}
