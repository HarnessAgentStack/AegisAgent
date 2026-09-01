package com.aegis.core.enums.session;

import lombok.Getter;

/**
 * 会话状态枚举。
 *
 * <p>记录会话的实时处理阶段，用于并发控制。
 * 状态在会话生命周期内多次流转，最终收敛至ENDED。
 *
 * @author wang.zhen
 */
@Getter
public enum SessionStatus {

    /** 思考中：模型正在推理生成回复 */
    THINKING("思考中"),

    /** 工具调用中：模型正在执行工具调用，等待工具返回结果 */
    TOOL_CALLING("工具调用中"),

    /** 输出中：模型正在流式输出回复文本 */
    OUTPUTTING("输出中"),

    /** 异常：会话执行出错（模型超时/工具失败/沙箱异常），需重试或人工介入 */
    EXCEPTION("异常"),

    /** 已中断：用户主动中断运行中的任务，可通过 resume 恢复 */
    INTERRUPTED("已中断"),

    /** 已暂停：HITL 审批等待中，审批通过后恢复执行 */
    PAUSED("已暂停"),

    /** 已开始：会话已创建并初始化完成，等待首次输入 */
    STARTED("已开始"),

    /** 已结束：会话正常关闭或超时终止，历史消息只读保留 */
    ENDED("已结束"),
    /** 已过期 */
    EXPIRED("已过期");

    /** 状态中文描述，用于日志输出 */
    private final String desc;

    SessionStatus(String desc) { this.desc = desc; }
}