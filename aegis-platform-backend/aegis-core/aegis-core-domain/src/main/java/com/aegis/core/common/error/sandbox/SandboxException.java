package com.aegis.core.common.error.sandbox;

/**
 * 沙箱领域异常基类。
 *
 * <p>参考 AgentScope {@code SandboxException} 设计，承载 {@link SandboxErrorCode} 错误码
 * 与可读消息，用于沙箱分配、释放、状态迁移等场景的可预期错误。
 *
 * <h3>异常体系</h3>
 * <ul>
 *   <li>{@link SandboxStateException} — 状态机校验异常（非法状态转换、终态不可逆）</li>
 *   <li>{@link SandboxAllocationException} — 分配异常（无可用实例、并发冲突）</li>
 *   <li>{@link SandboxQuotaException} — 配额异常（租户配额超限）</li>
 *   <li>{@link SandboxLifecycleException} — 生命周期异常（健康检查、超时回收）</li>
 *   <li>{@link SandboxGuardException} — 执行互斥异常（Guard 获取失败）</li>
 * </ul>
 *
 * <h3>与全局异常处理的集成</h3>
 * <p>由全局异常处理器捕获，根据 {@link SandboxErrorCode} 映射为 HTTP 响应码与错误消息。</p>
 *
 * @author wang.zhen
 */
