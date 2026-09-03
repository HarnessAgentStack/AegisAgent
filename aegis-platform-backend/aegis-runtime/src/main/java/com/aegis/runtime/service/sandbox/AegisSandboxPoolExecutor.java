package com.aegis.runtime.service.sandbox;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxOperationLog;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.enums.sandbox.SandboxPoolType;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.dal.mapper.sandbox.SandboxBaseImageMapper;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.dal.mapper.sandbox.SandboxOperationLogMapper;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 沙箱池执行器（Phase 2 减法后的补完路径，含 admin 后台管理联动）。
 *
 * <p>职责：查池 → 按 slotKey 隔离选取实例 → {@link ISandboxBackend#exec} 执行；
 * 池内无可用实例时按池配置 {@link ISandboxBackend#createInPool} 扩容并登记 sbx_instance。</p>
 *
 * <h3>slotKey 隔离语义（与旧 SlotKeyParser 对齐）</h3>
 * <ul>
 *   <li>UNIVERSAL → USER 槽位 {@code aegis:{tenantId}:user:{userId}}（同用户跨会话复用）</li>
 *   <li>APPLICATION / SYSTEM → AGENT 槽位 {@code aegis:{tenantId}:agent:{agentId}}（同智能体跨会话复用）</li>
 *   <li>SYSTEM 智能体另有 RESIDENT 常驻实例（{@code aegis:resident:sys:{agentId}}，
 *       由 admin Reconcile 预绑定），执行时优先使用且不改变其绑定</li>
 * </ul>
 *
 * <h3>后台管理联动契约（本类关键设计）</h3>
 * <ol>
 *   <li>占用即落痕：干净 IDLE → OCCUPIED 经 updateAllocateWithVersion 原子写入
 *       user_id/agent_id/session_id/slot_key/allocated_time（乐观锁防并发双占），
 *       后台实例列表实时可见占用方</li>
 *   <li>每次执行回写 last_heartbeat_time + 递增 reuse_count</li>
 *   <li>关键动作（ALLOCATE / REUSE / 异常标记）登记 sbx_operation_log（source=RUNTIME），
 *       后台操作审计流水可追溯</li>
 *   <li>释放归 admin：孤儿占用扫描（OCCUPIED 且无 ACTIVE 租约且心跳超 5 分钟）→
 *       forceReleaseOccupied 强制释放为脏 IDLE → 回收流程重置工作区。
 *       runtime 不主动释放（Phase 2 减法：无 IdleReleaseTracker / SandboxLeaseService）</li>
 * </ol>
 *
 * <p>多租户：sbx_pool/sbx_instance 等在租户插件忽略清单（查询显式带 tenant_id 条件）；
 * sbx_operation_log 受租户插件过滤，入口统一 {@link TenantContextHolder#bind}。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AegisSandboxPoolExecutor {

    private final SandboxPoolMapper poolMapper;
    private final SandboxInstanceMapper instanceMapper;
    private final SandboxBaseImageMapper baseImageMapper;
    private final SandboxOperationLogMapper operationLogMapper;
    private final ISandboxBackend sandboxBackend;

    /**
     * 在租户沙箱池内执行命令（完整后台管理联动）。
     *
     * @param tenantId   租户 ID
     * @param userId     用户 ID（占用绑定 + 审计）
     * @param agentId    智能体 ID（占用绑定 + 审计）
     * @param sessionId  会话 ID（占用绑定 + 审计）
     * @param agentType  智能体类型（UNIVERSAL/APPLICATION/SYSTEM，决定 slotKey 隔离粒度）
     * @param command    待执行命令（如 Python 包装脚本）
     * @param timeoutSec 超时秒数
     * @return 执行结果（stdout/stderr/exitCode）
     */
    public ISandboxBackend.ExecResult exec(Long tenantId, Long userId, Long agentId,
                                            String sessionId, String agentType,
                                            String command, long timeoutSec) {
        // sbx_operation_log 受租户插件过滤，Reactor 线程无上下文需手动绑定
        TenantContextHolder.bind(tenantId);
        try {
            String slotKey = buildSlotKey(tenantId, userId, agentId, agentType);
            SandboxInstance instance = acquireInstance(tenantId, userId, agentId,
                    sessionId, agentType, slotKey);
            String execId = instance.getNamespace() + "/" + instance.getPodName();
            try {
                ISandboxBackend.ExecResult result =
                        sandboxBackend.exec(tenantId, execId, command, timeoutSec);
                touchInstance(instance);
                log.info("沙箱池执行完成: tenantId={}, agentType={}, pod={}, slotKey={}, exitCode={}",
                        tenantId, agentType, instance.getPodName(), slotKey,
                        result != null ? result.exitCode : "null");
                return result;
            } catch (Exception e) {
                log.warn("沙箱池执行失败，标记实例异常: tenantId={}, pod={}, error={}",
                        tenantId, instance.getPodName(), e.getMessage());
                markAbnormal(instance, userId, agentId, sessionId, slotKey,
                        "EXEC_FAILED", e.getMessage());
                throw e;
            }
        } finally {
            TenantContextHolder.clear();
        }
    }

    // =========================================================================
    // 实例选取（slotKey 隔离三级退化）
    // =========================================================================

    /**
     * 获取沙箱实例：同槽位 OCCUPIED 复用 → SYSTEM 常驻 RESIDENT → 干净 IDLE 原子占用
     * → 池内扩容。
     */
    private SandboxInstance acquireInstance(Long tenantId, Long userId, Long agentId,
                                            String sessionId, String agentType, String slotKey) {
        SandboxPool pool = findPool(tenantId);
        if (pool == null) {
            throw new IllegalStateException(
                    "无可用沙箱池: tenantId=" + tenantId + ", agentType=" + agentType);
        }

        // 优先级 1：同槽位 OCCUPIED 复用（同一用户/智能体的会话连续性，跨会话共享工作上下文）
        SandboxInstance bound = instanceMapper.selectOne(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getPoolId, pool.getId())
                .eq(SandboxInstance::getTenantId, tenantId)
                .eq(SandboxInstance::getSlotKey, slotKey)
                .eq(SandboxInstance::getStatus, SandboxInstanceStatus.OCCUPIED)
                .orderByAsc(SandboxInstance::getId)
                .last("LIMIT 1"));
        if (bound != null) {
            if (probeAlive(tenantId, bound)) {
                bindSession(bound, sessionId);
                instanceMapper.incrementReuseCount(bound.getInstanceId());
                bound.setReuseCount((bound.getReuseCount() != null ? bound.getReuseCount() : 0) + 1);
                writeOpLog(bound, pool, "REUSE", SandboxInstanceStatus.OCCUPIED,
                        SandboxInstanceStatus.OCCUPIED, userId, agentId, sessionId, slotKey,
                        true, null, null);
                log.info("沙箱池复用实例(同槽位): tenantId={}, pool={}, pod={}, slotKey={}, sessionId={}",
                        tenantId, pool.getPoolCode(), bound.getPodName(), slotKey, sessionId);
                return bound;
            }
            markAbnormal(bound, userId, agentId, sessionId, slotKey,
                    "PROBE_FAILED", "同槽位占用实例探活失败");
        }

        // 优先级 2：SYSTEM 智能体的 RESIDENT 常驻实例（专用绑定，原状态原绑定保持不变）
        if ("SYSTEM".equalsIgnoreCase(agentType)) {
            String residentKey = "aegis:resident:sys:" + agentId;
            SandboxInstance resident = instanceMapper.selectOne(new LambdaQueryWrapper<SandboxInstance>()
                    .eq(SandboxInstance::getPoolId, pool.getId())
                    .eq(SandboxInstance::getTenantId, tenantId)
                    .eq(SandboxInstance::getSlotKey, residentKey)
                    .in(SandboxInstance::getStatus,
                            SandboxInstanceStatus.RESIDENT, SandboxInstanceStatus.OCCUPIED)
                    .orderByAsc(SandboxInstance::getId)
                    .last("LIMIT 1"));
            if (resident != null) {
                if (probeAlive(tenantId, resident)) {
                    instanceMapper.incrementReuseCount(resident.getInstanceId());
                    writeOpLog(resident, pool, "REUSE", resident.getStatus(),
                            resident.getStatus(), userId, agentId, sessionId, residentKey,
                            true, null, null);
                    log.info("沙箱池复用常驻实例: tenantId={}, pool={}, pod={}, residentKey={}",
                            tenantId, pool.getPoolCode(), resident.getPodName(), residentKey);
                    return resident;
                }
                markAbnormal(resident, userId, agentId, sessionId, residentKey,
                        "PROBE_FAILED", "常驻实例探活失败");
            }
        }

        // 优先级 3：干净 IDLE（initialized=1）→ 原子占用（乐观锁防并发双占）
        List<SandboxInstance> idles = instanceMapper.selectList(
                new LambdaQueryWrapper<SandboxInstance>()
                        .eq(SandboxInstance::getPoolId, pool.getId())
                        .eq(SandboxInstance::getTenantId, tenantId)
                        .eq(SandboxInstance::getStatus, SandboxInstanceStatus.IDLE)
                        .eq(SandboxInstance::getInitialized, 1)
                        .orderByAsc(SandboxInstance::getId));
        for (SandboxInstance idle : idles) {
            if (!probeAlive(tenantId, idle)) {
                markAbnormal(idle, userId, agentId, sessionId, slotKey,
                        "PROBE_FAILED", "候选 IDLE 实例探活失败");
                continue;
            }
            int updated = instanceMapper.updateAllocateWithVersion(
                    idle.getInstanceId(), userId, agentId, sessionId, slotKey,
                    idle.getVersion() != null ? idle.getVersion() : 0);
            if (updated > 0) {
                instanceMapper.incrementReuseCount(idle.getInstanceId());
                // 刷新本地对象（供调用方日志与后续引用）
                idle.setStatus(SandboxInstanceStatus.OCCUPIED);
                idle.setUserId(userId);
                idle.setAgentId(agentId);
                idle.setSessionId(sessionId);
                idle.setSlotKey(slotKey);
                writeOpLog(idle, pool, "ALLOCATE", SandboxInstanceStatus.IDLE,
                        SandboxInstanceStatus.OCCUPIED, userId, agentId, sessionId, slotKey,
                        true, null, null);
                log.info("沙箱池分配实例: tenantId={}, pool={}, pod={}, slotKey={}, userId={}, sessionId={}",
                        tenantId, pool.getPoolCode(), idle.getPodName(), slotKey, userId, sessionId);
                return idle;
            }
            // 版本冲突（被并发分配）→ 尝试下一候选
            log.info("沙箱池分配版本冲突，尝试下一候选: pod={}", idle.getPodName());
        }

        // 兜底：池内扩容（带完整占用绑定）
        return createInstanceInPool(tenantId, userId, agentId, sessionId, slotKey, pool);
    }

    /**
     * slotKey 隔离粒度：UNIVERSAL → 用户槽位；APPLICATION/SYSTEM → 智能体槽位。
     */
    private String buildSlotKey(Long tenantId, Long userId, Long agentId, String agentType) {
        if ("UNIVERSAL".equalsIgnoreCase(agentType)) {
            return "aegis:" + tenantId + ":user:" + userId;
        }
        return "aegis:" + tenantId + ":agent:" + agentId;
    }

    // =========================================================================
    // 池路由
    // =========================================================================

    private SandboxPool findPool(Long tenantId) {
        // 优先租户专属 ENABLED 标准执行池（代码执行路径的既定档位），
        // 逐级退化：任意租户专属 ENABLED 池 → 平台级 ENABLED 池（tenant_id=0）
        SandboxPool pool = poolMapper.selectOne(new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getTenantId, tenantId)
                .eq(SandboxPool::getPoolType, SandboxPoolType.STANDARD)
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                .orderByAsc(SandboxPool::getId)
                .last("LIMIT 1"));
        if (pool == null) {
            pool = poolMapper.selectOne(new LambdaQueryWrapper<SandboxPool>()
                    .eq(SandboxPool::getTenantId, tenantId)
                    .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                    .orderByAsc(SandboxPool::getId)
                    .last("LIMIT 1"));
        }
        if (pool == null) {
            pool = poolMapper.selectOne(new LambdaQueryWrapper<SandboxPool>()
                    .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                    .orderByAsc(SandboxPool::getId)
                    .last("LIMIT 1"));
        }
        return pool;
    }

    // =========================================================================
    // 池内扩容
    // =========================================================================

    /**
     * 池内扩容：按池配置创建新 Pod 并登记 sbx_instance（OCCUPIED + 完整占用绑定）。
     */
    private SandboxInstance createInstanceInPool(Long tenantId, Long userId, Long agentId,
                                                String sessionId, String slotKey, SandboxPool pool) {
        long current = instanceMapper.selectCount(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getPoolId, pool.getId())
                .ne(SandboxInstance::getStatus, SandboxInstanceStatus.DESTROYED));
        if (current >= pool.getMaxInstances()) {
            throw new IllegalStateException(
                    "沙箱池已达容量上限: pool=" + pool.getPoolCode()
                            + ", max=" + pool.getMaxInstances()
                            + "（现存实例可能全部异常，等待 admin Reconcile 回收）");
        }

        SandboxBaseImage image = baseImageMapper.selectById(pool.getBaseImageId());
        if (image == null) {
            throw new IllegalStateException("池关联镜像不存在: baseImageId=" + pool.getBaseImageId());
        }
        String imageRef = image.getRepository() + ":" + image.getTag();
        double cpu = parseCpu(pool.getCpuLimit());
        int memMb = pool.getMemLimitMb() != null ? pool.getMemLimitMb() : 256;

        log.info("沙箱池扩容: tenantId={}, pool={}, namespace={}, image={}, cpu={}, mem={}MB",
                tenantId, pool.getPoolCode(), pool.getNamespace(), imageRef, cpu, memMb);

        String execId = sandboxBackend.createInPool(tenantId, pool.getNamespace(), imageRef,
                cpu, memMb, Map.of(
                        "app", "aegis-sandbox",
                        "tenant", String.valueOf(tenantId),
                        "pool", pool.getPoolCode()));

        // createInPool 返回 namespace/podName
        String podName = execId.contains("/")
                ? execId.substring(execId.indexOf('/') + 1)
                : execId;
        String namespace = execId.contains("/")
                ? execId.substring(0, execId.indexOf('/'))
                : pool.getNamespace();

        SandboxInstance inst = new SandboxInstance();
        inst.setInstanceId(UUID.randomUUID().toString().replace("-", ""));
        inst.setPoolId(pool.getId());
        inst.setTenantId(tenantId);
        inst.setStatus(SandboxInstanceStatus.OCCUPIED);
        inst.setPodName(podName);
        inst.setNamespace(namespace);
        inst.setInitialized(1);
        inst.setUserId(userId);
        inst.setAgentId(agentId);
        inst.setSessionId(sessionId);
        inst.setSlotKey(slotKey);
        inst.setStartTime(LocalDateTime.now());
        inst.setAllocatedTime(LocalDateTime.now());
        inst.setLastHeartbeatTime(LocalDateTime.now());
        inst.setReuseCount(1);
        instanceMapper.insert(inst);

        writeOpLog(inst, pool, "ALLOCATE", null, SandboxInstanceStatus.OCCUPIED,
                userId, agentId, sessionId, slotKey, true, null, null);
        log.info("沙箱池新实例已登记: tenantId={}, pool={}, pod={}, instanceId={}, slotKey={}",
                tenantId, pool.getPoolCode(), podName, inst.getInstanceId(), slotKey);
        return inst;
    }

    // =========================================================================
    // 状态回写与审计
    // =========================================================================

    private boolean probeAlive(Long tenantId, SandboxInstance inst) {
        try {
            return sandboxBackend.probeAlive(tenantId, inst.getPodName(), inst.getNamespace());
        } catch (Exception e) {
            log.debug("沙箱探活异常: pod={}, error={}", inst.getPodName(), e.getMessage());
            return false;
        }
    }

    /**
     * 同槽位复用时切换会话绑定（user/agent/slotKey 不变，仅 session 追新）。
     */
    private void bindSession(SandboxInstance inst, String sessionId) {
        try {
            if (sessionId != null && !sessionId.equals(inst.getSessionId())) {
                SandboxInstance patch = new SandboxInstance();
                patch.setId(inst.getId());
                patch.setSessionId(sessionId);
                instanceMapper.updateById(patch);
                inst.setSessionId(sessionId);
            }
        } catch (Exception e) {
            log.debug("会话绑定刷新失败（不影响执行）: pod={}, error={}", inst.getPodName(), e.getMessage());
        }
    }

    private void touchInstance(SandboxInstance inst) {
        try {
            instanceMapper.updateHeartbeat(inst.getInstanceId(), LocalDateTime.now());
            inst.setLastHeartbeatTime(LocalDateTime.now());
        } catch (Exception e) {
            log.debug("沙箱心跳回写失败（不影响执行）: pod={}, error={}", inst.getPodName(), e.getMessage());
        }
    }

    /**
     * 标记实例异常 + 清理占用信息 + 审计登记（交由 admin Reconcile 修复流程重建）。
     */
    private void markAbnormal(SandboxInstance inst, Long userId, Long agentId,
                              String sessionId, String slotKey, String errorCode, String errorMessage) {
        SandboxInstanceStatus from = inst.getStatus();
        try {
            instanceMapper.clearOccupancy(inst.getInstanceId());
            SandboxInstance patch = new SandboxInstance();
            patch.setId(inst.getId());
            patch.setStatus(SandboxInstanceStatus.ABNORMAL);
            instanceMapper.updateById(patch);
            inst.setStatus(SandboxInstanceStatus.ABNORMAL);
            writeOpLog(inst, null, "MARK_ABNORMAL",
                    from, SandboxInstanceStatus.ABNORMAL, userId, agentId, sessionId, slotKey,
                    false, errorCode, errorMessage);
            log.warn("沙箱实例已标记异常: pod={}, instanceId={}, reason={}",
                    inst.getPodName(), inst.getInstanceId(), errorMessage);
        } catch (Exception e) {
            log.warn("沙箱异常标记失败: pod={}, error={}", inst.getPodName(), e.getMessage());
        }
    }

    /**
     * 登记 sbx_operation_log（source=RUNTIME，供后台操作审计流水展示）。
     */
    private void writeOpLog(SandboxInstance inst, SandboxPool pool, String operationType,
                             SandboxInstanceStatus from, SandboxInstanceStatus to,
                             Long userId, Long agentId, String sessionId, String slotKey,
                             boolean success, String errorCode, String errorMessage) {
        try {
            SandboxOperationLog opLog = SandboxOperationLog.builder()
                    .instanceId(inst.getInstanceId())
                    .poolId(inst.getPoolId() != null ? inst.getPoolId()
                            : (pool != null ? pool.getId() : null))
                    .tenantId(inst.getTenantId())
                    .operationType(operationType)
                    .source("RUNTIME")
                    .fromStatus(from)
                    .toStatus(to)
                    .userId(userId)
                    .agentId(agentId)
                    .sessionId(sessionId)
                    .slotKey(slotKey)
                    .success(success ? 1 : 0)
                    .errorCode(errorCode)
                    .errorMessage(errorMessage != null && errorMessage.length() > 1000
                            ? errorMessage.substring(0, 1000) : errorMessage)
                    .build();
            operationLogMapper.insert(opLog);
        } catch (Exception e) {
            log.debug("沙箱操作日志登记失败（不影响执行）: instanceId={}, op={}, error={}",
                    inst.getInstanceId(), operationType, e.getMessage());
        }
    }

    private double parseCpu(String cpuLimit) {
        try {
            return cpuLimit != null ? Double.parseDouble(cpuLimit.trim()) : 0.5;
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }
}
