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
 * 沙箱分配器（周期 1 从 {@link AegisSandboxPoolExecutor} 抽取，供 {@code AegisSandboxClient} 调用）。
 *
 * <p>职责：从 admin 池中按 slotKey 隔离选取实例（四级退化），或池内扩容；
 * 释放为 IDLE（复用不杀 Pod，回收归 admin Reconcile）。
 * 与 admin 后台管理高度联动：占用即落痕、心跳回写、操作审计。</p>
 *
 * <h3>slotKey 隔离语义</h3>
 * <ul>
 *   <li>UNIVERSAL → {@code aegis:{tenantId}:user:{userId}}（同用户跨会话复用）</li>
 *   <li>APPLICATION/SYSTEM → {@code aegis:{tenantId}:agent:{agentId}}（同智能体跨会话复用）</li>
 *   <li>SYSTEM 另有 RESIDENT 常驻实例（{@code aegis:resident:sys:{agentId}}）</li>
 * </ul>
 *
 * <h3>四级退化</h3>
 * <ol>
 *   <li>同槽位 OCCUPIED 复用（探活 → bindSession → reuse_count++）</li>
 *   <li>SYSTEM 常驻 RESIDENT</li>
 *   <li>干净 IDLE 原子占用（updateAllocateWithVersion 乐观锁防并发双占）</li>
 *   <li>池内 createInPool 扩容</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AegisSandboxAllocator {

    private final SandboxPoolMapper poolMapper;
    private final SandboxInstanceMapper instanceMapper;
    private final SandboxBaseImageMapper baseImageMapper;
    private final SandboxOperationLogMapper operationLogMapper;
    private final ISandboxBackend sandboxBackend;

    /**
     * 分配沙箱实例（四级退化）。
     *
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param agentId   智能体 ID
     * @param sessionId 会话 ID
     * @param agentType 智能体类型（UNIVERSAL/APPLICATION/SYSTEM）
     * @return 已占用的实例（OCCUPIED），含 podName/namespace/slotKey
     */
    public SandboxInstance allocate(Long tenantId, Long userId, Long agentId,
                                     String sessionId, String agentType) {
        TenantContextHolder.bind(tenantId);
        try {
            String slotKey = buildSlotKey(tenantId, userId, agentId, agentType);
            return acquireInstance(tenantId, userId, agentId, sessionId, agentType, slotKey);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 释放沙箱实例（OCCUPIED → IDLE，复用不杀 Pod）。
     *
     * <p>与 admin Reconcile 协同：release 后实例 IDLE 可被同槽位后续会话复用；
     * Reconcile 仅对超时未释放的孤儿强制回收重置。</p>
     *
     * @param instance 待释放实例
     */
    public void release(SandboxInstance instance) {
        if (instance == null) return;
        TenantContextHolder.bind(instance.getTenantId());
        try {
            SandboxInstanceStatus from = instance.getStatus();
            // 清占用字段 + 状态置 IDLE（保留 reuse_count 历史 + Pod 不销毁）
            instanceMapper.clearOccupancy(instance.getInstanceId());
            SandboxInstance patch = new SandboxInstance();
            patch.setId(instance.getId());
            patch.setStatus(SandboxInstanceStatus.IDLE);
            instanceMapper.updateById(patch);
            instance.setStatus(SandboxInstanceStatus.IDLE);

            writeOpLog(instance, null, "RELEASE", from, SandboxInstanceStatus.IDLE,
                    instance.getUserId(), instance.getAgentId(), instance.getSessionId(),
                    instance.getSlotKey(), true, null, null);
            log.info("沙箱实例已释放(复用不杀Pod): pod={}, instanceId={}, slotKey={}",
                    instance.getPodName(), instance.getInstanceId(), instance.getSlotKey());
        } catch (Exception e) {
            log.warn("沙箱释放失败（不影响后续复用）: pod={}, error={}",
                    instance.getPodName(), e.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 按 instanceId 反查池内实例（供 AegisSandboxClient.resume 补全 DB 持久化字段）。
     *
     * <p>resume 从 Redis state 反序列化只含 instanceId/podName 等标识，缺 DB 主键 id/version，
     * 直接用于 {@link #rebind}（updateById 路径）或 {@link #release} 会因 id 缺失静默失效。
     * 本方法补全全量字段，并做租户一致性校验（state 可能来自历史会话，跨租户防误用）。</p>
     *
     * @param tenantId   租户 ID（与记录不匹配视为不存在）
     * @param instanceId 实例 UUID
     * @return 实例记录，查无或租户不匹配返回 null
     */
    public SandboxInstance findByInstanceId(Long tenantId, String instanceId) {
        if (instanceId == null) {
            return null;
        }
        try {
            List<SandboxInstance> list = instanceMapper.selectByInstanceIds(List.of(instanceId));
            if (list == null || list.isEmpty()) {
                return null;
            }
            SandboxInstance inst = list.get(0);
            if (tenantId != null && !tenantId.equals(inst.getTenantId())) {
                log.warn("resume 反查实例租户不匹配（拒绝复用）: instanceId={}, stateTenantId={}, dbTenantId={}",
                        instanceId, tenantId, inst.getTenantId());
                return null;
            }
            return inst;
        } catch (Exception e) {
            log.warn("resume 反查实例失败: instanceId={}, error={}", instanceId, e.getMessage());
            return null;
        }
    }

    /**
     * 探活实例（供 AegisSandboxClient/AegisSandbox 调用）。
     */
    public boolean probeAlive(Long tenantId, SandboxInstance inst) {
        try {
            return sandboxBackend.probeAlive(tenantId, inst.getPodName(), inst.getNamespace());
        } catch (Exception e) {
            log.debug("沙箱探活异常: pod={}, error={}", inst.getPodName(), e.getMessage());
            return false;
        }
    }

    /**
     * 优先级 3 resume 复用后的重绑（IDLE → OCCUPIED + 会话/心跳刷新）。
     *
     * <p>框架持久化 state resume 命中且探活通过时，实例可能已因上一轮终态释放转为 IDLE
     * （内存态 SandboxInstance.status 可能滞后为 OCCUPIED）。本方法以 DB 写回为准：
     * 重标 OCCUPIED + 绑定当前会话 + 刷新心跳，保证 admin 后台可见性准确
     * （避免"IDLE 但实际在用"期间实例被其他会话的第三级退化错误占用，造成跨会话工作区污染）。</p>
     *
     * @param inst      探活通过的实例（含 instanceId/tenantId）
     * @param userId    当前用户 ID
     * @param agentId   当前智能体 ID
     * @param sessionId 当前会话 ID
     */
    public void rebind(SandboxInstance inst, Long userId, Long agentId, String sessionId) {
        if (inst == null) {
            return;
        }
        TenantContextHolder.bind(inst.getTenantId());
        try {
            SandboxInstanceStatus from = inst.getStatus();
            SandboxInstance patch = new SandboxInstance();
            patch.setId(inst.getId());
            patch.setStatus(SandboxInstanceStatus.OCCUPIED);
            patch.setUserId(userId);
            patch.setAgentId(agentId);
            patch.setSessionId(sessionId);
            patch.setLastHeartbeatTime(LocalDateTime.now());
            instanceMapper.updateById(patch);
            inst.setStatus(SandboxInstanceStatus.OCCUPIED);
            inst.setUserId(userId);
            inst.setAgentId(agentId);
            inst.setSessionId(sessionId);
            inst.setLastHeartbeatTime(LocalDateTime.now());
            writeOpLog(inst, null, "REBIND", from, SandboxInstanceStatus.OCCUPIED,
                    userId, agentId, sessionId, inst.getSlotKey(), true, null, null);
            log.info("沙箱实例resume重绑: pod={}, instanceId={}, slotKey={}, from={}",
                    inst.getPodName(), inst.getInstanceId(), inst.getSlotKey(), from);
        } catch (Exception e) {
            log.warn("沙箱重绑失败（复用继续，记账降级）: pod={}, error={}",
                    inst.getPodName(), e.getMessage());
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 心跳回写（每次 exec 后调）。
     */
    public void touch(SandboxInstance inst) {
        try {
            instanceMapper.updateHeartbeat(inst.getInstanceId(), LocalDateTime.now());
            inst.setLastHeartbeatTime(LocalDateTime.now());
        } catch (Exception e) {
            log.debug("沙箱心跳回写失败: pod={}, error={}", inst.getPodName(), e.getMessage());
        }
    }

    /**
     * 标记异常（exec 失败/探活失败时调，交 admin Reconcile 修复）。
     */
    public void markAbnormal(SandboxInstance inst, Long userId, Long agentId,
                              String sessionId, String slotKey,
                              String errorCode, String errorMessage) {
        SandboxInstanceStatus from = inst.getStatus();
        try {
            instanceMapper.clearOccupancy(inst.getInstanceId());
            SandboxInstance patch = new SandboxInstance();
            patch.setId(inst.getId());
            patch.setStatus(SandboxInstanceStatus.ABNORMAL);
            instanceMapper.updateById(patch);
            inst.setStatus(SandboxInstanceStatus.ABNORMAL);
            writeOpLog(inst, null, "MARK_ABNORMAL", from, SandboxInstanceStatus.ABNORMAL,
                    userId, agentId, sessionId, slotKey, false, errorCode, errorMessage);
            log.warn("沙箱实例已标记异常: pod={}, reason={}", inst.getPodName(), errorMessage);
        } catch (Exception e) {
            log.warn("沙箱异常标记失败: pod={}, error={}", inst.getPodName(), e.getMessage());
        }
    }

    /**
     * 登记 SNAPSHOT/RESTORE 等扩展操作审计。
     */
    public void writeOpLog(SandboxInstance inst, SandboxPool pool, String operationType,
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
            log.debug("沙箱操作日志登记失败: instanceId={}, op={}, error={}",
                    inst.getInstanceId(), operationType, e.getMessage());
        }
    }

    public SandboxPool findPool(Long tenantId) {
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

    public String buildSlotKey(Long tenantId, Long userId, Long agentId, String agentType) {
        if ("UNIVERSAL".equalsIgnoreCase(agentType)) {
            return "aegis:" + tenantId + ":user:" + userId;
        }
        return "aegis:" + tenantId + ":agent:" + agentId;
    }

    public ISandboxBackend getBackend() {
        return sandboxBackend;
    }

    // =========================================================================
    // 内部：四级退化（从 PoolExecutor.acquireInstance 平移）
    // =========================================================================

    private SandboxInstance acquireInstance(Long tenantId, Long userId, Long agentId,
                                            String sessionId, String agentType, String slotKey) {
        SandboxPool pool = findPool(tenantId);
        if (pool == null) {
            throw new IllegalStateException(
                    "无可用沙箱池: tenantId=" + tenantId + ", agentType=" + agentType);
        }

        // 优先级 1：同槽位 OCCUPIED 复用
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
                log.info("沙箱池复用实例(同槽位): pool={}, pod={}, slotKey={}",
                        pool.getPoolCode(), bound.getPodName(), slotKey);
                return bound;
            }
            markAbnormal(bound, userId, agentId, sessionId, slotKey,
                    "PROBE_FAILED", "同槽位占用实例探活失败");
        }

        // 优先级 2：SYSTEM RESIDENT 常驻
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
                    log.info("沙箱池复用常驻实例: pool={}, pod={}, residentKey={}",
                            pool.getPoolCode(), resident.getPodName(), residentKey);
                    return resident;
                }
                markAbnormal(resident, userId, agentId, sessionId, residentKey,
                        "PROBE_FAILED", "常驻实例探活失败");
            }
        }

        // 优先级 3：干净 IDLE 原子占用
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
                idle.setStatus(SandboxInstanceStatus.OCCUPIED);
                idle.setUserId(userId);
                idle.setAgentId(agentId);
                idle.setSessionId(sessionId);
                idle.setSlotKey(slotKey);
                writeOpLog(idle, pool, "ALLOCATE", SandboxInstanceStatus.IDLE,
                        SandboxInstanceStatus.OCCUPIED, userId, agentId, sessionId, slotKey,
                        true, null, null);
                log.info("沙箱池分配实例: pool={}, pod={}, slotKey={}",
                        pool.getPoolCode(), idle.getPodName(), slotKey);
                return idle;
            }
            log.info("沙箱池分配版本冲突，尝试下一候选: pod={}", idle.getPodName());
        }

        // 兜底：池内扩容
        return createInstanceInPool(tenantId, userId, agentId, sessionId, slotKey, pool);
    }

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
            log.debug("会话绑定刷新失败: pod={}, error={}", inst.getPodName(), e.getMessage());
        }
    }

    private SandboxInstance createInstanceInPool(Long tenantId, Long userId, Long agentId,
                                                String sessionId, String slotKey, SandboxPool pool) {
        long current = instanceMapper.selectCount(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getPoolId, pool.getId())
                .ne(SandboxInstance::getStatus, SandboxInstanceStatus.DESTROYED));
        if (current >= pool.getMaxInstances()) {
            throw new IllegalStateException(
                    "沙箱池已达容量上限: pool=" + pool.getPoolCode() + ", max=" + pool.getMaxInstances());
        }

        SandboxBaseImage image = baseImageMapper.selectById(pool.getBaseImageId());
        if (image == null) {
            throw new IllegalStateException("池关联镜像不存在: baseImageId=" + pool.getBaseImageId());
        }
        String imageRef = image.getRepository() + ":" + image.getTag();
        double cpu = parseCpu(pool.getCpuLimit());
        int memMb = pool.getMemLimitMb() != null ? pool.getMemLimitMb() : 256;

        String execId = sandboxBackend.createInPool(tenantId, pool.getNamespace(), imageRef,
                cpu, memMb, Map.of(
                        "app", "aegis-sandbox",
                        "tenant", String.valueOf(tenantId),
                        "pool", pool.getPoolCode()));

        String podName = execId.contains("/")
                ? execId.substring(execId.indexOf('/') + 1) : execId;
        String namespace = execId.contains("/")
                ? execId.substring(0, execId.indexOf('/')) : pool.getNamespace();

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
        // 周期5 AC-16：跨节点 resume 持久化 SandboxManager sessionKey 占位
        // （实际值由框架 SandboxLifecycleMiddleware 在 per-call acquire 时回写）
        inst.setAgentScopeSessionKey(slotKey);
        instanceMapper.insert(inst);

        writeOpLog(inst, pool, "ALLOCATE", null, SandboxInstanceStatus.OCCUPIED,
                userId, agentId, sessionId, slotKey, true, null, null);
        log.info("沙箱池新实例已登记: pool={}, pod={}, instanceId={}, slotKey={}",
                pool.getPoolCode(), podName, inst.getInstanceId(), slotKey);
        return inst;
    }

    private double parseCpu(String cpuLimit) {
        try {
            return cpuLimit != null ? Double.parseDouble(cpuLimit.trim()) : 0.5;
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }
}
