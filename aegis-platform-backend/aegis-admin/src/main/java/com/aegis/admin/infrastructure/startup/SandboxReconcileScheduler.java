package com.aegis.admin.infrastructure.startup;

import com.aegis.admin.config.infra.SandboxK8sProperties;
import com.aegis.admin.service.sandbox.SandboxInstanceManageService;
import com.aegis.admin.service.sandbox.SandboxReconcileLockService;
import com.aegis.admin.infrastructure.sandbox.K8sClusterService;
import com.aegis.admin.infrastructure.sandbox.K8sClusterService.PodCreateResult;
import com.aegis.admin.infrastructure.sandbox.spi.ImageRegistryRouter;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxLease;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.spi.IImageRegistry;
import com.aegis.dal.mapper.sandbox.SandboxBaseImageMapper;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.dal.mapper.sandbox.SandboxLeaseMapper;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 沙箱 Reconcile 循环调度器（两参数驱动模型核心）。
 *
 * <p>定时遍历所有 ENABLED 状态的池，执行自动化的生命周期管理：
 *
 * <h3>Reconcile 循环（每 2 分钟执行一次）</h3>
 * <ol>
 *   <li><b>健康检查</b>：探活 IDLE + OCCUPIED 实例，失败 → 标记 ABNORMAL</li>
 *   <li><b>孤儿 Pod 对账</b>：K8s 中存在但 DB 无记录的僵尸 Pod（超保护窗）→ 删除，释放 ResourceQuota</li>
 *   <li><b>OCCUPIED 超时回收</b>：长时间无心跳的 OCCUPIED 实例 → 强制回收为脏 IDLE</li>
 *   <li><b>异常修复</b>：ABNORMAL 实例 → 清理 OCCUPIED 遗留 + 重建 Pod，失败超限 → DESTROYED</li>
 *   <li><b>回收脏 IDLE</b>：IDLE(initialized=0) 且空闲超时 → 回收（默认硬回收：Pod 重建+镜像初始化）→ IDLE(initialized=1)</li>
 *   <li><b>预热补充</b>：干净 IDLE < min_instances → 创建 Pod + 初始化工作区 → 插入 sbx_instance(IDLE, init=1)</li>
 *   <li><b>缩容销毁</b>：活跃实例 > max_instances → 销毁空闲超阈值的 IDLE 实例</li>
 * </ol>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li>每个池独立处理，互不影响（单池异常不影响其他池）</li>
 *   <li>K8s 不可用时降级为仅 DB 操作（不创建/销毁 Pod）</li>
 *   <li>预热时受 max_instances 上限约束（不会超过容量上限）</li>
 *   <li>缩容时优先销毁最旧的 IDLE 实例（按 last_recycle_time 升序）</li>
 *   <li>Pod 创建遇 ResourceQuota 满时立即中止本轮批量流程（剩余创建必然失败，等待下轮对账释放后自愈）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxReconcileScheduler {

    private final SandboxPoolMapper poolMapper;
    private final SandboxInstanceMapper instanceMapper;
    private final SandboxBaseImageMapper baseImageMapper;
    private final K8sClusterService k8sClusterService;
    private final SandboxInstanceManageService instanceManageService;
    private final ImageRegistryRouter imageRegistryRouter;
    private final SandboxK8sProperties properties;
    private final SandboxOccupiedTimeoutHandler occupiedTimeoutHandler;
    private final SandboxReconcileLockService reconcileLockService;
    private final SandboxLeaseMapper leaseMapper;
    private final com.aegis.dal.mapper.agent.AgentDefMapper agentDefMapper;

    /** 工作区初始化命令：创建标准目录 */
    private static final String CMD_INIT_WORKSPACE =
            "mkdir -p /workspace/input /workspace/output /workspace/scripts /workspace/temp && echo ok";

    /**
     * Reconcile 主循环。
     *
     * <p>调度间隔由 {@code aegis.admin.sandbox.reconcile.interval-ms} 配置（默认 2 分钟）。
     */
    @Scheduled(fixedDelayString = "${aegis.admin.sandbox.reconcile.interval-ms:120000}")
    public void reconcile() {
        long start = System.currentTimeMillis();
        log.info("[Reconcile] 开始扫描...");

        // 尝试获取全局 Leader 锁（多 Admin 实例互斥）
        if (!reconcileLockService.tryAcquireLeader()) {
            log.debug("[Reconcile] 非 Leader 节点，跳过本轮对账");
            return;
        }

        int healthChecked = 0, recycled = 0, preheated = 0, scaledDown = 0, repaired = 0, errors = 0, occupiedRecovered = 0, expiredReclaimed = 0, residentEnsured = 0, orphanedReclaimed = 0, orphanPodsReclaimed = 0;

        try {
            // 常驻保障 —— 为启用的 SYSTEM 智能体确保 RESIDENT 实例绑定
            residentEnsured = ensureResidentBindings();

            // 租约过期对账 —— 所有池中过期的 OCCUPIED 实例标记为 DIRTY
            expiredReclaimed = reconcileExpiredLeases();

            // 泄漏 OCCUPIED 安全网 —— 回收无活跃租约的 OCCUPIED 实例
            orphanedReclaimed = reclaimOrphanedOccupied();

            // 查询所有 ENABLED 状态的池
            List<SandboxPool> pools = poolMapper.selectList(new LambdaQueryWrapper<SandboxPool>()
                    .eq(SandboxPool::getStatus, PoolStatus.ENABLED));

            if (pools.isEmpty()) {
                log.info("[Reconcile] 无启用的池，跳过");
                return;
            }

            for (SandboxPool pool : pools) {
                try {
                    ReconcileResult result = reconcilePool(pool);
                    healthChecked += result.healthChecked;
                    recycled += result.recycled;
                    preheated += result.preheated;
                    scaledDown += result.scaledDown;
                    repaired += result.repaired;
                    occupiedRecovered += result.occupiedRecovered;
                    orphanPodsReclaimed += result.orphanPodsReclaimed;
                } catch (Exception e) {
                    errors++;
                    log.error("[Reconcile] 处理池 {} 异常: {}", pool.getId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("[Reconcile] 扫描异常: {}", e.getMessage(), e);
        } finally {
            // 释放 Leader 锁
            reconcileLockService.releaseLeader();
        }

        long cost = System.currentTimeMillis() - start;
        log.info("[Reconcile] 扫描完成: 常驻保障={}, 租约过期回收={}, 泄漏回收={}, 健康检查={}, OCCUPIED回收={}, 孤儿Pod清理={}, 回收={}, 预热={}, 缩容={}, 修复={}, 错误={}, 耗时={}ms",
                residentEnsured, expiredReclaimed, orphanedReclaimed, healthChecked, occupiedRecovered, orphanPodsReclaimed, recycled, preheated, scaledDown, repaired, errors, cost);
    }

    // =========================================================================
    // 系统智能体常驻保障
    // =========================================================================

    /**
     * 常驻保障子流程 —— 为每个启用的 SYSTEM 智能体确保 RESIDENT 实例绑定。
     *
     * <p>流程：
     * <ol>
     *   <li>扫描 PUBLISHED 状态的 SYSTEM 智能体</li>
     *   <li>按 slotKey={@code aegis:resident:sys:{agentId}} 查询 RESIDENT/OCCUPIED 实例</li>
     *   <li>不存在 → 在 HEAVY 池（兜底任意启用池）创建 Pod + 初始化工作区 +
     *       插入 sbx_instance（status=RESIDENT，slotKey/agentId 绑定）</li>
     * </ol>
     * 探活失败的 RESIDENT 实例由 healthCheck 标记 ABNORMAL，
     * repairAbnormal 检测 resident slotKey 后保留绑定重建并恢复 RESIDENT。
     *
     * @return 本轮新建的常驻绑定数
     */
    private int ensureResidentBindings() {
        if (!k8sClusterService.isAvailable()) {
            return 0;
        }
        try {
            // 1. 扫描启用的 SYSTEM 智能体（PUBLISHED 且未删除）
            List<com.aegis.core.domain.agent.AgentDef> systemAgents = agentDefMapper.selectList(
                    new LambdaQueryWrapper<com.aegis.core.domain.agent.AgentDef>()
                            .eq(com.aegis.core.domain.agent.AgentDef::getAgentType,
                                    com.aegis.core.enums.agent.AgentType.SYSTEM)
                            .eq(com.aegis.core.domain.agent.AgentDef::getLifeStatus,
                                    com.aegis.core.enums.agent.AgentLifeStatus.PUBLISHED));
            if (systemAgents == null || systemAgents.isEmpty()) {
                return 0;
            }

            // 2. 解析常驻目标池（HEAVY 优先，兜底任意启用池）
            SandboxPool residentPool = resolveResidentPool();
            if (residentPool == null) {
                log.warn("[Reconcile][A3] 无可用常驻池，跳过常驻保障（SYSTEM 智能体数={}）", systemAgents.size());
                return 0;
            }

            int ensured = 0;
            for (com.aegis.core.domain.agent.AgentDef agent : systemAgents) {
                try {
                    String slotKey = "aegis:resident:sys:" + agent.getId();
                    // 已有绑定（RESIDENT/OCCUPIED/ABNORMAL 均视为存在，ABNORMAL 由修复流程恢复）
                    Long bound = instanceMapper.selectCount(
                            new LambdaQueryWrapper<SandboxInstance>()
                                    .eq(SandboxInstance::getSlotKey, slotKey)
                                    .in(SandboxInstance::getStatus,
                                            SandboxInstanceStatus.RESIDENT,
                                            SandboxInstanceStatus.OCCUPIED,
                                            SandboxInstanceStatus.ABNORMAL));
                    if (bound != null && bound > 0) {
                        continue;
                    }

                    // 3. 创建常驻实例（与预热产物同构：Pod + 工作区初始化 + 落库 RESIDENT）
                    PodCreateResult created = createResidentInstance(residentPool, agent, slotKey);
                    if (created == PodCreateResult.CREATED) {
                        ensured++;
                    } else if (created == PodCreateResult.QUOTA_EXCEEDED) {
                        log.warn("[Reconcile][A3] 常驻保障中止（ResourceQuota 已满）: agentId={}, 剩余待下轮对账释放后处理",
                                agent.getId());
                        break;
                    }
                } catch (Exception e) {
                    log.error("[Reconcile][A3] 常驻保障异常: agentId={}, error={}",
                            agent.getId(), e.getMessage());
                }
            }
            return ensured;
        } catch (Exception e) {
            log.error("[Reconcile][A3] 常驻保障扫描异常: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 解析常驻目标池（HEAVY 类型优先，兜底任意启用池）。
     */
    private SandboxPool resolveResidentPool() {
        // 首选：HEAVY 类型启用池
        SandboxPool pool = poolMapper.selectOne(new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                .eq(SandboxPool::getPoolType, com.aegis.core.enums.sandbox.SandboxPoolType.HEAVY)
                .last("LIMIT 1"));
        if (pool != null) {
            return pool;
        }
        // 兜底：任意启用池（当前单池部署场景）
        return poolMapper.selectOne(new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                .last("LIMIT 1"));
    }

    /**
     * 创建 RESIDENT 常驻实例（Pod + 工作区初始化 + 落库绑定）。
     *
     * @return 创建结果（见 {@link PodCreateResult}）
     */
    private PodCreateResult createResidentInstance(SandboxPool pool,
                                                   com.aegis.core.domain.agent.AgentDef agent,
                                                   String slotKey) {
        ensureNamespace(pool.getNamespace());
        String imageRef = resolveImageRef(pool);

        String podName = generatePodName(pool.getPoolCode());
        Map<String, String> labels = new HashMap<>();
        labels.put("tenant", String.valueOf(pool.getTenantId()));
        labels.put("pool", pool.getPoolCode());
        labels.put("resident", String.valueOf(agent.getId()));

        PodCreateResult created = k8sClusterService.createSandboxPod(
                pool.getNamespace(), podName, imageRef,
                pool.getCpuLimit(), pool.getMemLimitMb(), labels);
        if (created != PodCreateResult.CREATED) {
            log.warn("[Reconcile][A3] 常驻实例创建失败（Pod 创建失败: {}）: agentId={}, pool={}",
                    created, agent.getId(), pool.getPoolCode());
            return created;
        }

        boolean running = k8sClusterService.waitForPodRunning(
                pool.getNamespace(), podName,
                properties.getReconcile().getPodWaitTimeoutMs(),
                properties.getReconcile().getPodWaitIntervalMs());
        if (!running) {
            log.warn("[Reconcile][A3] 常驻实例创建失败（Pod 等待超时）: agentId={}, podName={}",
                    agent.getId(), podName);
            k8sClusterService.deletePod(pool.getNamespace(), podName);
            return PodCreateResult.FAILED;
        }

        // 工作区初始化（与预热产物同构）
        k8sClusterService.execInPod(pool.getNamespace(), podName, CMD_INIT_WORKSPACE);

        // 落库：RESIDENT 状态 + slotKey/agentId 绑定（常驻不建租约）
        SandboxInstance instance = SandboxInstance.builder()
                .instanceId(generateInstanceId())
                .poolId(pool.getId())
                .tenantId(agent.getTenantId() != null ? agent.getTenantId() : pool.getTenantId())
                .status(SandboxInstanceStatus.RESIDENT)
                .agentId(agent.getId())
                .slotKey(slotKey)
                .isolationScope("GLOBAL")
                .podName(podName)
                .namespace(pool.getNamespace())
                .initialized(1)
                .baseImageId(pool.getBaseImageId())
                .startTime(LocalDateTime.now())
                .allocatedTime(LocalDateTime.now())
                .lastRecycleTime(LocalDateTime.now())
                .reuseCount(0)
                .version(0)
                .build();
        instanceMapper.insert(instance);

        log.info("[Reconcile][A3] 常驻绑定创建成功: agentId={}, agentCode={}, slotKey={}, instanceId={}, podName={}, pool={}",
                agent.getId(), agent.getAgentCode(), slotKey, instance.getInstanceId(), podName, pool.getPoolCode());
        return PodCreateResult.CREATED;
    }

    /**
     * 租约过期对账 —— 扫描所有过期租约，将对应 OCCUPIED 实例置为脏 IDLE。
     *
     * <p>扫描 {@code sbx_lease} 表中 {@code expire_at < NOW() AND status='ACTIVE'} 的记录，
     * 标记为 EXPIRED 后将对应实例置为 DIRTY（initialized=0），供后续回收/预热处理。</p>
     *
     * @return 回收的实例数
     */
    private int reconcileExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        try {
            List<SandboxLease> expiredLeases = leaseMapper.selectExpiredLeases(now);
            if (expiredLeases == null || expiredLeases.isEmpty()) {
                return 0;
            }

            // 批量标记所有过期租约为 EXPIRED
            for (SandboxLease lease : expiredLeases) {
                leaseMapper.markExpired(lease.getLeaseId(), now);
            }

            int reclaimed = 0;
            for (SandboxLease lease : expiredLeases) {
                try {
                    String instanceId = lease.getInstanceId();
                    if (instanceId != null && !instanceId.isEmpty()) {
                        instanceMapper.clearOccupancy(instanceId);
                        instanceMapper.markIdleDirtyByInstanceId(instanceId);
                        log.info("[Reconcile] 租约过期回收: leaseId={}, instanceId={}",
                                lease.getLeaseId(), instanceId);
                        reclaimed++;
                    }
                } catch (Exception e) {
                    log.error("[Reconcile] 租约过期回收异常: leaseId={}, error={}",
                            lease.getLeaseId(), e.getMessage());
                }
            }
            return reclaimed;
        } catch (Exception e) {
            log.error("[Reconcile] 租约过期对账异常: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 泄漏 OCCUPIED 安全网 —— 回收无活跃租约的 OCCUPIED 实例。
     *
     * <p>分配过程中若在 runtime 的 {@code SandboxLeaseService.createLease()} 前异常退出，
     * 实例会永久停在 OCCUPIED 且无可过期租约，租约过期对账（{@link #reconcileExpiredLeases}）
     * 无法覆盖此类实例。本方法作为安全网，检出后强制回收为脏 IDLE，
     * 确保资源在一个 Reconcile 周期内被回收，不会永久泄漏。
     *
     * @return 回收的泄漏实例数
     */
    private int reclaimOrphanedOccupied() {
        try {
            List<SandboxInstance> orphans = instanceMapper.selectOrphanedOccupied();
            if (orphans == null || orphans.isEmpty()) {
                return 0;
            }

            int reclaimed = 0;
            for (SandboxInstance orphan : orphans) {
                try {
                    int updated = instanceMapper.forceReleaseOccupied(
                            orphan.getInstanceId(),
                            orphan.getVersion() != null ? orphan.getVersion() : 0);
                    if (updated > 0) {
                        reclaimed++;
                        log.warn("[Reconcile][P0-6] 泄漏 OCCUPIED 回收: instanceId={}, podName={}, agentId={}, allocatedTime={}",
                                orphan.getInstanceId(), orphan.getPodName(),
                                orphan.getAgentId(), orphan.getAllocatedTime());
                    } else {
                        log.debug("[Reconcile][P0-6] 泄漏回收乐观锁冲突: instanceId={}",
                                orphan.getInstanceId());
                    }
                } catch (Exception e) {
                    log.error("[Reconcile][P0-6] 泄漏回收异常: instanceId={}, error={}",
                            orphan.getInstanceId(), e.getMessage());
                }
            }
            return reclaimed;
        } catch (Exception e) {
            log.error("[Reconcile][P0-6] 泄漏 OCCUPIED 扫描异常: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * 处理单个池的 Reconcile 循环。
     *
     * @return 处理结果统计
     */
    private ReconcileResult reconcilePool(SandboxPool pool) {
        ReconcileResult result = new ReconcileResult();
        Long poolId = pool.getId();

        log.debug("[Reconcile] 处理池: id={}, code={}, min={}, max={}, idleTimeout={}min",
                poolId, pool.getPoolCode(), pool.getMinInstances(), pool.getMaxInstances(),
                pool.getIdleTimeoutMin());

        // 1. 健康检查
        result.healthChecked = healthCheck(pool);

        // 2. 孤儿 Pod 对账（K8s 存在但 DB 无记录 → 删除释放 quota）
        result.orphanPodsReclaimed = reconcileOrphanPods(pool);

        // 3. OCCUPIED 超时回收（长时间无心跳的 OCCUPIED 实例强制回收为 IDLE）
        result.occupiedRecovered = occupiedTimeoutHandler.handleTimeout(
                properties.getReconcile().getOccupiedTimeoutMin());

        // 4. 修复 ABNORMAL 实例
        result.repaired = repairAbnormal(pool);

        // 5. 回收脏 IDLE 实例（工作区重初始化）
        result.recycled = recycleDirtyIdle(pool);

        // 6. 预热补充（确保 min_instances 个干净 IDLE）
        result.preheated = preheat(pool);

        // 7. 缩容销毁（确保不超过 max_instances）
        result.scaledDown = scaleDown(pool);

        if (result.recycled > 0 || result.preheated > 0 || result.scaledDown > 0 || result.repaired > 0
                || result.occupiedRecovered > 0 || result.orphanPodsReclaimed > 0) {
            log.info("[Reconcile] 池 {} 处理完成: OCCUPIED回收={}, 孤儿Pod清理={}, 回收={}, 预热={}, 缩容={}, 修复={}",
                    pool.getPoolCode(), result.occupiedRecovered, result.orphanPodsReclaimed,
                    result.recycled, result.preheated, result.scaledDown, result.repaired);
        }
        return result;
    }

    // =========================================================================
    // 1. 健康检查
    // =========================================================================

    /**
     * 健康检查：探活 IDLE + OCCUPIED + RESIDENT 实例，失败 → 标记 ABNORMAL。
     *
     * <p>RESIDENT 常驻实例纳入探活范围（探活失败 → ABNORMAL →
     * repairAbnormal 保留绑定重建后恢复 RESIDENT）。
     *
     * @return 检查的实例数
     */
    private int healthCheck(SandboxPool pool) {
        if (!k8sClusterService.isAvailable()) {
            return 0;
        }
        List<SandboxInstance> instances = instanceMapper.selectByPoolAndStatuses(
                pool.getId(), List.of("IDLE", "OCCUPIED", "RESIDENT"));
        int checked = 0;
        int abnormal = 0;

        for (SandboxInstance inst : instances) {
            checked++;
            try {
                String phase = k8sClusterService.getPodPhase(pool.getNamespace(), inst.getPodName());
                if ("Running".equals(phase)) {
                    continue;
                }
                if ("Failed".equals(phase) || "Unknown".equals(phase) || "NOT_FOUND".equals(phase)) {
                    instanceMapper.updateStatus(inst.getInstanceId(), SandboxInstanceStatus.ABNORMAL.name());
                    abnormal++;
                    log.warn("[Reconcile] 实例标记异常: instanceId={}, phase={}, pool={}",
                            inst.getInstanceId(), phase, pool.getPoolCode());
                }
            } catch (Exception e) {
                log.warn("[Reconcile] 健康检查异常: instanceId={}, error={}",
                        inst.getInstanceId(), e.getMessage());
            }
        }
        if (abnormal > 0) {
            log.info("[Reconcile] 池 {} 健康检查: 检查 {} 个, 标记异常 {} 个",
                    pool.getPoolCode(), checked, abnormal);
        }
        return checked;
    }

    // =========================================================================
    // 2. 孤儿 Pod 对账
    // =========================================================================

    /** 孤儿 Pod 保护窗（ms）：跳过创建时间 5 分钟内的 Pod，避免误删"已创建但尚未落库"的预热产物 */
    private static final long ORPHAN_POD_GRACE_MS = 5 * 60 * 1000L;

    /**
     * K8s-DB Pod 对账：清理 K8s 中存在但 DB 无对应记录的僵尸沙箱 Pod。
     *
     * <p>孤儿来源：Pod 创建成功后落库前进程崩溃/DB 写入失败、DB 记录被人工清理、
     * 修复流程中旧 Pod 删除失败遗留新 Pod 等。僵尸 Pod 持续占用 ResourceQuota 而
     * DB 视角不可见，最终表现为"quota 满 + 预热全部失败 + 池内无 IDLE 可分配"。
     *
     * <p>按 namespace（而非 poolId）对账，覆盖同 namespace 多池场景；
     * 仅删除超过保护窗的孤儿 Pod，正在创建中的正常产物不受影响。
     *
     * @return 清理的孤儿 Pod 数
     */
    private int reconcileOrphanPods(SandboxPool pool) {
        if (!k8sClusterService.isAvailable() || !StringUtils.hasText(pool.getNamespace())) {
            return 0;
        }
        try {
            List<Pod> pods = k8sClusterService.listSandboxPods(pool.getNamespace());
            if (pods.isEmpty()) {
                return 0;
            }
            // DB 视角：该 namespace 下所有未销毁实例的 podName
            List<SandboxInstance> liveInstances = instanceMapper.selectList(
                    new LambdaQueryWrapper<SandboxInstance>()
                            .eq(SandboxInstance::getNamespace, pool.getNamespace())
                            .ne(SandboxInstance::getStatus, SandboxInstanceStatus.DESTROYED));
            Set<String> knownPodNames = new HashSet<>();
            for (SandboxInstance inst : liveInstances) {
                if (StringUtils.hasText(inst.getPodName())) {
                    knownPodNames.add(inst.getPodName());
                }
            }

            Instant now = Instant.now();
            int reclaimed = 0;
            for (Pod pod : pods) {
                String podName = pod.getMetadata() != null ? pod.getMetadata().getName() : null;
                if (podName == null || knownPodNames.contains(podName)) {
                    continue;
                }
                String creationTs = pod.getMetadata().getCreationTimestamp();
                if (creationTs == null) {
                    continue;
                }
                Instant createdAt;
                try {
                    createdAt = Instant.parse(creationTs);
                } catch (Exception e) {
                    continue;
                }
                long ageMs = Duration.between(createdAt, now).toMillis();
                if (ageMs < ORPHAN_POD_GRACE_MS) {
                    continue;
                }
                if (k8sClusterService.deletePod(pool.getNamespace(), podName)) {
                    reclaimed++;
                    log.warn("[Reconcile] 清理孤儿 Pod（K8s 存在但 DB 无记录，释放 quota）: namespace={}, podName={}, age={}s",
                            pool.getNamespace(), podName, ageMs / 1000);
                }
            }
            return reclaimed;
        } catch (Exception e) {
            log.error("[Reconcile] 孤儿 Pod 对账异常: pool={}, error={}", pool.getPoolCode(), e.getMessage());
            return 0;
        }
    }

    // =========================================================================
    // 3. 修复 ABNORMAL 实例
    // =========================================================================

    /**
     * 修复 ABNORMAL 实例：尝试销毁旧 Pod + 重建新 Pod。
     * 修复失败（重试超限）→ DESTROYED，由预热逻辑补充新实例。
     *
     * @return 修复的实例数
     */
    private int repairAbnormal(SandboxPool pool) {
        if (!k8sClusterService.isAvailable()) {
            return 0;
        }
        List<SandboxInstance> abnormalInstances = instanceMapper.selectByPoolAndStatuses(
                pool.getId(), List.of("ABNORMAL"));
        if (abnormalInstances.isEmpty()) {
            return 0;
        }

        // 确保 Namespace 存在
        ensureNamespace(pool.getNamespace());

        int repaired = 0;
        String imageRef = resolveImageRef(pool);

        for (SandboxInstance inst : abnormalInstances) {
            try {
                // RESIDENT 来源实例 —— 保留 slotKey/agentId 绑定重建，恢复 RESIDENT 状态
                boolean residentOrigin = inst.getSlotKey() != null
                        && inst.getSlotKey().startsWith("aegis:resident:sys:");

                // 如果 ABNORMAL 实例原先是 OCCUPIED，清理占用信息后再重建
                if (!residentOrigin && (StringUtils.hasText(inst.getSlotKey()) || inst.getUserId() != null
                        || inst.getAgentId() != null || StringUtils.hasText(inst.getSessionId()))) {
                    instanceMapper.clearOccupancy(inst.getInstanceId());
                    log.info("[Reconcile] 清理 OCCUPIED 遗留后重建: instanceId={}, slotKey={}",
                            inst.getInstanceId(), inst.getSlotKey());
                }

                // 销毁旧 Pod
                if (StringUtils.hasText(inst.getPodName())) {
                    k8sClusterService.deletePod(pool.getNamespace(), inst.getPodName());
                }

                // 创建新 Pod
                String newPodName = generatePodName(pool.getPoolCode());
                Map<String, String> labels = new HashMap<>();
                labels.put("tenant", String.valueOf(pool.getTenantId()));
                labels.put("pool", pool.getPoolCode());

                PodCreateResult created = k8sClusterService.createSandboxPod(
                        pool.getNamespace(), newPodName, imageRef,
                        pool.getCpuLimit(), pool.getMemLimitMb(), labels);

                if (created == PodCreateResult.QUOTA_EXCEEDED) {
                    log.warn("[Reconcile] 修复中止（ResourceQuota 已满，剩余 {} 个 ABNORMAL 待下轮对账释放后处理）: pool={}",
                            abnormalInstances.size() - repaired, pool.getPoolCode());
                    break;
                }
                if (created != PodCreateResult.CREATED) {
                    log.warn("[Reconcile] 修复失败（Pod 创建失败）: instanceId={}, pool={}",
                            inst.getInstanceId(), pool.getPoolCode());
                    continue;
                }

                // 等待 Pod Running
                boolean running = k8sClusterService.waitForPodRunning(
                        pool.getNamespace(), newPodName,
                        properties.getReconcile().getPodWaitTimeoutMs(),
                        properties.getReconcile().getPodWaitIntervalMs());

                if (!running) {
                    log.warn("[Reconcile] 修复失败（Pod 等待超时）: instanceId={}, podName={}",
                            inst.getInstanceId(), newPodName);
                    k8sClusterService.deletePod(pool.getNamespace(), newPodName);
                    continue;
                }

                // 初始化工作区
                k8sClusterService.execInPod(pool.getNamespace(), newPodName, CMD_INIT_WORKSPACE);

                // 更新实例记录：新 Pod 名 + 恢复状态
                inst.setPodName(newPodName);
                inst.setInitialized(1);
                // 新 Pod 工作区为空，装载产物不复存在，失效旧指纹
                inst.setResourceFingerprint(null);
                inst.setLastRecycleTime(LocalDateTime.now());
                if (residentOrigin) {
                    // 常驻实例修复 —— 恢复 RESIDENT 状态，保留 slotKey/agentId 绑定
                    inst.setStatus(SandboxInstanceStatus.RESIDENT);
                    log.info("[Reconcile][A3] 常驻实例修复成功（绑定保留）: instanceId={}, slotKey={}, agentId={}, newPodName={}",
                            inst.getInstanceId(), inst.getSlotKey(), inst.getAgentId(), newPodName);
                } else {
                    inst.setStatus(SandboxInstanceStatus.IDLE);
                    inst.setSlotKey(null);
                    inst.setUserId(null);
                    inst.setAgentId(null);
                    inst.setSessionId(null);
                }
                instanceMapper.updateById(inst);

                repaired++;
                log.info("[Reconcile] 修复成功: instanceId={}, newPodName={}, pool={}, resident={}",
                        inst.getInstanceId(), newPodName, pool.getPoolCode(), residentOrigin);
            } catch (Exception e) {
                log.error("[Reconcile] 修复异常: instanceId={}, error={}",
                        inst.getInstanceId(), e.getMessage());
            }
        }
        return repaired;
    }

    // =========================================================================
    // 3. 回收脏 IDLE 实例
    // =========================================================================

    /**
     * 回收脏 IDLE 实例：IDLE(initialized=0) 且空闲超时 -> 回收 -> IDLE(initialized=1)。
     *
     * <p>回收模式由配置 {@code aegis.admin.sandbox.reconcile.hard-recycle} 控制（默认硬回收）：
     * <ul>
     *   <li>硬回收：销毁旧 Pod + 从镜像重建新 Pod + 工作区初始化</li>
     *   <li>软回收：仅清理工作区目录，Pod 保持运行</li>
     * </ul>
     *
     * @return 回收的实例数
     */
    private int recycleDirtyIdle(SandboxPool pool) {
        int idleTimeoutMin = pool.getIdleTimeoutMin() != null ? pool.getIdleTimeoutMin() : 30;
        LocalDateTime idleThreshold = LocalDateTime.now().minusMinutes(idleTimeoutMin);

        List<SandboxInstance> dirtyIdle = instanceMapper.selectDirtyIdleTimeout(
                pool.getId(), idleThreshold);
        if (dirtyIdle.isEmpty()) {
            return 0;
        }

        // 确保 Namespace 存在（硬回收会创建新 Pod）
        ensureNamespace(pool.getNamespace());

        int recycled = 0;
        for (SandboxInstance inst : dirtyIdle) {
            try {
                instanceManageService.recycle(inst.getInstanceId());
                recycled++;
            } catch (Exception e) {
                log.error("[Reconcile] 回收失败: instanceId={}, error={}",
                        inst.getInstanceId(), e.getMessage());
            }
        }
        return recycled;
    }

    // =========================================================================
    // 4. 预热补充
    // =========================================================================

    /**
     * 预热补充：确保池内有 ≥ min_instances 个干净 IDLE 实例。
     *
     * <p>逻辑：
     * <ol>
     *   <li>统计干净 IDLE 数（initialized=1）</li>
     *   <li>如果 < min_instances，计算缺口</li>
     *   <li>缺口受 max_instances 约束（不超过容量上限）</li>
     *   <li>逐个创建 Pod + 等待 Running + 初始化工作区 + 插入 sbx_instance</li>
     * </ol>
     *
     * @return 预热创建的实例数
     */
    private int preheat(SandboxPool pool) {
        if (!k8sClusterService.isAvailable()) {
            return 0;
        }
        int minInstances = pool.getMinInstances() != null ? pool.getMinInstances() : 1;
        int maxInstances = pool.getMaxInstances() != null ? pool.getMaxInstances() : 5;

        int idleClean = instanceMapper.countIdleClean(pool.getId());
        if (idleClean >= minInstances) {
            return 0; // 已满足
        }

        int deficit = minInstances - idleClean;
        int active = instanceMapper.countActive(pool.getId());
        // 预热不超过 max_instances 上限
        int capped = Math.min(deficit, maxInstances - active);
        if (capped <= 0) {
            log.debug("[Reconcile] 池 {} 预热被 max_instances 限制: idleClean={}, min={}, active={}, max={}",
                    pool.getPoolCode(), idleClean, minInstances, active, maxInstances);
            return 0;
        }

        String imageRef = resolveImageRef(pool);
        int preheated = 0;

        // 确保 Namespace 存在（防御式检查，防止 K8s 集群重置后 Namespace 丢失）
        ensureNamespace(pool.getNamespace());

        for (int i = 0; i < capped; i++) {
            try {
                String podName = generatePodName(pool.getPoolCode());
                String instanceId = generateInstanceId();

                Map<String, String> labels = new HashMap<>();
                labels.put("tenant", String.valueOf(pool.getTenantId()));
                labels.put("pool", pool.getPoolCode());

                // 创建 Pod
                PodCreateResult created = k8sClusterService.createSandboxPod(
                        pool.getNamespace(), podName, imageRef,
                        pool.getCpuLimit(), pool.getMemLimitMb(), labels);
                if (created == PodCreateResult.QUOTA_EXCEEDED) {
                    log.warn("[Reconcile] 预热中止（ResourceQuota 已满，已补 {} 个，剩余 {} 个待下轮对账释放后处理）: pool={}",
                            pool.getPoolCode(), preheated, capped - i - 1);
                    break;
                }
                if (created != PodCreateResult.CREATED) {
                    log.warn("[Reconcile] 预热失败（Pod 创建失败）: pool={}, podName={}",
                            pool.getPoolCode(), podName);
                    continue;
                }

                // 等待 Pod Running
                boolean running = k8sClusterService.waitForPodRunning(
                        pool.getNamespace(), podName,
                        properties.getReconcile().getPodWaitTimeoutMs(),
                        properties.getReconcile().getPodWaitIntervalMs());
                if (!running) {
                    log.warn("[Reconcile] 预热失败（Pod 等待超时）: pool={}, podName={}",
                            pool.getPoolCode(), podName);
                    k8sClusterService.deletePod(pool.getNamespace(), podName);
                    continue;
                }

                // 初始化工作区
                k8sClusterService.execInPod(pool.getNamespace(), podName, CMD_INIT_WORKSPACE);

                // 插入 sbx_instance 记录
                SandboxInstance instance = SandboxInstance.builder()
                        .instanceId(instanceId)
                        .poolId(pool.getId())
                        .tenantId(pool.getTenantId())
                        .status(SandboxInstanceStatus.IDLE)
                        .podName(podName)
                        .namespace(pool.getNamespace())
                        .initialized(1)
                        .baseImageId(pool.getBaseImageId())
                        .startTime(LocalDateTime.now())
                        .lastRecycleTime(LocalDateTime.now())
                        .reuseCount(0)
                        .build();
                instanceMapper.insert(instance);

                preheated++;
                log.info("[Reconcile] 预热成功: pool={}, instanceId={}, podName={}",
                        pool.getPoolCode(), instanceId, podName);
            } catch (Exception e) {
                log.error("[Reconcile] 预热异常: pool={}, error={}", pool.getPoolCode(), e.getMessage());
            }
        }
        return preheated;
    }

    // =========================================================================
    // 5. 缩容销毁
    // =========================================================================

    /**
     * 缩容销毁：活跃实例 > max_instances 时，销毁多余 IDLE 实例。
     *
     * <p>优先销毁最旧的 IDLE 实例（按 last_recycle_time 升序）。
     *
     * @return 销毁的实例数
     */
    private int scaleDown(SandboxPool pool) {
        int maxInstances = pool.getMaxInstances() != null ? pool.getMaxInstances() : 5;
        int active = instanceMapper.countActive(pool.getId());
        if (active <= maxInstances) {
            return 0;
        }

        int excess = active - maxInstances;
        int minIdleMinutes = properties.getReconcile().getMinIdleMinutes();
        List<SandboxInstance> idleInstances = instanceMapper.selectIdleForScaleDown(
                pool.getId(), excess, minIdleMinutes);
        if (idleInstances.isEmpty()) {
            log.debug("[Reconcile] 池 {} 无空闲超过 {} 分钟的 IDLE 实例，跳过缩容",
                    pool.getPoolCode(), minIdleMinutes);
            return 0;
        }

        int scaledDown = 0;
        for (SandboxInstance inst : idleInstances) {
            try {
                if (k8sClusterService.isAvailable() && StringUtils.hasText(inst.getPodName())) {
                    k8sClusterService.deletePod(pool.getNamespace(), inst.getPodName());
                }
                instanceMapper.markDestroyed(inst.getInstanceId());
                scaledDown++;
                log.info("[Reconcile] 缩容销毁: pool={}, instanceId={}, podName={}",
                        pool.getPoolCode(), inst.getInstanceId(), inst.getPodName());
            } catch (Exception e) {
                log.error("[Reconcile] 缩容异常: instanceId={}, error={}",
                        inst.getInstanceId(), e.getMessage());
            }
        }
        return scaledDown;
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * 确保 Namespace 存在（防御式检查，K8s 集群重置后 Namespace 可能丢失）。
     */
    private void ensureNamespace(String namespace) {
        if (!StringUtils.hasText(namespace)) {
            return;
        }
        if (!k8sClusterService.namespaceExists(namespace)) {
            log.warn("[Reconcile] Namespace 不存在，正在创建: {}", namespace);
            k8sClusterService.createNamespace(namespace);
        }
    }

    /**
     * 解析池对应镜像的完整引用。
     */
    private String resolveImageRef(SandboxPool pool) {
        if (pool.getBaseImageId() == null) {
            return "docker.io/library/python:3.11-slim";
        }
        SandboxBaseImage image = baseImageMapper.selectById(pool.getBaseImageId());
        if (image == null) {
            return "docker.io/library/python:3.11-slim";
        }
        IImageRegistry registry = imageRegistryRouter.route(image.getRegistryType());
        return registry.getImageRef(pool.getTenantId(), image.getRepository(), image.getTag());
    }

    /**
     * 生成 Pod 名称（sbx-{poolCode}-{8位UUID}）。
     *
     * <p>K8s Pod 名称须符合 RFC 1123 子域名规范：仅小写字母、数字、'-'、'.'，
     * 必须以字母数字开头和结尾。poolCode 可能含下划线等非法字符（如 TEST_POOL），
     * 需将非法字符替换为 '-'，并合并连续 '-'、去除首尾 '-'。
     */
    private String generatePodName(String poolCode) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String safeCode = poolCode.toLowerCase()
                .replaceAll("[^a-z0-9.-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.-]+|[.-]+$", "");
        if (safeCode.isEmpty()) {
            safeCode = "pool";
        }
        return "sbx-" + safeCode + "-" + suffix;
    }

    /**
     * 生成实例 ID（UUID）。
     */
    private String generateInstanceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // =========================================================================
    // Reconcile 结果统计
    // =========================================================================

    /**
     * 单池 Reconcile 处理结果。
     */
    private static class ReconcileResult {
        int healthChecked = 0;
        int occupiedRecovered = 0;
        int orphanPodsReclaimed = 0;
        int recycled = 0;
        int preheated = 0;
        int scaledDown = 0;
        int repaired = 0;
    }
}