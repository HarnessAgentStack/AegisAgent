package com.aegis.runtime.service.sandbox;

import com.aegis.core.common.error.sandbox.SandboxException;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.domain.sandbox.SandboxStateMachine;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 沙箱实例领域服务。
 *
 * <p>收口 {@link SandboxInstanceMapper} 与 {@link SandboxPoolMapper} 的数据访问，
 * 供 {@code AegisSandboxCoordinator}、{@code SandboxHealthMonitor} 等集成层组件调用，
 * 避免 integration 层直接持有 DAL Mapper。
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li>查询沙箱实例（按 slotKey + 状态 / 按 instanceId / 按 状态 + 时间窗口）</li>
 *   <li>统计租户沙箱实例数（用于配额校验）</li>
 *   <li>写入/更新沙箱实例记录</li>
 *   <li>查询启用的沙箱池列表</li>
 *   <li>统计池内 IDLE 实例数</li>
 *   <li>更新实例状态、复用次数、快照信息</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxInstanceService {

    private final SandboxInstanceMapper sandboxInstanceMapper;
    private final SandboxPoolMapper sandboxPoolMapper;

    /**
     * 按 slotKey + OCCUPIED/RESIDENT 状态查询沙箱实例（用于槽位复用）。
     *
     * <p>向后兼容：同时查询新格式（aegis:{t}:user:{u}）和旧格式（t:{t}:u:{u}）的 slotKey。
     * A3 扩展：RESIDENT 常驻实例（系统智能体专属）也纳入复用查询范围。
     *
     * @param slotKey 槽位键
     * @return 沙箱实例，不存在时返回 null
     */
    public SandboxInstance findOccupiedBySlotKey(String slotKey) {
        if (slotKey == null) {
            return null;
        }
        SandboxInstance result = sandboxInstanceMapper.selectOne(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getSlotKey, slotKey)
                        .in(SandboxInstance::getStatus,
                                SandboxInstanceStatus.OCCUPIED, SandboxInstanceStatus.RESIDENT)
                        .last("LIMIT 1"));
        if (result != null) {
            return result;
        }
        String legacyKey = toLegacySlotKey(slotKey);
        if (legacyKey != null && !legacyKey.equals(slotKey)) {
            result = sandboxInstanceMapper.selectOne(
                    new LambdaQueryWrapper<SandboxInstance>()
                            .eq(SandboxInstance::getSlotKey, legacyKey)
                            .in(SandboxInstance::getStatus,
                                    SandboxInstanceStatus.OCCUPIED, SandboxInstanceStatus.RESIDENT)
                            .last("LIMIT 1"));
            if (result != null) {
                log.info("兼容查询命中旧格式 slotKey: new={}, legacy={}", slotKey, legacyKey);
            }
        }
        return result;
    }

    /**
     * 将新格式 slotKey 转换为旧格式，用于向后兼容查询。
     * 新格式: aegis:{tenantId}:user:{userId} / aegis:{tenantId}:agent:{agentId} / aegis:{tenantId}:global
     * 旧格式: t:{tenantId}:u:{userId} / t:{tenantId}:a:{agentId} / system:global
     */
    private String toLegacySlotKey(String slotKey) {
        if (slotKey == null || slotKey.isBlank()) {
            return slotKey;
        }
        if (slotKey.startsWith("aegis:")) {
            String[] parts = slotKey.split(":");
            if (parts.length == 4 && "user".equals(parts[2])) {
                return "t:" + parts[1] + ":u:" + parts[3];
            }
            if (parts.length == 4 && "agent".equals(parts[2])) {
                return "t:" + parts[1] + ":a:" + parts[3];
            }
            if (parts.length == 3 && "global".equals(parts[2])) {
                return "system:global";
            }
        }
        return slotKey;
    }

    /**
     * 按租户 + 隔离级别 + 干净 IDLE 状态查询第一个可用实例（用于从预热池选取）。
     *
     * <p>★ 两参数驱动模型：runtime 只选取 {@code initialized=1}（工作区已初始化）的干净 IDLE 实例。
     * 脏 IDLE（initialized=0）由 admin Reconcile 回收（重初始化）后才会转为干净 IDLE。
     *
     * <p>★ 匹配优先级：
     * <ol>
     *   <li>精确匹配指定 isolationScope 的实例</li>
     *   <li>通用实例（isolation_scope IS NULL）</li>
     *   <li>SESSION 级实例（可降级使用，隔离最严格）</li>
     * </ol>
     *
     * @param tenantId       租户 ID
     * @param isolationScope 隔离级别（USER/AGENT/GLOBAL/SESSION）
     * @return 干净 IDLE 实例，不存在时返回 null
     */
    public SandboxInstance findIdleByScope(Long tenantId, String isolationScope) {
        return findIdleByScope(tenantId, isolationScope, null);
    }

    /**
     * P0-2：按租户 + 隔离级别 + 池归属 + 干净 IDLE 状态查询第一个可用实例。
     *
     * <p>在两参数版本基础上增加 poolId 过滤，确保 IDLE 选取严格限定在
     * 池路由决策（{@code SandboxPoolRouter#resolveByAgentMeta}）命中的目标池内，
     * 杜绝跨池取用。
     *
     * @param tenantId       租户 ID
     * @param isolationScope 隔离级别（USER/AGENT/GLOBAL/SESSION）
     * @param poolId         目标池 ID（null 表示不过滤池归属）
     * @return 干净 IDLE 实例，不存在时返回 null
     */
    public SandboxInstance findIdleByScope(Long tenantId, String isolationScope, Long poolId) {
        return sandboxInstanceMapper.selectOne(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(tenantId != null, SandboxInstance::getTenantId, tenantId)
                        .eq(SandboxInstance::getStatus, SandboxInstanceStatus.IDLE)
                        .eq(SandboxInstance::getInitialized, 1)
                        // P0-2：严格限定目标池内选取
                        .eq(poolId != null, SandboxInstance::getPoolId, poolId)
                        // 匹配指定 scope、通用实例（IS NULL）或 SESSION（可降级）
                        .and(w -> {
                            if (isolationScope != null) {
                                w.eq(SandboxInstance::getIsolationScope, isolationScope)
                                 .or()
                                 .isNull(SandboxInstance::getIsolationScope)
                                 .or()
                                 .eq(SandboxInstance::getIsolationScope, "SESSION");
                            } else {
                                w.isNull(SandboxInstance::getIsolationScope)
                                 .or()
                                 .eq(SandboxInstance::getIsolationScope, "SESSION");
                            }
                        })
                        // 优先级排序：精确匹配 > 通用(NULL) > SESSION，然后按分配时间
                        .last("ORDER BY CASE " +
                                "WHEN isolation_scope = '" + (isolationScope != null ? isolationScope : "") + "' THEN 0 " +
                                "WHEN isolation_scope IS NULL THEN 1 " +
                                "WHEN isolation_scope = 'SESSION' THEN 2 " +
                                "ELSE 3 END, allocated_time ASC LIMIT 1"));
    }

    /**
     * 标记实例为 OCCUPIED 并设置占用信息（用于从 IDLE 池选取后绑定）。
     *
     * <p>P2-4: 增加 userId/agentId/sessionId 参数补全归属字段。
     * P2-5: 增加状态机校验（确保从 IDLE 才能到 OCCUPIED）。
     *
     * @param instanceId 实例 ID
     * @param slotKey    槽位键
     * @param userId     用户 ID
     * @param agentId    Agent ID
     * @param sessionId  会话 ID
     */
    public void markOccupied(String instanceId, String slotKey,
                             Long userId, Long agentId, String sessionId) {
        SandboxInstance instance = sandboxInstanceMapper.selectOne(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .last("LIMIT 1"));
        if (instance == null) {
            log.warn("markOccupied 实例不存在: instanceId={}", instanceId);
            throw new SandboxException.StateMachineViolationException(
                    com.aegis.core.common.error.sandbox.SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "Instance not found: " + instanceId);
        }
        if (!SandboxStateMachine.canTransit(instance.getStatus(), SandboxInstanceStatus.OCCUPIED)) {
            log.warn("markOccupied 状态机校验失败: instanceId={}, current={}, target=OCCUPIED",
                    instanceId, instance.getStatus());
            throw new SandboxException.StateMachineViolationException(
                    com.aegis.core.common.error.sandbox.SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "Cannot transit from " + instance.getStatus() + " to OCCUPIED",
                    instance.getStatus() != null ? instance.getStatus().name() : "null",
                    SandboxInstanceStatus.OCCUPIED.name());
        }

        sandboxInstanceMapper.update(null,
                new LambdaUpdateWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .set(SandboxInstance::getStatus, SandboxInstanceStatus.OCCUPIED)
                        .set(SandboxInstance::getSlotKey, slotKey)
                        .set(SandboxInstance::getUserId, userId)
                        .set(SandboxInstance::getAgentId, agentId)
                        .set(SandboxInstance::getSessionId, sessionId)
                        .set(SandboxInstance::getAllocatedTime, LocalDateTime.now()));
    }

    /**
     * ★ 两参数驱动模型：脏标记补充（lifecycleManager.release 后调用）。
     *
     * <p>P2-3: lifecycleManager.release() 完成 OCCUPIED→IDLE 状态迁移后，
     * 补充设置 initialized=0、recycledTime、slotKey=null 等"脏 IDLE"标记。
     *
     * @param instanceId 实例 ID
     */
    public void markDirtyAfterRelease(String instanceId) {
        LocalDateTime now = LocalDateTime.now();
        sandboxInstanceMapper.update(null,
                new LambdaUpdateWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .set(SandboxInstance::getInitialized, 0)
                        .set(SandboxInstance::getRecycledTime, now)
                        // P0-6：同步更新 lastRecycleTime，确保 selectDirtyIdleTimeout SQL
                        // primary branch（last_recycle_time IS NOT NULL）命中可靠时间戳，
                        // 以释放时刻起算空闲超时，而非沿用上次回收完成时的旧时间
                        .set(SandboxInstance::getLastRecycleTime, now)
                        .set(SandboxInstance::getSlotKey, null)
                        // P0-6：清理残留占用信息，防止脏 IDLE 实例携带旧 session/user/agent 数据
                        .set(SandboxInstance::getUserId, null)
                        .set(SandboxInstance::getAgentId, null)
                        .set(SandboxInstance::getSessionId, null)
                        // A5：释放即失效装载指纹
                        .set(SandboxInstance::getResourceFingerprint, null));
    }

    /**
     * A5：标记实例资源装载完成（initialized=2 + 装载指纹）。
     *
     * <p>由 {@code SandboxResourceLoader} 在装载清单成功物化到 Pod 工作区后调用。
     * 轻量更新（不触碰 status/version，避免与心跳/续约并发冲突）。
     *
     * @param instanceId  实例 ID
     * @param fingerprint 装载清单 SHA-256 指纹
     */
    public void markResourceLoaded(String instanceId, String fingerprint) {
        sandboxInstanceMapper.update(null,
                new LambdaUpdateWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .set(SandboxInstance::getInitialized, 2)
                        .set(SandboxInstance::getResourceFingerprint, fingerprint));
    }

    /**
     * 按 instanceId 查询沙箱实例。
     *
     * @param instanceId 实例ID
     * @return 沙箱实例，不存在时返回 null
     */
    public SandboxInstance findByInstanceId(String instanceId) {
        return sandboxInstanceMapper.selectOne(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .last("LIMIT 1"));
    }

    /**
     * 按状态查询全部实例（用于健康检查、定时回收）。
     *
     * @param status 实例状态
     * @return 匹配的实例列表
     */
    public List<SandboxInstance> listByStatus(SandboxInstanceStatus status) {
        return sandboxInstanceMapper.selectList(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getStatus, status));
    }

    /**
     * 按状态 + 分配时间早于阈值查询（用于检测 OCCUPIED 超时实例）。
     *
     * @param status        实例状态
     * @param timeThreshold 时间阈值（早于此时间的视为超时）
     * @return 匹配的实例列表
     */
    public List<SandboxInstance> listByStatusAndAllocatedBefore(SandboxInstanceStatus status,
                                                                 LocalDateTime timeThreshold) {
        return sandboxInstanceMapper.selectList(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getStatus, status)
                        .lt(SandboxInstance::getAllocatedTime, timeThreshold));
    }

    /**
     * 按状态 + 回收时间早于阈值查询（用于检测 IDLE 超时实例）。
     *
     * @param status        实例状态
     * @param timeThreshold 时间阈值
     * @return 匹配的实例列表
     */
    public List<SandboxInstance> listByStatusAndRecycledBefore(SandboxInstanceStatus status,
                                                                LocalDateTime timeThreshold) {
        return sandboxInstanceMapper.selectList(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getStatus, status)
                        .lt(SandboxInstance::getRecycledTime, timeThreshold));
    }

    /**
     * 统计租户在指定状态集合下的实例数（用于沙箱配额校验）。
     *
     * @param tenantId 租户ID
     * @param statuses 状态集合
     * @return 实例数
     */
    public long countByTenantAndStatuses(Long tenantId, SandboxInstanceStatus... statuses) {
        return sandboxInstanceMapper.selectCount(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getTenantId, tenantId)
                        .in(SandboxInstance::getStatus, (Object[]) statuses));
    }

    /**
     * P2-7: 统计租户 OCCUPIED + 干净 IDLE 实例数（用于沙箱配额校验）。
     *
     * <p>只统计 OCCUPIED 状态 + IDLE(initialized=1) 状态的实例，
     * 排除脏 IDLE（initialized=0），因为脏 IDLE 已不占用活跃资源。
     *
     * @param tenantId 租户ID
     * @return OCCUPIED + 干净 IDLE 实例数
     */
    public long countOccupiedPlusCleanIdle(Long tenantId) {
        long occupied = sandboxInstanceMapper.selectCount(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getTenantId, tenantId)
                        .eq(SandboxInstance::getStatus, SandboxInstanceStatus.OCCUPIED));
        long cleanIdle = sandboxInstanceMapper.selectCount(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getTenantId, tenantId)
                        .eq(SandboxInstance::getStatus, SandboxInstanceStatus.IDLE)
                        .eq(SandboxInstance::getInitialized, 1));
        return occupied + cleanIdle;
    }

    /**
     * 统计租户在单一状态下的实例数（用于使用情况查询）。
     *
     * @param tenantId 租户ID
     * @param status   实例状态
     * @return 实例数
     */
    public long countByTenantAndStatus(Long tenantId, SandboxInstanceStatus status) {
        return sandboxInstanceMapper.selectCount(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getTenantId, tenantId)
                        .eq(SandboxInstance::getStatus, status));
    }

    /**
     * 统计池内指定状态的实例数（用于预热判断）。
     *
     * @param poolId 池ID
     * @param status 实例状态
     * @return 实例数
     */
    public long countByPoolAndStatus(Long poolId, SandboxInstanceStatus status) {
        return sandboxInstanceMapper.selectCount(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getPoolId, poolId)
                        .eq(SandboxInstance::getStatus, status));
    }

    /**
     * 插入沙箱实例记录。
     *
     * @param instance 沙箱实例
     */
    public void insert(SandboxInstance instance) {
        sandboxInstanceMapper.insert(instance);
    }

    /**
     * 按主键更新沙箱实例。
     *
     * @param instance 沙箱实例
     */
    public void updateById(SandboxInstance instance) {
        sandboxInstanceMapper.updateById(instance);
    }

    /**
     * 更新实例状态（轻量更新，避免全字段覆盖）。
     *
     * <p>P2-5: 增加状态机校验，确保状态变更符合状态机规则。
     *
     * @param instanceId 实例ID
     * @param status     新状态
     */
    public void updateStatus(String instanceId, SandboxInstanceStatus status) {
        SandboxInstance instance = sandboxInstanceMapper.selectOne(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .last("LIMIT 1"));
        if (instance == null) {
            log.warn("updateStatus 实例不存在: instanceId={}", instanceId);
            throw new SandboxException.StateMachineViolationException(
                    com.aegis.core.common.error.sandbox.SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "Instance not found: " + instanceId);
        }
        if (!SandboxStateMachine.canTransit(instance.getStatus(), status)) {
            log.warn("updateStatus 状态机校验失败: instanceId={}, current={}, target={}",
                    instanceId, instance.getStatus(), status);
            throw new SandboxException.StateMachineViolationException(
                    com.aegis.core.common.error.sandbox.SandboxErrorCode.SBX_ILLEGAL_STATE_TRANSITION,
                    "Cannot transit from " + instance.getStatus() + " to " + status,
                    instance.getStatus() != null ? instance.getStatus().name() : "null",
                    status != null ? status.name() : "null");
        }

        sandboxInstanceMapper.update(null,
                new LambdaUpdateWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .set(SandboxInstance::getStatus, status));
    }

    /**
     * 递增实例的复用次数（slot 复用时调用）。
     *
     * @param instanceId 实例ID
     */
    public void incrementReuseCount(String instanceId) {
        sandboxInstanceMapper.update(null,
                new LambdaUpdateWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .setSql("reuse_count = reuse_count + 1"));
    }

    /**
     * 更新实例快照信息（saveSnapshot 后调用）。
     *
     * @param instanceId   实例ID
     * @param snapshotId   快照ID
     * @param snapshotTime 快照时间
     */
    public void updateSnapshotInfo(String instanceId, String snapshotId, LocalDateTime snapshotTime) {
        sandboxInstanceMapper.update(null,
                new LambdaUpdateWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getInstanceId, instanceId)
                        .set(SandboxInstance::getSnapshotId, snapshotId)
                        .set(SandboxInstance::getSnapshotTime, snapshotTime));
    }

    /**
     * 查询所有启用的沙箱池（用于预热初始化）。
     *
     * @return 启用的沙箱池列表
     */
    public List<SandboxPool> listEnabledPools() {
        return sandboxPoolMapper.selectList(
                new LambdaQueryWrapper<SandboxPool>()
                        .eq(SandboxPool::getStatus, PoolStatus.ENABLED));
    }
}