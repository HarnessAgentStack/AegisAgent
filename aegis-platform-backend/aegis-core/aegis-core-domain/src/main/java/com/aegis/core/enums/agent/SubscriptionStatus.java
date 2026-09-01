package com.aegis.core.enums.agent;

import lombok.Getter;
import com.aegis.core.enums.common.Visibility;

/**
 * 订阅状态枚举。
 *
 * <p>资源采用"可见即可订阅"设计：发布时通过 {@link Visibility} 控制可见范围，
 * 可见范围内的用户直接订阅，无需审批。订阅状态仅在 ACTIVE/UNSUBSCRIBED 间流转。
 *
 * @author wang.zhen
 */
@Getter
public enum SubscriptionStatus {

    /** 已订阅：用户当前可使用该智能体 */
    ACTIVE("已订阅"),

    /** 已退订：用户主动退订，不再可见 */
    UNSUBSCRIBED("已退订");

    /** 状态中文描述，用于日志输出 */
    private final String desc;

    SubscriptionStatus(String desc) { this.desc = desc; }
}
