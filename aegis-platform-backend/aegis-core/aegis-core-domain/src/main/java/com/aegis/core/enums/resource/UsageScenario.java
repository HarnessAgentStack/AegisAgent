package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 智能体使用场景枚举。
 *
 * <p>区分智能体是个人使用还是共享发布，影响生命周期初始状态与发布流程。
 *
 * <h3>场景说明</h3>
 * <ul>
 *   <li>PERSONAL：个人使用，创建后直接进入 ACTIVE 状态，仅创建者可用</li>
 *   <li>SHARED：共享发布，创建后进入 DRAFT 状态，需经审核后发布到市场</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
public enum UsageScenario {

    /** 个人使用：仅创建者可使用 */
    PERSONAL("个人使用"),

    /** 共享发布：发布到智能体市场，可被订阅 */
    SHARED("共享发布");

    /** 使用场景中文描述 */
    private final String desc;

    UsageScenario(String desc) { this.desc = desc; }
}
