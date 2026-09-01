package com.aegis.core.enums.eval;

import lombok.Getter;

/**
 * 评测任务状态。
 *
 * @author wang.zhen
 */
@Getter
public enum EvalStatus {
    COMPLETED("已完成"),
    IN_PROGRESS("进行中"),
    QUEUED("排队中");

    private final String desc;

    EvalStatus(String desc) {
        this.desc = desc;
    }
}
