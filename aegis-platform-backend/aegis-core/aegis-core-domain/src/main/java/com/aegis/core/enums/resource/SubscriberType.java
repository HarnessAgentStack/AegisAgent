package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 技能订阅者类型枚举。
 *
 * <p>区分订阅关系的主体来源，支持用户直接订阅与智能体绑定订阅两类场景。</p>
 *
 * @author wang.zhen
 */
@Getter
public enum SubscriberType {

    /** 用户订阅：终端用户主动订阅技能，subscriber_id 关联 user.id */
    USER("用户订阅"),

    /** 智能体订阅：智能体绑定技能形成的订阅关系，subscriber_id 关联 agent_def.id */
    AGENT("智能体订阅");

    private final String desc;

    SubscriberType(String desc) {
        this.desc = desc;
    }
}