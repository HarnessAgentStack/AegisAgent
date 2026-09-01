package com.aegis.core.enums.security;

import lombok.Getter;
import com.aegis.core.domain.security.HitlHistory;

/**
 * HITL审批动作枚举。
 *
 * <p>记录审批人对HITL节点的处置动作，用于审批历史（HitlHistory）追溯。
 * TIMEOUT由系统在SLA超时后自动写入。
 *
 * @author wang.zhen
 */
@Getter
public enum HitlAction {

    /** 通过：批准原操作继续执行 */
    APPROVE("通过"),

    /** 拒绝：驳回原操作，终止当前流程 */
    REJECT("拒绝"),

    /** 修改：审批人修改请求参数后批准，保留修改前后对比记录 */
    MODIFY("修改"),

    /** 超时：超过SLA时限未处理，由系统按超时策略自动处置 */
    TIMEOUT("超时");

    /** 动作中文描述，用于日志输出 */
    private final String desc;

    HitlAction(String desc) { this.desc = desc; }
}