public class SandboxException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 沙箱错误码 */
    private final SandboxErrorCode errorCode;

    /** 操作标识（如 allocate / release / transit） */
    private final String operation;

    /**
     * 构造沙箱异常（带错误码和消息）。
     *
     * @param errorCode 错误码
     * @param message   可读消息
     */
    public SandboxException(SandboxErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.operation = null;
    }

    /**
     * 构造沙箱异常（带错误码、消息和原因）。
     *
     * @param errorCode 错误码
     * @param message   可读消息
     * @param cause     原始异常
     */
    public SandboxException(SandboxErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.operation = null;
    }

    /**
     * 构造沙箱异常（带错误码、操作、消息和原因）。
     *
     * @param errorCode 错误码
     * @param operation 操作标识
     * @param message   可读消息
     * @param cause     原始异常
     */
    public SandboxException(SandboxErrorCode errorCode, String operation,
                            String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.operation = operation;
    }

    /**
     * 获取错误码。
     *
     * @return 沙箱错误码
     */
    public SandboxErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * 获取操作标识。
     *
     * @return 操作标识，可能为 null
     */
    public String getOperation() {
        return operation;
    }

    // ---- 专用子类 ----

    /**
     * 状态机校验异常。
     *
     * <p>在状态转换违反状态机规则时抛出，如 OCCUPIED→DESTROYED 非法转换。</p>
     */
    public static class SandboxStateException extends SandboxException {

        private final String currentState;
        private final String targetState;

        /**
         * 构造状态机异常。
         *
         * @param currentState 当前状态
         * @param targetState  目标状态
         */
        public SandboxStateException(String currentState, String targetState) {
            super(SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "非法状态转换: " + currentState + " → " + targetState);
            this.currentState = currentState;
            this.targetState = targetState;
        }

        /**
         * 构造终态异常。
         *
         * @param state 当前状态
         */
        public SandboxStateException(String state, boolean terminal) {
            super(SandboxErrorCode.SBX_TERMINAL_STATE_IMMUTABLE,
                    "实例终态不可变更: " + state);
            this.currentState = state;
            this.targetState = null;
        }

        public String getCurrentState() {
            return currentState;
        }

        public String getTargetState() {
            return targetState;
        }
    }

    /**
     * 沙箱分配异常。
     *
     * <p>在沙箱分配过程中出现的业务异常，如无可用实例、并发冲突等。</p>
     */
    public static class SandboxAllocationException extends SandboxException {

        /**
         * 构造分配异常。
         *
         * @param errorCode 错误码
         * @param message   可读消息
         */
        public SandboxAllocationException(SandboxErrorCode errorCode, String message) {
            super(errorCode, "allocate", message, null);
        }

        /**
         * 构造分配异常（带原因）。
         *
         * @param errorCode 错误码
         * @param message   可读消息
         * @param cause     原始异常
         */
        public SandboxAllocationException(SandboxErrorCode errorCode, String message,
                                          Throwable cause) {
            super(errorCode, "allocate", message, cause);
        }
    }

    /**
     * 沙箱配额异常。
     *
     * <p>在租户沙箱实例数超限时抛出。</p>
     */
    public static class SandboxQuotaException extends SandboxException {

        private final Long tenantId;
        private final long currentCount;
        private final long maxQuota;

        /**
         * 构造配额异常。
         *
         * @param tenantId     租户 ID
         * @param currentCount 当前实例数
         * @param maxQuota     最大配额
         */
        public SandboxQuotaException(Long tenantId, long currentCount, long maxQuota) {
            super(SandboxErrorCode.SBX_QUOTA_EXCEEDED,
                    "租户沙箱配额超限: tenantId=" + tenantId
                            + ", used=" + currentCount + ", max=" + maxQuota);
            this.tenantId = tenantId;
            this.currentCount = currentCount;
            this.maxQuota = maxQuota;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public long getCurrentCount() {
            return currentCount;
        }

        public long getMaxQuota() {
            return maxQuota;
        }
    }

    /**
     * 沙箱生命周期异常。
     *
     * <p>在健康检查、超时回收等生命周期操作中抛出。</p>
     */
    public static class SandboxLifecycleException extends SandboxException {

        /**
         * 构造生命周期异常。
         *
         * @param errorCode 错误码
         * @param message   可读消息
         */
        public SandboxLifecycleException(SandboxErrorCode errorCode, String message) {
            super(errorCode, "lifecycle", message, null);
        }

        /**
         * 构造生命周期异常（带原因）。
         *
         * @param errorCode 错误码
         * @param message   可读消息
         * @param cause     原始异常
         */
        public SandboxLifecycleException(SandboxErrorCode errorCode, String message,
                                         Throwable cause) {
            super(errorCode, "lifecycle", message, cause);
        }
    }

    /**
     * 沙箱执行互斥异常。
     *
     * <p>在 SandboxExecutionGuard 获取失败时抛出。</p>
     */
    public static class SandboxGuardException extends SandboxException {

        private final String guardKey;

        /**
         * 构造 Guard 异常。
         *
         * @param guardKey 互斥键
         * @param message  可读消息
         */
        public SandboxGuardException(String guardKey, String message) {
            super(SandboxErrorCode.SBX_GUARD_ACQUIRE_FAILED, "guard", message, null);
            this.guardKey = guardKey;
        }

        public String getGuardKey() {
            return guardKey;
        }
    }

    /**
     * 沙箱占用冲突异常（乐观锁冲突）。
     *
     * <p>在并发分配/释放场景下，version 不匹配时抛出，
     * 表示实例已被其他请求更新，当前操作需重试。</p>
     */
    public static class OccupancyConflictException extends SandboxException {

        private final String instanceId;

        /**
         * 构造占用冲突异常。
         *
         * @param errorCode 错误码
         * @param message   可读消息
         */
        public OccupancyConflictException(SandboxErrorCode errorCode, String message) {
            super(errorCode, "allocate", message, null);
            this.instanceId = null;
        }

        /**
         * 构造占用冲突异常（带实例 ID）。
         *
         * @param errorCode  错误码
         * @param message    可读消息
         * @param instanceId 实例 ID
         */
        public OccupancyConflictException(SandboxErrorCode errorCode, String message,
                                           String instanceId) {
            super(errorCode, "allocate", message, (Throwable) null);
            this.instanceId = instanceId;
        }

        public String getInstanceId() {
            return instanceId;
        }
    }

    /**
     * 状态机违反异常（便捷构造器）。
     *
     * <p>在状态转换违反状态机规则时抛出。</p>
     */
    public static class StateMachineViolationException extends SandboxException {

        private final String currentState;
        private final String targetState;

        /**
         * 构造状态机违反异常。
         *
         * @param errorCode 错误码
         * @param message   可读消息
         */
        public StateMachineViolationException(SandboxErrorCode errorCode, String message) {
            super(errorCode, "state", message, null);
            this.currentState = null;
            this.targetState = null;
        }

        /**
         * 构造状态机违反异常（带状态信息）。
         *
         * @param errorCode    错误码
         * @param message      可读消息
         * @param currentState 当前状态
         * @param targetState  目标状态
         */
        public StateMachineViolationException(SandboxErrorCode errorCode, String message,
                                               String currentState, String targetState) {
            super(errorCode, "state", message, (Throwable) null);
            this.currentState = currentState;
            this.targetState = targetState;
        }

        public String getCurrentState() {
            return currentState;
        }

        public String getTargetState() {
            return targetState;
        }
    }
}
