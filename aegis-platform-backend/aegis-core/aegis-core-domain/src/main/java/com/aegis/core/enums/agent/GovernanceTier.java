package com.aegis.core.enums.agent;

import lombok.Getter;

/**
 * 治理档位（Governance Tier）。
 *
 * <p>用户只需选择风险档位，平台据此自动套用一整套保护策略（隔离强度 + 工具白名单 + 内容过滤 + 人审 + 审计粒度）。
 * 原则：高风险操作天然受限，而非依赖用户自觉——safe-by-default。
 *
 * <ul>
 *   <li>STANDARD —— 标准档（默认）：标准共享池、按默认工具策略、基础内容过滤、发布走审核。</li>
 *   <li>ENHANCED —— 增强档：会话级隔离、敏感工具需审批、增强内容过滤、关键动作人审。</li>
 *   <li>STRICT   —— 严格档：强隔离 / 专属、高危工具强制人审或拒绝、严格内容过滤、强制规划 + 关键步骤人工确认、全量审计。</li>
 * </ul>
 *
 *  @author wang.zhen
 */
@Getter
public enum GovernanceTier {

    /** 标准档（默认） */
    STANDARD("标准"),

    /** 增强档 */
    ENHANCED("增强"),

    /** 严格档 */
    STRICT("严格");

    /** 档位中文描述 */
    private final String desc;

    GovernanceTier(String desc) {
        this.desc = desc;
    }

    /**
     * 档位是否要求启用规划模式（任务拆解）。
     */
    public boolean requiresPlanning() {
        return this == STRICT;
    }

    /**
     * 档位是否要求至少关键动作的人审（HITL）。
     */
    public boolean requiresHumanReview() {
        return this == ENHANCED || this == STRICT;
    }

    /**
     * 内容过滤严格度，供运行时内容安全中间件选择档位。
     */
    public String contentFilterLevel() {
        return switch (this) {
            case STRICT -> "strict";
            case ENHANCED -> "enhanced";
            default -> "standard";
        };
    }
}
