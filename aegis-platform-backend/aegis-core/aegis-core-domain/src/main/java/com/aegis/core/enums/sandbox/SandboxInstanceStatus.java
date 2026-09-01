package com.aegis.core.enums.sandbox;

import lombok.Getter;

/**
 * 沙箱实例状态枚举。
 *
 * <p>记录沙箱实例的实时运行状态，用于沙箱池调度与健康监控。
 * 实例从池中分配后进入占用态，会话结束后归还空闲态。
 *
 * @author wang.zhen
 */
@Getter
public enum SandboxInstanceStatus {

    /** 占用中：已分配给会话，正在执行任务，不可再分配 */
    OCCUPIED("占用中"),

    /**
     * 常驻绑定：系统智能体专属实例，与智能体一对一绑定。
     *
     * <p>语义约束：
     * <ul>
     *   <li>不参与动态分配（findIdleByScope 只查 IDLE，天然排除）</li>
     *   <li>不创建租约（无过期概念，releaseSlot 拦截不释放）</li>
     *   <li>不回收、不缩容（Reconcile 回收/缩容只扫 IDLE/OCCUPIED）</li>
     *   <li>仅探活失败时由 admin 重建（重建后恢复 RESIDENT，保留 slotKey/agentId 绑定）</li>
     *   <li>容量核算与动态实例分离（countActive 不含 RESIDENT）</li>
     * </ul>
     * slotKey 规范：{@code aegis:resident:sys:{agentId}}
     */
    RESIDENT("常驻绑定"),

    /** 空闲：任务完成已归还池，等待下次分配 */
    IDLE("空闲"),

    /** 异常：实例健康检查失败或资源超限，需人工介入或自动回收 */
    ABNORMAL("异常"),

    /** 已销毁：容器已彻底销毁，终态，不再被健康监控扫描或回收 */
    DESTROYED("已销毁");

    /** 状态中文描述，用于日志输出 */
    private final String desc;

    SandboxInstanceStatus(String desc) { this.desc = desc; }
}