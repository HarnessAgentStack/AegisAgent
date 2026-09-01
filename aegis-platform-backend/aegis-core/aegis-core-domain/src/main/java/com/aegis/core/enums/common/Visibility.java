package com.aegis.core.enums.common;

import lombok.Getter;

/**
 * 资源发布可见范围枚举。
 *
 * <p>资源（智能体/技能/知识库）发布时由创建者设置可见范围，
 * 替代原有的订阅审批机制。可见范围内的用户可直接订阅，无需审批。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>前置控制：发布时确定可见范围，而非订阅时审批</li>
 *   <li>简洁高效：看见即可订阅，减少审批环节</li>
 *   <li>TENANT 为默认值，避免误公开到跨租户市场</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
public enum Visibility {

    /** 本租户可见：仅当前租户用户可订阅（默认） */
    TENANT("本租户可见"),

    /** 全平台可见：跨租户市场公开，所有租户用户可订阅 */
    PUBLIC("全平台可见");

    /** 可见范围中文描述 */
    private final String desc;

    Visibility(String desc) { this.desc = desc; }
}
