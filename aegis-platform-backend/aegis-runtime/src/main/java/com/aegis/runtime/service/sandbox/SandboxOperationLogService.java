package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.SandboxOperationLog;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.dal.mapper.sandbox.SandboxOperationLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 沙箱操作日志领域服务。
 *
 * <p>收口 {@link SandboxOperationLogMapper} 的数据访问，
 * 供 runtime / admin 的沙箱操作组件在状态变更时写入审计日志。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>写入操作日志（分配、释放、回收、销毁等）</li>
 *   <li>按实例/租户查询操作历史</li>
 *   <li>按操作类型统计数量</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxOperationLogService {

    private final SandboxOperationLogMapper operationLogMapper;

    /**
     * 记录沙箱分配操作。
     *
     * @param instanceId 实例 ID
     * @param poolId     池 ID
     * @param tenantId   租户 ID
     * @param userId     用户 ID
     * @param agentId    Agent ID
     * @param sessionId  会话 ID
     * @param slotKey    槽位键
     */
    public void logAllocate(String instanceId, Long poolId, Long tenantId,
                            Long userId, Long agentId, String sessionId, String slotKey) {
        writeLog(instanceId, poolId, tenantId, "ALLOCATE", "RUNTIME",
                null, SandboxInstanceStatus.OCCUPIED,
                userId, agentId, sessionId, slotKey,
                true, null, null, null);
    }

    /**
     * 记录沙箱释放操作。
     *
     * @param instanceId 实例 ID
     * @param poolId     池 ID
     * @param tenantId   租户 ID
     * @param slotKey    槽位键
     */
    public void logRelease(String instanceId, Long poolId, Long tenantId, String slotKey) {
        writeLog(instanceId, poolId, tenantId, "RELEASE", "RUNTIME",
                SandboxInstanceStatus.OCCUPIED, SandboxInstanceStatus.IDLE,
                null, null, null, slotKey,
                true, null, null, null);
    }

    /**
     * 记录沙箱回收操作。
     *
     * @param instanceId 实例 ID
     * @param poolId     池 ID
     * @param tenantId   租户 ID
     */
    public void logReclaim(String instanceId, Long poolId, Long tenantId) {
        writeLog(instanceId, poolId, tenantId, "RECLAIM", "ADMIN",
                SandboxInstanceStatus.IDLE, SandboxInstanceStatus.IDLE,
                null, null, null, null,
                true, null, null, null);
    }

    /**
     * 记录沙箱销毁操作。
     *
     * @param instanceId 实例 ID
     * @param poolId     池 ID
     * @param tenantId   租户 ID
     * @param fromStatus 变更前状态
     */
    public void logDestroy(String instanceId, Long poolId, Long tenantId,
                            SandboxInstanceStatus fromStatus) {
        writeLog(instanceId, poolId, tenantId, "DESTROY", "ADMIN",
                fromStatus, SandboxInstanceStatus.DESTROYED,
                null, null, null, null,
                true, null, null, null);
    }

    /**
     * 记录沙箱修复操作。
     *
     * @param instanceId 实例 ID
     * @param poolId     池 ID
     * @param tenantId   租户 ID
     */
    public void logRepair(String instanceId, Long poolId, Long tenantId) {
        writeLog(instanceId, poolId, tenantId, "REPAIR", "ADMIN",
                SandboxInstanceStatus.ABNORMAL, SandboxInstanceStatus.IDLE,
                null, null, null, null,
                true, null, null, null);
    }

    /**
     * 记录心跳操作。
     *
     * @param instanceId 实例 ID
     * @param tenantId   租户 ID
     * @param success    是否成功
     * @param errorCode  错误码（失败时填写）
     * @param errorMessage 错误消息（失败时填写）
     */
    public void logHeartbeat(String instanceId, Long tenantId, boolean success,
                              String errorCode, String errorMessage) {
        writeLog(instanceId, null, tenantId, "HEARTBEAT", "SYSTEM",
                null, null, null, null, null, null,
                success, errorCode, errorMessage, null);
    }

    /**
     * 记录操作日志。
     *
     * @param operationType 操作类型
     * @param source        操作来源
     * @param fromStatus    变更前状态
     * @param toStatus      变更后状态
     * @param success       是否成功
     * @param errorCode     错误码
     * @param errorMessage  错误消息
     */
    private void writeLog(String instanceId, Long poolId, Long tenantId,
                           String operationType, String source,
                           SandboxInstanceStatus fromStatus, SandboxInstanceStatus toStatus,
                           Long userId, Long agentId, String sessionId, String slotKey,
                           boolean success, String errorCode, String errorMessage,
                           String detailJson) {
        try {
            SandboxOperationLog logEntry = SandboxOperationLog.builder()
                    .instanceId(instanceId)
                    .poolId(poolId)
                    .tenantId(tenantId)
                    .operationType(operationType)
                    .source(source)
                    .fromStatus(fromStatus)
                    .toStatus(toStatus)
                    .userId(userId)
                    .agentId(agentId)
                    .sessionId(sessionId)
                    .slotKey(slotKey)
                    .success(success ? 1 : 0)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage)
                    .detailJson(detailJson)
                    .build();
            operationLogMapper.insert(logEntry);
        } catch (Exception e) {
            // 日志写入失败不影响主流程
            log.warn("沙箱操作日志写入失败: instanceId={}, operationType={}, error={}",
                    instanceId, operationType, e.getMessage());
        }
    }

    /**
     * 按实例 ID 查询最近操作日志。
     *
     * @param instanceId 实例 ID
     * @param limit      最大返回条数
     * @return 操作日志列表
     */
    public List<SandboxOperationLog> queryByInstanceId(String instanceId, int limit) {
        return operationLogMapper.selectByInstanceId(instanceId, limit);
    }

    /**
     * 按租户和时间范围查询操作日志。
     *
     * @param tenantId  租户 ID
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 操作日志列表
     */
    public List<SandboxOperationLog> queryByTenantAndTimeRange(
            Long tenantId, LocalDateTime startTime, LocalDateTime endTime) {
        return operationLogMapper.selectByTenantAndTimeRange(tenantId, startTime, endTime);
    }
}
