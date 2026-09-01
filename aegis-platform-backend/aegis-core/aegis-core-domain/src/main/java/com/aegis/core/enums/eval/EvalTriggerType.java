package com.aegis.core.enums.eval;

import lombok.Getter;

/**
 * 评测触发方式。
 *
 * @author wang.zhen
 */
@Getter
public enum EvalTriggerType {
    PRE_RELEASE("版本发布前"),
    MANUAL("手动"),
    SCHEDULED("定时回归");

    private final String desc;

    EvalTriggerType(String desc) {
        this.desc = desc;
    }
}
