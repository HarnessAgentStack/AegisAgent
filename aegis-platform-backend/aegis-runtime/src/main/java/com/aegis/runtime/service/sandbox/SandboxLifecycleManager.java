package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.IsolationContext;
import com.aegis.core.domain.sandbox.SandboxAllocationContext;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxStateMachine;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.common.error.sandbox.SandboxErrorCode;
import com.aegis.core.common.error.sandbox.SandboxException;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 沙箱生命周期管理器。
 *
 * <p>封装沙箱实例的核心生命周期操作，协调状态机校验、数据库持久化
 * 和操作日志记录。作为 Sandbox 领域的核心服务，向上支撑
 * 沙箱分配/释放流程。</p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>状态机校验：确保状态变更符合 {@link SandboxStateMachine} 规则</li>
 *   <li>乐观并发控制：基于 version 字段防止并发状态脏写</li>
 *   <li>操作日志记录：每次状态变更写入 {@link com.aegis.core.domain.sandbox.SandboxOperationLog}</li>
 *   <li>心跳更新：定期刷新 lastHeartbeatTime 用于超时回收判定</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本类使用数据库乐观锁保证并发安全，不依赖 JVM 内锁。
 * 多实例部署场景下，由数据库保证状态一致性。</p>
 *
 * @author wang.zhen
 * @see SandboxStateMachine
 * @see SandboxInstanceMapper
 * @see SandboxOperationLogService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxLifecycleManager {

    private final SandboxInstanceMapper instanceMapper;
    private final SandboxOperationLogService operationLogService;

    /**
     * 分配沙箱实例（IDLE → OCCUPIED）。
     *
     * <p>原子性地执行状态变更 + 占用信息写入 + 版本号递增。
     * 如果乐观锁冲突（version 不匹配），抛出 {@link SandboxException.OccupancyConflictException}。</p>
     *
     * @param instance 待分配的实例（需预查询并填充 version）
     * @param context  隔离上下文（包含占用信息）
     * @return 分配结果
     * @throws SandboxException 如果状态机校验失败或乐观锁冲突
     */
    @Transactional
    public SandboxAllocationContext allocate(SandboxInstance instance, IsolationContext context) {
        // 1. 状态机校验
        SandboxInstanceStatus currentStatus = instance.getStatus();
        SandboxInstanceStatus targetStatus = SandboxInstanceStatus.OCCUPIED;
        if (!SandboxStateMachine.canTransit(currentStatus, targetStatus)) {
            log.warn("沙箱状态机校验失败: instanceId={}, current={}, target={}",
                    instance.getInstanceId(), currentStatus, targetStatus);
            throw new SandboxException.StateMachineViolationException(
                    SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "Cannot transit from " + currentStatus + " to " + targetStatus,
                    currentStatus.name(), targetStatus.name());
        }

        // 2. 乐观锁更新分配信息
        String slotKey = context.computeSlotKey();
        int updated = instanceMapper.updateAllocateWithVersion(
                instance.getInstanceId(),
                context.getUserId(),
                context.getAgentId(),
                context.getSessionId(),
                slotKey,
                instance.getVersion() != null ? instance.getVersion() : 0
        );

        if (updated == 0) {
            log.warn("沙箱分配乐观锁冲突: instanceId={}, version={}",
                    instance.getInstanceId(), instance.getVersion());
            throw new SandboxException.OccupancyConflictException(
                    SandboxErrorCode.SBX_ALLOCATION_CONFLICT,
                    "Instance version conflict, possibly already allocated by another request",
                    instance.getInstanceId());
        }

        // 3. 记录操作日志
        operationLogService.logAllocate(
                instance.getInstanceId(),
                instance.getPoolId(),
                instance.getTenantId(),
                context.getUserId(),
                context.getAgentId(),
                context.getSessionId(),
                slotKey
        );

        log.info("沙箱分配成功: instanceId={}, slotKey={}", instance.getInstanceId(), slotKey);

        return SandboxAllocationContext.success(
                instance.getInstanceId(),
                instance.getPodName(),
                instance.getNamespace(),
                slotKey
        );
    }

    /**
     * 释放沙箱实例（OCCUPIED → IDLE）。
     *
     * <p>释放后实例标记为脏 IDLE（initialized=0），等待 Admin Reconcile 回收。</p>
     *
     * @param instance 待释放的实例
     * @return 是否成功
     */
    @Transactional
    public boolean release(SandboxInstance instance) {
        // 1. 状态机校验
        SandboxInstanceStatus currentStatus = instance.getStatus();
        if (!SandboxStateMachine.canTransit(currentStatus, SandboxInstanceStatus.IDLE)) {
            log.warn("沙箱状态机校验失败: instanceId={}, current={}, target=IDLE",
                    instance.getInstanceId(), currentStatus);
            throw new SandboxException.StateMachineViolationException(
                    SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "Cannot transit from " + currentStatus + " to IDLE",
                    currentStatus.name(), SandboxInstanceStatus.IDLE.name());
        }

        // 2. 更新状态（乐观锁）
        int updated = instanceMapper.updateStatusWithVersion(
                instance.getInstanceId(),
                SandboxInstanceStatus.IDLE.name(),
                instance.getVersion() != null ? instance.getVersion() : 0
        );

        if (updated == 0) {
            log.warn("沙箱释放乐观锁冲突: instanceId={}", instance.getInstanceId());
            throw new SandboxException.OccupancyConflictException(
                    SandboxErrorCode.SBX_ALLOCATION_CONFLICT,
                    "Instance version conflict during release",
                    instance.getInstanceId());
        }

        // 3. 记录操作日志
        operationLogService.logRelease(
                instance.getInstanceId(),
                instance.getPoolId(),
                instance.getTenantId(),
                instance.getSlotKey()
        );

        log.info("沙箱释放成功: instanceId={}", instance.getInstanceId());
        return true;
    }

    /**
     * 记录沙箱心跳。
     *
     * <p>更新 lastHeartbeatTime，用于超时回收精准判定。</p>
     *
     * @param instanceId 实例 ID
     */
    public void heartbeat(String instanceId) {
        instanceMapper.updateHeartbeat(instanceId, LocalDateTime.now());
    }
}
