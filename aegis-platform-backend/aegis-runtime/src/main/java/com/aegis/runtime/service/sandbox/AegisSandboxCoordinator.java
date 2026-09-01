package com.aegis.runtime.service.sandbox;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.sandbox.IsolationContext;
import com.aegis.core.domain.sandbox.SandboxAllocationContext;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxLease;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.domain.tenant.TenantQuota;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.spi.IDistributedLock;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.dal.mapper.sandbox.SandboxBaseImageMapper;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.runtime.service.sandbox.SandboxInstanceService;
import com.aegis.runtime.infrastructure.sandbox.client.AegisSandboxState;
import com.aegis.runtime.service.sandbox.SandboxLeaseService;
import com.aegis.runtime.service.sandbox.SandboxLifecycleManager;
import com.aegis.runtime.service.sandbox.SandboxPoolRouter;
import com.aegis.runtime.service.metering.TenantQuotaService;
import com.aegis.runtime.service.sandbox.SlotKeyParser;
import io.agentscope.harness.agent.IsolationScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Aegis 沙箱资源协调器（V2 - 租约+分布式锁版）。
 *
 * <p>桥接 sbx_pool / sbx_instance 为 AgentScope 沙箱后端的资源协调器。
 *
 * <h3>V2 核心变更</h3>
 * <ul>
 *   <li>引入 {@link IDistributedLock} 替代 JVM 内锁，支持多 Runtime 实例并发安全</li>
 *   <li>引入 {@link SandboxLeaseService} 租约机制，释放改为软释放（租约过期后 Reconcile 自然回收）</li>
 *   <li>新增 {@link #renewSlot} 心跳续约方法</li>
 *   <li>支持 {@link IsolationStrategy} 隔离策略选择</li>
 * </ul>
 *
 * <h3>P0-2 核心变更（池路由强制化）</h3>
 * <ul>
 *   <li>分配入口接入 {@link SandboxPoolRouter#resolveByAgentMeta}：按 (tenantId, agentType, strategy) 路由目标池</li>
 *   <li>IDLE 选取增加 pool_id 过滤：杜绝跨池取用</li>
 *   <li>池空分支改为池内动态创建（{@link ISandboxBackend#createInPool}）：镜像/限额取自池配置，
 *       写入 pool_id、执行标准 workspace 初始化，admin Reconcile 可统一纳管</li>
 *   <li>池满（达到 max_instances）返回 SERVICE_UNAVAILABLE，不再旁路直建池外孤儿实例</li>
 * </ul>
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>{@link #allocateSlot} - 分配沙箱：分布式锁保护 + 租约创建</li>
 *   <li>{@link #releaseSlot} - 释放沙箱：软释放（租约短缓冲），不立即置脏</li>
 *   <li>{@link #renewSlot} - 心跳续约：延长租约过期时间</li>
 *   <li>{@link #probeAlive} - 探活沙箱实例</li>
 *   <li>{@link #saveSnapshot} - 保存工作空间快照</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisSandboxCoordinator {

    private final ISandboxBackend sandboxBackend;
    private final SandboxInstanceService sandboxInstanceService;
    private final TenantQuotaService tenantQuotaService;
    private final SandboxLifecycleManager lifecycleManager;
    private final SandboxInstanceMapper sandboxInstanceMapper;
    private final IDistributedLock distributedLock;
    private final SandboxLeaseService leaseService;
    private final SandboxPoolRouter poolRouter;
    private final SandboxBaseImageMapper baseImageMapper;

    private static final long LEASE_DURATION_MINUTES = 30;
    private static final long RELEASE_BUFFER_SECONDS = 60;
    private static final long LOCK_WAIT_SECONDS = 10;
    private static final long LOCK_LEASE_SECONDS = 30;

    /** 池内创建的工作区初始化命令（与 admin 预热/回收还原保持一致） */
    private static final String CMD_INIT_WORKSPACE =
            "mkdir -p /workspace/input /workspace/output /workspace/scripts /workspace/temp && echo ok";

    /** 镜像缺失时的兜底镜像（与 admin resolveImageRef 兜底一致） */
    private static final String DEFAULT_IMAGE_REF = "docker.io/library/python:3.11-slim";

    /**
     * 分配沙箱槽位（V2：分布式锁 + 租约机制）。
     *
     * @param scope     隔离作用域
     * @param slotKey   槽位键
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param agentId   Agent ID
     * @param sessionId 会话 ID
     * @return 沙箱分配结果（包含 instanceId, podName, namespace）
     */
    public SandboxAllocationContext allocateSlot(IsolationScope scope, String slotKey, Long tenantId,
                               Long userId, Long agentId, String sessionId) {
        return allocateSlot(scope, slotKey, tenantId, userId, agentId, sessionId,
                IsolationStrategy.SHARED_PER_SCOPE, null);
    }

    /**
     * 分配沙箱槽位（V2：带隔离策略）。
     *
     * @param scope    隔离作用域
     * @param slotKey  槽位键
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param agentId  Agent ID
     * @param sessionId 会话 ID
     * @param strategy 隔离策略
     * @return 沙箱分配结果（包含 instanceId, podName, namespace）
     */
    public SandboxAllocationContext allocateSlot(IsolationScope scope, String slotKey, Long tenantId,
                               Long userId, Long agentId, String sessionId,
                               IsolationStrategy strategy) {
        return allocateSlot(scope, slotKey, tenantId, userId, agentId, sessionId, strategy, null);
    }

    /**
     * 分配沙箱槽位（P0-2：池路由强制化 + 池内动态创建；A3：RESIDENT 常驻语义）。
     *
     * <p>分配优先级：
     * <ol>
     *   <li>复用：按 slotKey 查找 OCCUPIED/RESIDENT 实例（探活成功则续约复用）</li>
     *   <li>取池：按 (tenantId, agentType, strategy) 路由目标池，从池内选取干净 IDLE（含 pool_id 过滤）</li>
     *   <li>池内创建：池空且未达 max_instances 时，在池命名空间内按池配置创建新实例</li>
     * </ol>
     *
     * <p>A3 扩展：slotKey 为 RESIDENT 格式（{@code aegis:resident:sys:{agentId}}）时：
     * <ul>
     *   <li>复用命中 → 仅刷新心跳（RESIDENT 无租约，无过期概念）</li>
     *   <li>新分配/池内创建 → 状态置 RESIDENT（非 OCCUPIED），不创建租约</li>
     * </ul>
     *
     * @param scope     隔离作用域
     * @param slotKey   槽位键
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param agentId   Agent ID
     * @param sessionId 会话 ID
     * @param strategy  隔离策略
     * @param agentType 智能体类型（UNIVERSAL/APPLICATION/SYSTEM，池路由决策输入，可为 null）
     * @return 沙箱分配结果（包含 instanceId, podName, namespace）
     */
    public SandboxAllocationContext allocateSlot(IsolationScope scope, String slotKey, Long tenantId,
                               Long userId, Long agentId, String sessionId,
                               IsolationStrategy strategy, String agentType) {
        validateScope(scope);

        // A3：RESIDENT 常驻槽位判定（系统智能体专属）
        boolean residentSlot = SlotKeyParser.isResidentSlot(slotKey);

        String lockKey = "sandbox:lock:" + slotKey;
        boolean locked = distributedLock.tryLock(lockKey, LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        if (!locked) {
            log.warn("获取沙箱分配锁失败: slotKey={}", slotKey);
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE, "系统繁忙，请稍后重试");
        }

        try {
            // A3：RESIDENT 实例不计入租户动态配额（常驻容量与动态容量分离核算）
            if (!residentSlot) {
                enforceSandboxQuota(tenantId);
            }

            SandboxInstance existing = sandboxInstanceService.findOccupiedBySlotKey(slotKey);
            if (existing != null) {
                if (!java.util.Objects.equals(existing.getTenantId(), tenantId)) {
                    log.warn("跨租户沙箱复用拦截: slotKey={}, requestTenantId={}, ownerTenantId={}",
                            slotKey, tenantId, existing.getTenantId());
                    markAbnormal(existing);
                    existing = null;
                }
            }

            if (existing != null) {
                // P5-5：DEDICATED_PER_SESSION 不复用旧 Pod，走新分配路径
                if (strategy == IsolationStrategy.DEDICATED_PER_SESSION) {
                    log.info("DEDICATED 策略强制新分配: slotKey={}, strategy={}", slotKey, strategy);
                } else if (probeInstance(existing)) {
                    log.info("沙箱槽位复用+续约: slotKey={}, instanceId={}, podName={}, namespace={}, status={}",
                            slotKey, existing.getInstanceId(), existing.getPodName(), existing.getNamespace(),
                            existing.getStatus());
                    sandboxInstanceService.incrementReuseCount(existing.getInstanceId());
                    if (residentSlot) {
                        // A3：RESIDENT 复用仅刷新心跳（无租约，常驻不过期）
                        lifecycleManager.heartbeat(existing.getInstanceId());
                    } else {
                        renewLeaseForInstance(existing.getInstanceId(), sessionId, slotKey);
                    }
                    return SandboxAllocationContext.success(
                            existing.getInstanceId(), existing.getPodName(),
                            existing.getNamespace(), slotKey);
                } else {
                    log.warn("沙箱容器不可用，标记ABNORMAL: slotKey={}, oldInstanceId={}",
                            slotKey, existing.getInstanceId());
                    markAbnormal(existing);
                }
            }

            // P0-2：池路由决策（强制走池，杜绝旁路创建）
            SandboxPool targetPool = resolveTargetPool(tenantId, agentType, strategy);

            SandboxInstance idle = sandboxInstanceService.findIdleByScope(tenantId, scope.name(),
                    targetPool != null ? targetPool.getId() : null);
            if (idle != null) {
                // P0-09: 防御性检查：确保 IDLE 实例对应的 Pod 真实存在（使用 podName/namespace 探活）
                if (!probeInstance(idle)) {
                    log.warn("IDLE 实例对应的 Pod 不可用，标记 ABNORMAL: instanceId={}, podName={}",
                            idle.getInstanceId(), idle.getPodName());
                    markAbnormal(idle);
                    // 递归重试（或手动查找下一个 IDLE）
                    idle = sandboxInstanceService.findIdleByScope(tenantId, scope.name(),
                            targetPool != null ? targetPool.getId() : null);
                    if (idle != null && !probeInstance(idle)) {
                        log.warn("重试后仍无可用 IDLE 实例，Pod 均不可用");
                        idle = null;
                    }
                }
            }

            if (idle != null) {
                IsolationContext context = IsolationContext.builder()
                        .tenantId(tenantId)
                        .userId(userId)
                        .agentId(agentId)
                        .sessionId(sessionId)
                        .isolationScope(toAegisIsolationScope(scope))
                        .build();

                SandboxAllocationContext result = lifecycleManager.allocate(idle, context);
                if (residentSlot) {
                    // A3：常驻绑定 —— 分配后状态转为 RESIDENT，不创建租约
                    promoteToResident(idle, result.getInstanceId());
                    log.info("A3 常驻绑定建立(干净IDLE): slotKey={}, instanceId={}, podName={}, namespace={}, poolId={}",
                            slotKey, result.getInstanceId(), result.getPodName(), result.getNamespace(), idle.getPoolId());
                } else {
                    leaseService.createLease(result.getInstanceId(), sessionId, slotKey,
                            LEASE_DURATION_MINUTES, TimeUnit.MINUTES);
                    log.info("沙箱槽位分配(干净IDLE): slotKey={}, instanceId={}, podName={}, namespace={}, strategy={}, poolId={}",
                            slotKey, result.getInstanceId(), result.getPodName(), result.getNamespace(),
                            strategy, idle.getPoolId());
                }
                return result;
            }

            // P0-2：IDLE 池为空 → 池内动态创建（禁止池外旁路直建）
            return createInPoolInstance(targetPool, scope, slotKey, tenantId,
                    userId, agentId, sessionId, strategy, residentSlot);
        } finally {
            distributedLock.unlock(lockKey);
        }
    }

    /**
     * A3：将新分配实例提升为 RESIDENT 常驻绑定状态。
     *
     * <p>allocate 先置 OCCUPIED（复用既有乐观锁分配路径），此处原子转为 RESIDENT。
     * 转换失败不影响业务（下次分配仍可重试提升）。
     */
    private void promoteToResident(SandboxInstance instance, String instanceId) {
        try {
            int updated = sandboxInstanceMapper.updateStatus(
                    instanceId, SandboxInstanceStatus.RESIDENT.name());
            if (updated > 0) {
                log.info("A3 实例提升为 RESIDENT: instanceId={}, agentId={}",
                        instanceId, instance.getAgentId());
            }
        } catch (Exception e) {
            log.error("A3 提升 RESIDENT 状态失败（保持 OCCUPIED，不阻断分配）: instanceId={}",
                    instanceId, e);
        }
    }

    /**
     * P0-2：池路由决策。
     *
     * <p>按 (tenantId, agentType, strategy) 调用 {@link SandboxPoolRouter#resolveByAgentMeta}：
     * UNIVERSAL→LIGHT、SYSTEM→HEAVY、其他（含 null）→STANDARD；
     * 找不到匹配类型时逐级兜底（租户私有任意池 → 系统共享任意池）。
     *
     * @return 目标池配置，无可用池时返回 null（由池内创建分支 fail-fast）
     */
    private SandboxPool resolveTargetPool(Long tenantId, String agentType, IsolationStrategy strategy) {
        SandboxPool targetPool = poolRouter.resolveByAgentMeta(tenantId, agentType, strategy);
        if (targetPool == null) {
            log.warn("沙箱池路由未命中可用池: tenantId={}, agentType={}, strategy={}",
                    tenantId, agentType, strategy);
        } else {
            log.info("沙箱池路由决策: tenantId={}, agentType={}, strategy={} → poolId={}, poolCode={}, poolType={}, namespace={}",
                    tenantId, agentType, strategy, targetPool.getId(), targetPool.getPoolCode(),
                    targetPool.getPoolType(), targetPool.getNamespace());
        }
        return targetPool;
    }

    /**
     * P0-2/S-G3：池内动态创建沙箱实例（A3 扩展：residentSlot 时落库为 RESIDENT）。
     *
     * <p>替代原"sandboxBackend.create() 裸调用"旁路路径：
     * <ol>
     *   <li>容量校验：池内活跃实例（IDLE+OCCUPIED）达 max_instances 则拒绝（SERVICE_UNAVAILABLE）</li>
     *   <li>镜像解析：池关联 sbx_base_image → 完整镜像引用（兜底 python:3.11-slim）</li>
     *   <li>Pod 创建：在池命名空间内按池资源限额创建（{@link ISandboxBackend#createInPool}）</li>
     *   <li>工作区初始化：执行标准 CMD_INIT_WORKSPACE，标记 initialized=1</li>
     *   <li>落库：写入 sbx_instance（instanceId=UUID，poolId 归属池，tenantId=占用方租户）</li>
     * </ol>
     * 新实例与 admin 预热产物同构，可被 Reconcile 统一纳管（回收还原、预热补充、缩容销毁）。
     *
     * @param pool         目标池（null 时 fail-fast，拒绝无池创建）
     * @param residentSlot A3：true 表示 RESIDENT 常驻绑定（落库 RESIDENT 状态，不建租约）
     */
    private SandboxAllocationContext createInPoolInstance(SandboxPool pool, IsolationScope scope, String slotKey,
                                                            Long tenantId, Long userId, Long agentId,
                                                            String sessionId, IsolationStrategy strategy,
                                                            boolean residentSlot) {
        if (pool == null) {
            log.error("池内创建失败：无可用沙箱池（拒绝池外旁路创建）: tenantId={}, scope={}, slotKey={}",
                    tenantId, scope, slotKey);
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE,
                    "无可用沙箱池，拒绝池外创建: slotKey=" + slotKey);
        }

        // 1. 容量校验（池满即拒绝，排队语义后续引入；A3: countActive 不含 RESIDENT，常驻容量分离核算）
        int maxInstances = pool.getMaxInstances() != null ? pool.getMaxInstances() : 5;
        int active = sandboxInstanceMapper.countActive(pool.getId());
        if (active >= maxInstances) {
            log.warn("沙箱池已满，拒绝分配: poolCode={}, active={}, max={}, slotKey={}, resident={}",
                    pool.getPoolCode(), active, maxInstances, slotKey, residentSlot);
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE,
                    "沙箱池已满: poolCode=" + pool.getPoolCode()
                    + ", active=" + active + ", max=" + maxInstances);
        }

        // 2. 镜像与资源限额解析
        String imageRef = resolvePoolImageRef(pool);
        double cpu = parseCpuLimit(pool.getCpuLimit());
        int memMb = pool.getMemLimitMb() != null ? pool.getMemLimitMb() : 512;

        log.info("池内动态创建沙箱实例: poolCode={}, namespace={}, image={}, cpu={}, memMb={}, slotKey={}, active={}/{}",
                pool.getPoolCode(), pool.getNamespace(), imageRef, cpu, memMb, slotKey, active, maxInstances);
        try {
            // 3. 池内创建 Pod（命名空间归属池，标签携带 tenant/pool 归属标识）
            Map<String, String> labels = new HashMap<>(4);
            labels.put("tenant", String.valueOf(tenantId));
            labels.put("pool", pool.getPoolCode());
            String k8sResourceId = sandboxBackend.createInPool(
                    tenantId, pool.getNamespace(), imageRef, cpu, memMb, labels);

            String[] parts = k8sResourceId.split("/", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("createInPool 返回非法实例标识（期望 namespace/podName）: " + k8sResourceId);
            }
            String namespace = parts[0];
            String podName = parts[1];

            // 4. 标准工作区初始化（与 admin 预热产物同构，initialized=1）
            ISandboxBackend.ExecResult initResult = sandboxBackend.exec(
                    tenantId, k8sResourceId, CMD_INIT_WORKSPACE, 30);
            if (initResult == null || initResult.exitCode != 0) {
                log.warn("池内创建工作区初始化失败（继续分配）: instanceId={}, exitCode={}, stderr={}",
                        k8sResourceId, initResult != null ? initResult.exitCode : -1,
                        initResult != null ? initResult.stderr : "null");
            }

            // 5. 落库（instanceId=UUID，与 admin 预热格式一致；tenantId=占用方租户；
            //    A3: residentSlot 时状态直接落 RESIDENT，跳过 OCCUPIED 中间态）
            String instanceId = UUID.randomUUID().toString().replace("-", "");
            SandboxInstance newInstance = SandboxInstance.builder()
                    .instanceId(instanceId)
                    .poolId(pool.getId())
                    .tenantId(tenantId)
                    .status(residentSlot ? SandboxInstanceStatus.RESIDENT : SandboxInstanceStatus.OCCUPIED)
                    .userId(userId)
                    .agentId(agentId)
                    .sessionId(sessionId)
                    .slotKey(slotKey)
                    .podName(podName)
                    .namespace(namespace)
                    .isolationScope(scope.name())
                    .initialized(1)
                    .baseImageId(pool.getBaseImageId())
                    .startTime(LocalDateTime.now())
                    .allocatedTime(LocalDateTime.now())
                    .reuseCount(0)
                    .version(0)
                    .build();

            sandboxInstanceMapper.insert(newInstance);
            log.info("池内动态创建沙箱实例成功: poolCode={}, instanceId={}, podName={}, namespace={}, slotKey={}, resident={}",
                    pool.getPoolCode(), instanceId, podName, namespace, slotKey, residentSlot);

            // 6. 租约（A3: RESIDENT 常驻不建租约，无过期概念）
            if (!residentSlot) {
                leaseService.createLease(instanceId, sessionId, slotKey,
                        LEASE_DURATION_MINUTES, TimeUnit.MINUTES);
            }

            SandboxAllocationContext result = SandboxAllocationContext.created(
                    instanceId, podName, namespace, slotKey);
            if (residentSlot) {
                log.info("A3 常驻绑定建立(池内创建): slotKey={}, instanceId={}, podName={}, namespace={}, poolId={}",
                        slotKey, instanceId, podName, namespace, pool.getId());
            } else {
                log.info("沙箱槽位分配(池内创建): slotKey={}, instanceId={}, podName={}, namespace={}, strategy={}",
                        slotKey, instanceId, podName, namespace, strategy);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("池内动态创建沙箱实例失败: poolCode={}, tenantId={}, scope={}, error={}",
                    pool.getPoolCode(), tenantId, scope, e.getMessage(), e);
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE,
                    "池内创建沙箱实例失败: " + e.getMessage());
        }
    }

    /**
     * P0-2：解析池关联镜像的完整引用。
     *
     * <p>池未配置镜像或镜像记录缺失时，兜底 python:3.11-slim（与 admin resolveImageRef 一致）。
     */
    private String resolvePoolImageRef(SandboxPool pool) {
        if (pool.getBaseImageId() == null) {
            return DEFAULT_IMAGE_REF;
        }
        SandboxBaseImage image = baseImageMapper.selectById(pool.getBaseImageId());
        if (image == null) {
            log.warn("池关联镜像记录缺失，使用兜底镜像: poolCode={}, baseImageId={}",
                    pool.getPoolCode(), pool.getBaseImageId());
            return DEFAULT_IMAGE_REF;
        }
        String tag = (image.getTag() != null && !image.getTag().isBlank()) ? image.getTag() : "latest";
        String repository = image.getRepository() != null ? image.getRepository() : "library/python";
        if (image.getRegistry() != null && !image.getRegistry().isBlank()) {
            return image.getRegistry() + "/" + repository + ":" + tag;
        }
        return repository + ":" + tag;
    }

    /**
     * P0-2：解析池 CPU 限额配置（String → double，非法值兜底 1.0 核）。
     */
    private double parseCpuLimit(String cpuLimit) {
        if (cpuLimit == null || cpuLimit.isBlank()) {
            return 1.0;
        }
        try {
            return Double.parseDouble(cpuLimit.trim());
        } catch (NumberFormatException e) {
            log.warn("池 CPU 限额配置非法，兜底 1.0 核: cpuLimit={}", cpuLimit);
            return 1.0;
        }
    }

    /**
     * P6-3：带池路由的沙箱分配（显式指定 poolCode）。
     *
     * @param poolCode 目标池编码（可选，为 null 时自动路由）
     */
    public SandboxAllocationContext allocateSlot(String poolCode, IsolationScope scope, String slotKey,
                               Long tenantId, Long userId, Long agentId,
                               String sessionId, IsolationStrategy strategy) {
        // 显式池路由校验
        if (poolCode != null && !poolCode.isEmpty()) {
            SandboxPool targetPool = poolRouter.resolveByCode(tenantId, poolCode);
            if (targetPool == null) {
                log.warn("指定池不存在或未启用: poolCode={}, tenantId={}", poolCode, tenantId);
                throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE,
                        "指定沙箱池不可用: " + poolCode);
            }
        }
        // 调用原有分配逻辑（后续版本将按 targetPool 过滤 IDLE 实例）
        return allocateSlot(scope, slotKey, tenantId, userId, agentId, sessionId, strategy);
    }

    /**
     * 释放沙箱槽位（V2：软释放 + 租约短缓冲）。
     *
     * <p>V2 核心变更：不再立即标记脏 IDLE，而是释放租约（设置短过期时间），
     * 让 Reconcile 自然检测租约过期后回收。这样避免了"抽地基"问题。</p>
     *
     * <p>P5-3：共享模式下先保存会话级快照，再释放租约。
     * 快照存储以 sessionId 为 key，供后续会话恢复时严格绑定会话。</p>
     *
     * @param tenantId     租户 ID
     * @param instanceId   沙箱实例 ID
     * @param saveSnapshot 是否保存工作空间快照
     */
    public void releaseSlot(Long tenantId, String instanceId, boolean saveSnapshot) {
        SandboxInstance instance = sandboxInstanceService.findByInstanceId(instanceId);
        if (instance == null) {
            log.warn("释放失败：实例不存在: instanceId={}", instanceId);
            return;
        }

        // A3：RESIDENT 常驻实例不释放（系统智能体专属，仅刷新心跳）
        if (instance.getStatus() == SandboxInstanceStatus.RESIDENT
                || SlotKeyParser.isResidentSlot(instance.getSlotKey())) {
            log.info("A3 常驻实例释放被拦截（常驻绑定不回收）: instanceId={}, slotKey={}, agentId={}",
                    instanceId, instance.getSlotKey(), instance.getAgentId());
            lifecycleManager.heartbeat(instanceId);
            return;
        }

        // P5-3：会话级快照 —— 以 sessionId 为 key 归档，确保会话隔离
        if (saveSnapshot) {
            try {
                String snapshotKey = buildSessionSnapshotKey(instance, tenantId);
                sandboxBackend.snapshot(tenantId, instanceId);
                sandboxInstanceService.updateSnapshotInfo(instanceId, snapshotKey, LocalDateTime.now());
                log.info("工作空间快照已保存(会话级): instanceId={}, snapshotKey={}", instanceId, snapshotKey);
            } catch (Exception e) {
                log.warn("快照保存失败，继续释放: instanceId={}", instanceId, e);
            }
        }

        // P5-2：仅清理当前 session 子目录下的临时文件与输出，其他会话数据保留
        String sessionScopedCleanCmd = buildSessionCleanCmd(instance);
        if (sessionScopedCleanCmd != null) {
            try {
                sandboxBackend.exec(tenantId, instanceId, sessionScopedCleanCmd, 30);
            } catch (Exception e) {
                log.debug("清理会话临时目录失败（忽略）: instanceId={}", instanceId, e);
            }
        }

        String slotKey = instance.getSlotKey();
        if (slotKey != null) {
            java.util.List<SandboxLease> activeLeases = leaseService.findActiveBySlotKey(slotKey);
            if (activeLeases.size() <= 1) {
                lifecycleManager.release(instance);
                markDirtyAfterRelease(instanceId);
                log.info("沙箱已释放(最后租约): instanceId={}, 标记脏IDLE", instanceId);
            } else {
                log.info("沙箱仍有其他活跃租约: instanceId={}, activeLeases={}",
                        instanceId, activeLeases.size());
            }

            for (SandboxLease lease : activeLeases) {
                leaseService.releaseLease(lease.getLeaseId(), RELEASE_BUFFER_SECONDS);
            }
        } else {
            lifecycleManager.release(instance);
            markDirtyAfterRelease(instanceId);
            log.info("沙箱已释放(无租约): instanceId={}", instanceId);
        }
    }

    /**
     * P5-3：构造会话级快照 key（以 sessionId 为 key 归档）。
     */
    private String buildSessionSnapshotKey(SandboxInstance instance, Long tenantId) {
        String sessionId = instance.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return "snap-" + instance.getInstanceId() + "-" + System.currentTimeMillis();
        }
        return "snap-" + tenantId + "-" + sessionId + "-" + System.currentTimeMillis();
    }

    /**
     * P5-2：按 session 构造子目录清理命令，避免影响其他会话数据。
     */
    private String buildSessionCleanCmd(SandboxInstance instance) {
        String sessionId = instance.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            return "rm -rf /workspace/temp/* /workspace/output/*";
        }
        Long tenantId = instance.getTenantId();
        Long agentId = instance.getAgentId();
        // 默认走 SHARED_PER_SCOPE 路径；若为 DEDICATED / QUOTA，工作区同样隔离到会话子目录
        String root = "/workspace/" + tenantId + "/" + agentId + "/" + sessionId;
        return "rm -rf " + root + "/temp/* " + root + "/output/*";
    }

    /**
     * V2 新增：心跳续约（Agent 执行期间调用）。
     *
     * <p>延长租约过期时间，防止长任务执行过程中租约过期被 Reconcile 回收。</p>
     *
     * @param instanceId 沙箱实例 ID
     * @param sessionId  会话 ID
     */
    public void renewSlot(String instanceId, String sessionId) {
        SandboxInstance instance = sandboxInstanceService.findByInstanceId(instanceId);
        if (instance == null) {
            return;
        }

        String slotKey = instance.getSlotKey();
        if (slotKey == null) {
            return;
        }

        java.util.List<SandboxLease> activeLeases = leaseService.findActiveBySlotKey(slotKey);
        boolean renewed = false;
        for (SandboxLease lease : activeLeases) {
            if (sessionId != null && sessionId.equals(lease.getSessionId())) {
                leaseService.renewLease(lease.getLeaseId(), LEASE_DURATION_MINUTES, TimeUnit.MINUTES);
                renewed = true;
                break;
            }
        }

        if (!renewed && !activeLeases.isEmpty()) {
            leaseService.renewLease(activeLeases.get(0).getLeaseId(),
                    LEASE_DURATION_MINUTES, TimeUnit.MINUTES);
        }

        lifecycleManager.heartbeat(instanceId);
        log.debug("沙箱心跳续约: instanceId={}, sessionId={}", instanceId, sessionId);
    }

    /**
     * 探测沙箱容器是否存活。
     */
    public boolean probeAlive(Long tenantId, String instanceId) {
        return sandboxBackend.probeAlive(tenantId, instanceId);
    }

    /**
     * P0-09: 通过 podName 和 namespace 探测沙箱容器是否存活。
     */
    public boolean probeAlive(Long tenantId, String podName, String namespace) {
        return sandboxBackend.probeAlive(tenantId, podName, namespace);
    }

    /**
     * P0-09: 基于实例探活，优先使用 podName 和 namespace。
     */
    private boolean probeInstance(SandboxInstance instance) {
        if (instance.getPodName() != null && instance.getNamespace() != null) {
            return probeAlive(instance.getTenantId(), instance.getPodName(), instance.getNamespace());
        }
        return probeAlive(instance.getTenantId(), instance.getInstanceId());
    }

    /**
     * 在沙箱内执行命令。
     */
    public ISandboxBackend.ExecResult exec(Long tenantId, String instanceId,
                                            String command, long timeoutSec) {
        return sandboxBackend.exec(tenantId, instanceId, command, timeoutSec);
    }

    /**
     * 保存沙箱快照。
     */
    public String saveSnapshot(Long tenantId, String instanceId) {
        String snapshotId = sandboxBackend.snapshot(tenantId, instanceId);
        sandboxInstanceService.updateSnapshotInfo(instanceId, snapshotId, LocalDateTime.now());
        log.info("沙箱快照保存: instanceId={}, snapshotId={}", instanceId, snapshotId);
        return snapshotId;
    }

    /**
     * P6-2：沙箱配额校验（租户级 maxSandboxes 限制）。
     *
     * <p>统计当前租户占用的沙箱实例（OCCUPIED + 干净 IDLE），若已达上限则拒绝分配。
     * 配额为 null 或 <=0 时表示无限制，直接放行。</p>
     *
     * @param tenantId 租户ID
     * @throws BusinessException 配额超限时抛出
     */
    public void enforceSandboxQuota(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        TenantQuota quota = tenantQuotaService.findQuotaByTenant(tenantId);
        if (quota == null || quota.getMaxSandboxes() == null || quota.getMaxSandboxes() <= 0) {
            return;
        }
        long current = sandboxInstanceService.countOccupiedPlusCleanIdle(tenantId);
        if (current >= quota.getMaxSandboxes()) {
            log.warn("沙箱配额超限: tenantId={}, used={}, max={}", tenantId, current, quota.getMaxSandboxes());
            throw new BusinessException(ResultCode.QUOTA_EXCEEDED,
                    "沙箱配额超限: tenantId=" + tenantId
                    + ", used=" + current + ", max=" + quota.getMaxSandboxes());
        }
    }

    /**
     * 按 slotKey 查找当前 OCCUPIED 状态的沙箱实例。
     */
    public SandboxInstance findOccupiedBySlotKey(String slotKey) {
        return sandboxInstanceService.findOccupiedBySlotKey(slotKey);
    }

    /**
     * P2 补丁：补充沙箱状态中缺失的 podName 和 namespace。
     * 
     * <p>当从旧会话恢复时，podName 和 namespace 可能为 null，
     * 需要从数据库查询补充。</p>
     *
     * @param state 待补充的沙箱状态
     */
    public void enrichSandboxState(AegisSandboxState state) {
        if (state.getPodName() != null && state.getNamespace() != null) {
            return; // 已有完整信息，无需补充
        }
        if (state.getInstanceId() == null) {
            log.warn("沙箱状态缺少 instanceId，无法补充 podName/namespace");
            return;
        }
        try {
            SandboxInstance instance = sandboxInstanceService.findByInstanceId(state.getInstanceId());
            if (instance != null) {
                if (state.getPodName() == null && instance.getPodName() != null) {
                    state.setPodName(instance.getPodName());
                }
                if (state.getNamespace() == null && instance.getNamespace() != null) {
                    state.setNamespace(instance.getNamespace());
                }
                log.info("补充沙箱状态信息: instanceId={}, podName={}, namespace={}",
                        state.getInstanceId(), state.getPodName(), state.getNamespace());
            } else {
                log.warn("数据库中未找到沙箱实例: instanceId={}", state.getInstanceId());
            }
        } catch (Exception e) {
            log.error("补充沙箱状态信息失败: instanceId={}", state.getInstanceId(), e);
        }
    }

    private void renewLeaseForInstance(String instanceId, String sessionId, String slotKey) {
        java.util.List<SandboxLease> activeLeases = leaseService.findActiveBySlotKey(slotKey);
        for (SandboxLease lease : activeLeases) {
            leaseService.renewLease(lease.getLeaseId(), LEASE_DURATION_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void markAbnormal(SandboxInstance instance) {
        int version = instance.getVersion() != null ? instance.getVersion() : 0;
        int updated = sandboxInstanceMapper.updateStatusWithVersion(
                instance.getInstanceId(),
                SandboxInstanceStatus.ABNORMAL.name(),
                version);
        if (updated > 0) {
            log.info("实例标记为 ABNORMAL: instanceId={}", instance.getInstanceId());
        } else {
            log.debug("标记 ABNORMAL 乐观锁冲突，跳过: instanceId={}", instance.getInstanceId());
        }
    }

    private void markDirtyAfterRelease(String instanceId) {
        sandboxInstanceService.markDirtyAfterRelease(instanceId);
    }

    private void validateScope(IsolationScope scope) {
    }

    private com.aegis.core.enums.sandbox.IsolationScope toAegisIsolationScope(IsolationScope scope) {
        if (scope == null) {
            return null;
        }
        return com.aegis.core.enums.sandbox.IsolationScope.valueOf(scope.name());
    }
}