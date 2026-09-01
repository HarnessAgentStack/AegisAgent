package com.aegis.core.enums.resource;

import lombok.Getter;
import com.aegis.core.domain.security.ToolPolicy;

/**
 * 工具处置策略枚举。
 *
 * <p>工具管控策略（ToolPolicy）决策矩阵的输出结果，决定工具调用请求的最终处置动作。
 * 由工具类型 x 安全级别二维查表得出，可被HITL节点二次覆盖。
 *
 * @author wang.zhen
 */
@Getter
public enum ToolPolicyAction {

    /** 允许：直接放行工具调用，记录审计日志 */
    ALLOW("允许"),

    /** 需审批：触发HITL审批节点，等待人工批准后执行 */
    APPROVE("需审批"),

    /** 拒绝：直接拒绝工具调用，返回错误并记录安全审计 */
    REJECT("拒绝");

    /** 策略中文描述，用于日志输出 */
    private final String desc;

    ToolPolicyAction(String desc) { this.desc = desc; }
}