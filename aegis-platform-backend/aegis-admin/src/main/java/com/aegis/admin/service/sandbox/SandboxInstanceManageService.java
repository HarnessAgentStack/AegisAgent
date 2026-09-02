package com.aegis.admin.service.sandbox;

import com.aegis.admin.config.infra.SandboxK8sProperties;
import com.aegis.admin.infrastructure.sandbox.K8sClusterService;
import com.aegis.admin.infrastructure.sandbox.spi.ImageRegistryRouter;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.spi.IImageRegistry;
import com.aegis.dal.mapper.sandbox.SandboxBaseImageMapper;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 沙箱实例领域服务（管理平面）。
 *
 * <p>负责实例的查询、回收、销毁等管理操作。
 * 实际的分配（allocateSlot）由 aegis-runtime 的 AegisSandboxCoordinator 负责，
 * 本服务仅提供管理视角的操作（监控、回收、销毁）。
 *
 * <h3>回收策略（由配置 {@code aegis.admin.sandbox.reconcile.hard-recycle} 控制）</h3>
 * <ul>
 *   <li><b>硬回收（默认）</b>：销毁旧 Pod + 从镜像重建新 Pod + 工作区初始化。
 *       确保使用过的沙箱完全恢复到镜像初始状态。</li>
 *   <li><b>软回收</b>：仅清理工作区目录（exec rm + mkdir），Pod 保持运行。
 *       成本低但无法还原系统级变更（如 pip install）。</li>
 * </ul>
 *
 * <h3>销毁 = 删除 Pod</h3>
 * <p>销毁操作 = 删除 K8s Pod + 标记 DESTROYED。仅用于缩容、故障修复、池删除。
 *
 * @author wang.zhen
 */
@Service
@RequiredArgsConstructor
public class SandboxInstanceManageService {

    private static final Logger log = LoggerFactory.getLogger(SandboxInstanceManageService.class);

    private final SandboxInstanceMapper instanceMapper;
    private final SandboxPoolMapper poolMapper;
    private final K8sClusterService k8sClusterService;
    private final SandboxBaseImageMapper baseImageMapper;
    private final ImageRegistryRouter imageRegistryRouter;
    private final SandboxK8sProperties properties;

    /** 工作区清理命令：删除用户数据（软回收使用） */
    private static final String CMD_CLEAN_WORKSPACE =
            "rm -rf /workspace/input/* /workspace/output/* /workspace/temp/* /workspace/scripts/* 2>/dev/null; " +
            "rm -rf /tmp/* 2>/dev/null; true";

    /** 工作区重建命令：重建标准目录 */
    private static final String CMD_REBUILD_WORKSPACE =
            "mkdir -p /workspace/input /workspace/output /workspace/scripts /workspace/temp && echo ok";

    /** 工作区初始化命令：创建标准目录（硬回收/预热/修复使用） */
    private static final String CMD_INIT_WORKSPACE =
            "mkdir -p /workspace/input /workspace/output /workspace/scripts /workspace/temp && echo ok";

    /**
     * 分页查询实例（支持按池/状态/租户过滤）。
     */
    public Page<SandboxInstance> page(long current, long size, Long poolId, String status,
                                      Long tenantId, String instanceId) {
        LambdaQueryWrapper<SandboxInstance> wrapper = new LambdaQueryWrapper<SandboxInstance>()
                .orderByDesc(SandboxInstance::getCreateTime);
        if (poolId != null) {
            wrapper.eq(SandboxInstance::getPoolId, poolId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SandboxInstance::getStatus, SandboxInstanceStatus.valueOf(status));
        }
        if (tenantId != null) {
            wrapper.eq(SandboxInstance::getTenantId, tenantId);
        }
        if (StringUtils.hasText(instanceId)) {
            wrapper.eq(SandboxInstance::getInstanceId, instanceId);
        }
        return instanceMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 按 instanceId 查询。
     */
    public SandboxInstance getByInstanceId(String instanceId) {
        return instanceMapper.selectOne(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getInstanceId, instanceId));
    }

    /**
     * 回收实例：根据配置选择硬回收或软回收。
     *
     * <p>配置项 {@code aegis.admin.sandbox.reconcile.hard-recycle}（默认 true）：
     * <ul>
     *   <li>true = 硬回收：销毁旧 Pod + 从镜像重建新 Pod + 工作区初始化</li>
     *   <li>false = 软回收：仅清理工作区目录，Pod 保持运行</li>
     * </ul>
     *
     * @param instanceId 实例 ID
     */
    public void recycle(String instanceId) {
        if (properties.getReconcile().isHardRecycle()) {
            recycleHard(instanceId);
        } else {
            recycleSoft(instanceId);
        }
    }

