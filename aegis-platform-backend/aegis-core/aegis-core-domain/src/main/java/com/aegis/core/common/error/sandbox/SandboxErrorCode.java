package com.aegis.core.common.error.sandbox;

/**
 * 沙箱领域错误码枚举。
 *
 * <p>参考 AgentScope {@code SandboxErrorCode} 设计，定义 Aegis 沙箱领域特有的错误码，
 * 覆盖分配、释放、状态迁移、配额校验、池管理等场景。
 *
 * <h3>编码分区</h3>
 * <ul>
 *   <li>{@code SBX_STATE_*}：状态机校验错误</li>
 *   <li>{@code SBX_ALLOC_*}：沙箱分配错误</li>
 *   <li>{@code SBX_RELEASE_*}：沙箱释放错误</li>
 *   <li>{@code SBX_QUOTA_*}：配额超限</li>
 *   <li>{@code SBX_POOL_*}：池管理错误</li>
 *   <li>{@code SBX_LIFECYCLE_*}：生命周期操作错误</li>
 *   <li>{@code SBX_CONFIG_*}：配置错误</li>
 * </ul>
 *
 * @author wang.zhen
 */
public enum SandboxErrorCode {

    // ---- 状态机校验 ----
    /** 非法状态转换：当前状态不允许转换到目标状态 */
    SBX_ILLEGAL_STATE_TRANSITION,

    /** 实例状态已变更，操作基于过期状态 */
    SBX_STATE_STALE,

    /** 实例终态不可变更：DESTROYED 状态不可逆 */
    SBX_TERMINAL_STATE_IMMUTABLE,

    // ---- 分配 ----
    /** 无可用沙箱实例：干净 IDLE 池为空 */
    SBX_NO_AVAILABLE_INSTANCE,

    /** 沙箱实例已被其他会话占用（跨租户复用拦截） */
    SBX_INSTANCE_ALREADY_OCCUPIED,

    /** 槽位键对应的实例存活检测失败 */
    SBX_SLOT_PROBE_FAILED,

    /** 分配时实例并发冲突（乐观锁/唯一键冲突） */
    SBX_ALLOCATION_CONFLICT,

    // ---- 释放 ----
    /** 释放时实例不在 OCCUPIED 状态 */
    SBX_RELEASE_NOT_OCCUPIED,

    /** 快照保存失败导致释放不完整 */
    SBX_RELEASE_SNAPSHOT_FAILED,

    // ---- 配额 ----
    /** 租户沙箱实例数超限 */
    SBX_QUOTA_EXCEEDED,

    /** 租户配额未配置 */
    SBX_QUOTA_NOT_CONFIGURED,

    // ---- 池管理 ----
    /** 沙箱池不可用（未启用或已禁用） */
    SBX_POOL_UNAVAILABLE,

    /** 池预热失败：Pod 创建或初始化失败 */
    SBX_POOL_WARMUP_FAILED,

    /** 池缩容失败：实例仍在使用中 */
    SBX_POOL_SCALEDOWN_FAILED,

    // ---- 生命周期 ----
    /** 实例健康检查失败 */
    SBX_HEALTH_CHECK_FAILED,

    /** OCCUPIED 超时回收 */
    SBX_OCCUPIED_TIMEOUT,

    /** IDLE 脏实例超时回收 */
    SBX_DIRTY_IDLE_TIMEOUT,

    /** 沙箱执行互斥获取失败 */
    SBX_GUARD_ACQUIRE_FAILED,

    // ---- 配置 ----
    /** 沙箱后端未配置 */
    SBX_BACKEND_NOT_CONFIGURED,

    /** 隔离作用域不支持 */
    SBX_SCOPE_NOT_SUPPORTED,

    /** 通用沙箱运行时错误 */
    SBX_RUNTIME_ERROR
}
