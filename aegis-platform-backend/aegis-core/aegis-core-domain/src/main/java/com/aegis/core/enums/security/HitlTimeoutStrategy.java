package com.aegis.core.enums.security;

import lombok.Getter;

/**
 * HITL（Human-In-The-Loop）超时处理策略枚举。
 *
 * <p>当HITL审批节点超过SLA时限未处理时，系统按预设策略自动处置，
 * 避免任务无限期阻塞。策略在审批节点配置时声明。
 *
 * @author wang.zhen
 */
@Getter
public enum HitlTimeoutStrategy {

    /** 自动拒绝：超时后自动拒绝原操作，保守策略，适用于高风险场景 */
    AUTO_REJECT("自动拒绝"),

    /** 升级处理：超时后升级至上级审批人或值班池，适用于关键业务流程 */
    ESCALATE("升级处理"),

    /** 自动通过：超时后自动批准原操作，激进策略，仅适用于低风险场景 */
    AUTO_APPROVE("自动通过");

    /** 策略中文描述，用于日志输出 */
    private final String desc;

    HitlTimeoutStrategy(String desc) { this.desc = desc; }
}