    /**
     * 硬回收：销毁旧 Pod + 从镜像重建新 Pod + 工作区初始化。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>销毁旧 Pod（K8s deletePod）</li>
     *   <li>解析池关联镜像，创建新 Pod</li>
     *   <li>等待新 Pod Running</li>
     *   <li>初始化工作区（exec mkdir）</li>
     *   <li>更新 DB：pod_name=新Pod, status=IDLE, initialized=1</li>
     * </ol>
     * 硬回收确保沙箱完全恢复到镜像初始状态，包括系统级变更（如 pip install）。
     *
     * @param instanceId 实例 ID
     */
    public void recycleHard(String instanceId) {
        SandboxInstance instance = getByInstanceId(instanceId);
        if (instance == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实例不存在: " + instanceId);
        }
        SandboxPool pool = poolMapper.selectById(instance.getPoolId());
        if (pool == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实例所属池不存在");
        }
        String namespace = pool.getNamespace();
        String oldPodName = instance.getPodName();

        // K8s 不可用时降级为软回收
        if (!k8sClusterService.isAvailable()) {
            log.warn("[SandboxInstance] K8s 不可用，硬回收降级为仅更新 DB 状态: instanceId={}", instanceId);
            instanceMapper.updateRecycleComplete(instanceId, LocalDateTime.now());
            return;
        }

        // 1. 销毁旧 Pod
        if (StringUtils.hasText(oldPodName)) {
            try {
                k8sClusterService.deletePod(namespace, oldPodName);
            } catch (Exception e) {
                log.warn("[SandboxInstance] 销毁旧 Pod 失败（继续创建新 Pod）: instanceId={}, oldPodName={}, error={}",
                        instanceId, oldPodName, e.getMessage());
            }
        }

        // 2. 解析镜像并创建新 Pod
        String imageRef = resolveImageRef(pool);
        String newPodName = generatePodName(pool.getPoolCode());

        Map<String, String> labels = new HashMap<>();
        labels.put("tenant", String.valueOf(pool.getTenantId()));
        labels.put("pool", pool.getPoolCode());

        K8sClusterService.PodCreateResult created = k8sClusterService.createSandboxPod(
                namespace, newPodName, imageRef,
                pool.getCpuLimit(), pool.getMemLimitMb(), labels);
        if (created != K8sClusterService.PodCreateResult.CREATED) {
            String reason = created == K8sClusterService.PodCreateResult.QUOTA_EXCEEDED
                    ? "集群资源配额(ResourceQuota)已满" : "Pod 创建失败";
            log.error("[SandboxInstance] 硬回收失败（{}）: instanceId={}, pool={}",
                    reason, instanceId, pool.getPoolCode());
            instanceMapper.updateStatus(instanceId, SandboxInstanceStatus.ABNORMAL.name());
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "硬回收失败：" + reason + "，已标记异常，等待自动修复");
        }

        // 3. 等待新 Pod Running
        boolean running = k8sClusterService.waitForPodRunning(
                namespace, newPodName,
                properties.getReconcile().getPodWaitTimeoutMs(),
                properties.getReconcile().getPodWaitIntervalMs());
        if (!running) {
            log.error("[SandboxInstance] 硬回收失败（Pod 等待超时）: instanceId={}, newPodName={}",
                    instanceId, newPodName);
            k8sClusterService.deletePod(namespace, newPodName);
            instanceMapper.updateStatus(instanceId, SandboxInstanceStatus.ABNORMAL.name());
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "硬回收失败：Pod 等待超时，已标记异常，等待自动修复");
        }

        // 4. 初始化工作区
        k8sClusterService.execInPod(namespace, newPodName, CMD_INIT_WORKSPACE);

        // 5. 更新 DB：新 Pod 名 + IDLE(initialized=1)
        instanceMapper.updateRecycleCompleteWithPod(instanceId, newPodName, LocalDateTime.now());

        log.info("[SandboxInstance] 硬回收（Pod 重建）成功: instanceId={}, oldPod={}, newPod={}, poolId={}",
                instanceId, oldPodName, newPodName, instance.getPoolId());
    }

    /**
     * 软回收：仅清理工作区目录，Pod 保持运行。
     *
     * <p>执行步骤：
     * <ol>
     *   <li>清理用户工作区数据（exec rm -rf）</li>
     *   <li>重建标准目录（exec mkdir）</li>
     *   <li>更新 DB：status=IDLE, initialized=1, last_recycle_time=NOW()</li>
     * </ol>
     * Pod 保持运行，不销毁。成本低但无法还原系统级变更。
     *
     * @param instanceId 实例 ID
     */
    public void recycleSoft(String instanceId) {
        SandboxInstance instance = getByInstanceId(instanceId);
        if (instance == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实例不存在: " + instanceId);
        }
        SandboxPool pool = poolMapper.selectById(instance.getPoolId());
        if (pool == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实例所属池不存在");
        }
        String namespace = pool.getNamespace();
        String podName = instance.getPodName();

        // 1. 工作区清理（exec 命令）
        if (k8sClusterService.isAvailable() && StringUtils.hasText(namespace) && StringUtils.hasText(podName)) {
            String phase = k8sClusterService.getPodPhase(namespace, podName);
            if (!"Running".equals(phase)) {
                log.warn("[SandboxInstance] Pod 非 Running 状态，跳过工作区清理: instanceId={}, phase={}",
                        instanceId, phase);
                instanceMapper.updateStatus(instanceId, SandboxInstanceStatus.ABNORMAL.name());
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "Pod 不可用 (phase=" + phase + ")，已标记异常，等待自动修复");
            }

            k8sClusterService.execInPod(namespace, podName, CMD_CLEAN_WORKSPACE);
            String result = k8sClusterService.execInPod(namespace, podName, CMD_REBUILD_WORKSPACE);
            if (!"ok".equals(result)) {
                log.warn("[SandboxInstance] 工作区重建可能失败: instanceId={}, result={}", instanceId, result);
            }
        } else {
            log.warn("[SandboxInstance] K8s 不可用，仅更新 DB 状态: instanceId={}", instanceId);
        }

        // 2. 更新 DB：标记 IDLE(initialized=1)
        instanceMapper.updateRecycleComplete(instanceId, LocalDateTime.now());

        log.info("[SandboxInstance] 软回收（工作区重初始化）成功: instanceId={}, poolId={}",
                instanceId, instance.getPoolId());
    }

    /**
     * 销毁实例（删除 Pod，标记 DESTROYED）。
     *
     * <p>仅用于缩容、故障修复、池删除。正常回收请使用 {@link #recycle}。
     */
    public void destroy(String instanceId) {
        SandboxInstance instance = getByInstanceId(instanceId);
        if (instance == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实例不存在: " + instanceId);
        }
        SandboxPool pool = poolMapper.selectById(instance.getPoolId());
        String namespace = pool != null ? pool.getNamespace() : null;
        String podName = instance.getPodName();

        if (k8sClusterService.isAvailable() && StringUtils.hasText(namespace) && StringUtils.hasText(podName)) {
            k8sClusterService.deletePod(namespace, podName);
        }
        instanceMapper.markDestroyed(instanceId);
        log.info("[SandboxInstance] 销毁成功: instanceId={}", instanceId);
    }

    /**
     * 获取实例的 K8s Pod 状态。
     */
    public Map<String, Object> getPodStatus(String instanceId) {
        SandboxInstance instance = getByInstanceId(instanceId);
        if (instance == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实例不存在: " + instanceId);
        }
        SandboxPool pool = poolMapper.selectById(instance.getPoolId());
        Map<String, Object> status = new HashMap<>();
        status.put("instanceId", instanceId);
        status.put("dbStatus", instance.getStatus());
        status.put("initialized", instance.getInitialized());
        if (pool != null && k8sClusterService.isAvailable()) {
            status.put("podPhase", k8sClusterService.getPodPhase(pool.getNamespace(), instance.getPodName()));
        } else {
            status.put("podPhase", "UNKNOWN");
        }
        return status;
    }

    /**
     * 统计：按状态分组计数。
     */
    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new HashMap<>();
        for (SandboxInstanceStatus status : SandboxInstanceStatus.values()) {
            Long count = instanceMapper.selectCount(new LambdaQueryWrapper<SandboxInstance>()
                    .eq(SandboxInstance::getStatus, status));
            result.put(status.name(), count);
        }
        return result;
    }

    // =========================================================================
    // 硬回收工具方法
    // =========================================================================

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
     * 必须以字母数字开头和结尾。poolCode 可能含下划线等非法字符，需替换。
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
}