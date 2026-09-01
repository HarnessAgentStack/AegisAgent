package com.aegis.admin.service.sandbox;

import com.aegis.admin.infrastructure.sandbox.K8sClusterService;
import com.aegis.admin.infrastructure.sandbox.spi.ImageRegistryRouter;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.sandbox.SandboxBaseImage;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.sandbox.SandboxPool;
import com.aegis.core.enums.monitor.PoolStatus;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.enums.sandbox.SandboxPoolType;
import com.aegis.dal.mapper.sandbox.SandboxBaseImageMapper;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.dal.mapper.sandbox.SandboxPoolMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 沙箱池领域服务（管理平面）。
 *
 * <p>基于两参数驱动模型（minInstances / maxInstances / idleTimeoutMin）管理池的生命周期。
 *
 * <h3>核心职责</h3>
 * <ul>
 *   <li>创建池：K8s 资源预检查 → 创建 K8s 资源（事务外）→ 写入 DB → 触发预热</li>
 *   <li>更新池：更新配置 + 同步 K8s ResourceQuota / NetworkPolicy</li>
 *   <li>删除池：销毁所有 Pod → 清理 K8s 资源 → 逻辑删除 DB</li>
 *   <li>修复池：手动重建 K8s 资源（管理员诊断用）</li>
 * </ul>
 *
 * <h3>关键设计决策</h3>
 * <ul>
 *   <li>K8s 调用移出 @Transactional，先创 K8s 再写 DB（避免事务内 K8s 失败导致不一致）</li>
 *   <li>不再存储 total_count / used_count（由 sbx_instance 实时统计）</li>
 *   <li>不再提供 warmup / syncAll 方法（预热由 Reconcile 循环自动执行）</li>
 *   <li>系统共享池（tenant_id=0）可删除（移除硬编码限制）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxPoolManageService {

    private final SandboxPoolMapper poolMapper;
    private final SandboxInstanceMapper instanceMapper;
    private final SandboxBaseImageMapper baseImageMapper;
    private final K8sClusterService k8sClusterService;
    private final ImageRegistryRouter imageRegistryRouter;

    private static final String QUOTA_NAME = "aegis-sbx-quota";
    private static final String NETWORK_POLICY_NAME = "aegis-sbx-network";

    /**
     * 分页查询池（含系统共享池 + 当前租户私有池）。
     */
    public Page<SandboxPool> page(long current, long size, String poolName, String poolType, String status) {
        Long tenantId = TenantContextHolder.getTenantId();
        LambdaQueryWrapper<SandboxPool> wrapper = new LambdaQueryWrapper<SandboxPool>()
                .and(w -> w.eq(SandboxPool::getTenantId, 0L)
                           .or()
                           .eq(SandboxPool::getTenantId, tenantId == null ? 0L : tenantId))
                .orderByDesc(SandboxPool::getCreateTime);
        if (StringUtils.hasText(poolName)) {
            wrapper.like(SandboxPool::getPoolName, poolName);
        }
        if (StringUtils.hasText(poolType)) {
            wrapper.eq(SandboxPool::getPoolType, SandboxPoolType.valueOf(poolType));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SandboxPool::getStatus, PoolStatus.valueOf(status));
        }
        return poolMapper.selectPage(new Page<>(current, size), wrapper);
    }

    /**
     * 列出所有启用的池。
     */
    public List<SandboxPool> listEnabled() {
        Long tenantId = TenantContextHolder.getTenantId();
        return poolMapper.selectList(new LambdaQueryWrapper<SandboxPool>()
                .and(w -> w.eq(SandboxPool::getTenantId, 0L)
                           .or()
                           .eq(SandboxPool::getTenantId, tenantId == null ? 0L : tenantId))
                .eq(SandboxPool::getStatus, PoolStatus.ENABLED)
                .orderByAsc(SandboxPool::getPoolType));
    }

    /**
     * 按 ID 查询。
     */
    public SandboxPool getById(Long id) {
        return poolMapper.selectById(id);
    }

    /**
     * 新建池（★ 两参数驱动模型：K8s 预检查 → 先创 K8s → 写 DB）。
     *
     * <p>流程：
     * <ol>
     *   <li>参数校验（base_image 存在、min ≤ max、idle_timeout > 0）</li>
     *   <li>K8s 资源预检查（集群是否有足够资源承载 min_instances × 单实例规格）</li>
     *   <li>创建 K8s 资源（Namespace + ResourceQuota + NetworkPolicy）— 事务外</li>
     *   <li>写入 DB（@Transactional）</li>
     * </ol>
     * K8s 调用失败时不写 DB，确保数据一致。
     */
    public SandboxPool create(SandboxPool pool) {
        Long tenantId = TenantContextHolder.getTenantId();
        pool.setTenantId(tenantId == null ? 0L : tenantId);

        // ===== Step 1: 参数校验 =====
        validatePoolConfig(pool);

        // 自动生成 poolCode / namespace
        if (!StringUtils.hasText(pool.getPoolCode())) {
            pool.setPoolCode(generatePoolCode(pool.getTenantId(), pool.getPoolType()));
        }
        if (!StringUtils.hasText(pool.getNamespace())) {
            pool.setNamespace(generateNamespace(pool.getTenantId(), pool.getPoolType()));
        }
        if (pool.getStatus() == null) {
            pool.setStatus(PoolStatus.ENABLED);
        }
        // 设置默认值
        if (pool.getMinInstances() == null) pool.setMinInstances(1);
        if (pool.getMaxInstances() == null) pool.setMaxInstances(5);
        if (pool.getIdleTimeoutMin() == null) pool.setIdleTimeoutMin(30);
        if (!StringUtils.hasText(pool.getCpuLimit())) pool.setCpuLimit("1.0");
        if (pool.getMemLimitMb() == null) pool.setMemLimitMb(2048);
        if (pool.getDiskLimitGb() == null) pool.setDiskLimitGb(10);
        if (pool.getNetworkPolicy() == null) pool.setNetworkPolicy(
                com.aegis.core.enums.sandbox.NetworkPolicy.RESTRICTED);

        // 校验 poolCode 唯一
        Long count = poolMapper.selectCount(new LambdaQueryWrapper<SandboxPool>()
                .eq(SandboxPool::getTenantId, pool.getTenantId())
                .eq(SandboxPool::getPoolCode, pool.getPoolCode()));
        if (count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "池编码已存在: " + pool.getPoolCode());
        }

        // 校验基础镜像
        SandboxBaseImage image = baseImageMapper.selectById(pool.getBaseImageId());
        if (image == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "基础镜像不存在: " + pool.getBaseImageId());
        }

        // ===== Step 2: K8s 资源预检查（API 异常时降级为跳过，不阻断建池） =====
        double requiredCpu = pool.getMinInstances() * parseCpuToDouble(pool.getCpuLimit());
        int requiredMem = pool.getMinInstances() * pool.getMemLimitMb();
        K8sClusterService.ClusterResourceCheckResult checkResult =
                k8sClusterService.checkClusterResource(requiredCpu, requiredMem);
        if (!checkResult.isSufficient()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, checkResult.getMessage());
        }
        if (checkResult.getMessage().startsWith("集群资源检查跳过")) {
            log.warn("[SandboxPool] K8s 资源预检查跳过: poolCode={}, reason={}", pool.getPoolCode(), checkResult.getMessage());
        } else {
            log.info("[SandboxPool] K8s 资源预检查通过: poolCode={}, requiredCpu={}, requiredMem={}MB",
                    pool.getPoolCode(), requiredCpu, requiredMem);
        }

        // ===== Step 3: 创建 K8s 资源（事务外，失败则抛异常不写 DB） =====
        createK8sResources(pool);

        // ===== Step 4: 写入 DB =====
        poolMapper.insert(pool);

        log.info("[SandboxPool] 新建池成功: id={}, code={}, ns={}, min={}, max={}",
                pool.getId(), pool.getPoolCode(), pool.getNamespace(),
                pool.getMinInstances(), pool.getMaxInstances());
        return pool;
    }

    /**
     * 更新池配置（同步 K8s Quota/NetworkPolicy）。
     *
     * <p>允许更新 min_instances / max_instances / idle_timeout_min / 资源规格 / 网络策略。
     * 不允许修改 tenantId / poolCode / namespace / baseImageId。
     */
    @Transactional(rollbackFor = Exception.class)
    public SandboxPool update(SandboxPool pool) {
        SandboxPool existing = poolMapper.selectById(pool.getId());
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "沙箱池不存在");
        }

        // 不允许改 tenantId / poolCode / namespace
        pool.setTenantId(existing.getTenantId());
        pool.setPoolCode(existing.getPoolCode());
        pool.setNamespace(existing.getNamespace());

        // 参数校验
        if (pool.getMinInstances() != null && pool.getMaxInstances() != null) {
            if (pool.getMinInstances() > pool.getMaxInstances()) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "min_instances 不能大于 max_instances");
            }
        }

        poolMapper.updateById(pool);

        // 同步 K8s ResourceQuota / NetworkPolicy（事务外行为，失败仅告警）
        SandboxPool merged = poolMapper.selectById(pool.getId());
        updateK8sResources(merged);

        log.info("[SandboxPool] 更新池成功: id={}, code={}, min={}, max={}",
                pool.getId(), pool.getPoolCode(), merged.getMinInstances(), merged.getMaxInstances());
        return merged;
    }

    /**
     * 启用/停用/维护池。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String status) {
        SandboxPool existing = poolMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "沙箱池不存在");
        }
        SandboxPool update = new SandboxPool();
        update.setId(id);
        update.setStatus(PoolStatus.valueOf(status));
        poolMapper.updateById(update);
        log.info("[SandboxPool] 池状态变更: id={}, status={}", id, status);
    }

    /**
     * 删除池。
     *
     * <p>流程：
     * <ol>
     *   <li>查询池</li>
     *   <li>检查是否有 OCCUPIED 实例（有则拒绝删除）</li>
     *   <li>销毁所有 Pod（事务外）</li>
     *   <li>清理 K8s 资源（NetworkPolicy + ResourceQuota + Namespace）</li>
     *   <li>逻辑删除 DB 记录</li>
     * </ol>
     */
    public void delete(Long id) {
        SandboxPool existing = poolMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "沙箱池不存在");
        }

        // 校验无 OCCUPIED 实例
        Long occupiedCount = instanceMapper.selectCount(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getPoolId, id)
                .eq(SandboxInstance::getStatus, SandboxInstanceStatus.OCCUPIED));
        if (occupiedCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "池内有 " + occupiedCount + " 个占用中实例，无法删除，请等待释放");
        }

        // 销毁所有 Pod（事务外）
        List<SandboxInstance> instances = instanceMapper.selectList(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getPoolId, id)
                .in(SandboxInstance::getStatus, SandboxInstanceStatus.IDLE, SandboxInstanceStatus.ABNORMAL));
        for (SandboxInstance inst : instances) {
            if (StringUtils.hasText(inst.getPodName())) {
                k8sClusterService.deletePod(existing.getNamespace(), inst.getPodName());
            }
            instanceMapper.markDestroyed(inst.getInstanceId());
        }

        // 清理 K8s 资源（事务外）
        cleanupK8sResources(existing.getNamespace());

        // 逻辑删除 DB 记录
        poolMapper.deleteById(id);
        // 批量逻辑删除实例
        instanceMapper.delete(new LambdaQueryWrapper<SandboxInstance>()
                .eq(SandboxInstance::getPoolId, id));

        log.info("[SandboxPool] 删除池成功: id={}, code={}, ns={}",
                id, existing.getPoolCode(), existing.getNamespace());
    }

    /**
     * 获取池的 K8s 资源状态（Pod 列表 + 命名空间是否存在 + 实例统计）。
     */
    public Map<String, Object> getK8sStatus(Long poolId) {
        SandboxPool pool = poolMapper.selectById(poolId);
        if (pool == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "沙箱池不存在");
        }
        Map<String, Object> status = new HashMap<>();
        status.put("poolId", poolId);
        status.put("poolCode", pool.getPoolCode());
        status.put("namespace", pool.getNamespace());
        status.put("k8sAvailable", k8sClusterService.isAvailable());
        status.put("namespaceExists", k8sClusterService.namespaceExists(pool.getNamespace()));
        status.put("pods", k8sClusterService.listSandboxPods(pool.getNamespace()));

        // 实例统计
        int idleClean = instanceMapper.countIdleClean(poolId);
        int active = instanceMapper.countActive(poolId);
        Map<String, Object> stats = new HashMap<>();
        stats.put("idleClean", idleClean);
        stats.put("active", active);
        stats.put("minInstances", pool.getMinInstances());
        stats.put("maxInstances", pool.getMaxInstances());
        status.put("instanceStats", stats);
        return status;
    }

    /**
     * 手动修复池 K8s 资源（重建 Namespace + ResourceQuota + NetworkPolicy）。
     *
     * <p>用于 K8s 资源被误删后的修复，或存量池初始化。幂等：已存在则更新。
     */
    public Map<String, Object> repairPoolK8s(Long poolId) {
        SandboxPool pool = poolMapper.selectById(poolId);
        if (pool == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "沙箱池不存在");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("poolId", poolId);
        result.put("poolCode", pool.getPoolCode());
        result.put("namespace", pool.getNamespace());

        if (!k8sClusterService.isAvailable()) {
            result.put("success", false);
            result.put("message", "K8s 集群不可用");
            return result;
        }

        boolean namespaceExistedBefore = k8sClusterService.namespaceExists(pool.getNamespace());
        createK8sResources(pool);

        result.put("success", true);
        result.put("created", !namespaceExistedBefore);
        result.put("namespaceExistsAfter", k8sClusterService.namespaceExists(pool.getNamespace()));
        log.info("[SandboxPool] K8s 资源修复完成: poolId={}, ns={}, created={}",
                poolId, pool.getNamespace(), !namespaceExistedBefore);
        return result;
    }

    // =========================================================================
    // 内部方法
    // =========================================================================

    /**
     * 参数校验。
     */
    private void validatePoolConfig(SandboxPool pool) {
        if (pool.getBaseImageId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "base_image_id 不能为空");
        }
        if (pool.getMinInstances() != null && pool.getMinInstances() < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "min_instances 不能为负数");
        }
        if (pool.getMaxInstances() != null && pool.getMaxInstances() < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "max_instances 至少为 1");
        }
        if (pool.getMinInstances() != null && pool.getMaxInstances() != null
                && pool.getMinInstances() > pool.getMaxInstances()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "min_instances 不能大于 max_instances");
        }
        if (pool.getIdleTimeoutMin() != null && pool.getIdleTimeoutMin() < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "idle_timeout_min 至少为 1 分钟");
        }
    }

    /**
     * 创建 K8s 资源（Namespace + ResourceQuota + NetworkPolicy）。
     * 失败时抛 BusinessException。
     */
    private void createK8sResources(SandboxPool pool) {
        if (!k8sClusterService.isAvailable()) {
            throw new BusinessException(ResultCode.SERVICE_UNAVAILABLE,
                    "K8s 集群未连接，无法创建沙箱池");
        }
        String namespace = pool.getNamespace();

        // Namespace
        if (!k8sClusterService.createNamespace(namespace)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "K8s Namespace 创建失败: " + namespace);
        }

        // ResourceQuota（按 max_instances × 单实例规格计算总量上限）
        int maxInstances = pool.getMaxInstances() != null ? pool.getMaxInstances() : 5;
        String totalCpu = formatCpuQuota(pool.getCpuLimit(), maxInstances);
        int totalMemMb = pool.getMemLimitMb() * maxInstances;
        if (!k8sClusterService.applyResourceQuota(namespace, QUOTA_NAME,
                totalCpu, totalMemMb, maxInstances)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "K8s ResourceQuota 创建失败: " + namespace + "/" + QUOTA_NAME);
        }

        // NetworkPolicy
        String npType = pool.getNetworkPolicy() == null
                ? "RESTRICTED" : pool.getNetworkPolicy().name();
        if (!k8sClusterService.applyNetworkPolicy(namespace, NETWORK_POLICY_NAME, npType)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "K8s NetworkPolicy 创建失败: " + namespace + "/" + NETWORK_POLICY_NAME);
        }
    }

    /**
     * 更新 K8s 资源（仅更新 ResourceQuota 和 NetworkPolicy，Namespace 不重建）。
     * 失败仅告警，不抛异常（更新是幂等操作）。
     */
    private void updateK8sResources(SandboxPool pool) {
        if (!k8sClusterService.isAvailable()) {
            log.warn("[SandboxPool] K8s 不可用，跳过资源同步: poolId={}", pool.getId());
            return;
        }
        String namespace = pool.getNamespace();
        int maxInstances = pool.getMaxInstances() != null ? pool.getMaxInstances() : 5;
        String totalCpu = formatCpuQuota(pool.getCpuLimit(), maxInstances);
        int totalMemMb = pool.getMemLimitMb() * maxInstances;
        k8sClusterService.applyResourceQuota(namespace, QUOTA_NAME, totalCpu, totalMemMb, maxInstances);

        String npType = pool.getNetworkPolicy() == null
                ? "RESTRICTED" : pool.getNetworkPolicy().name();
        k8sClusterService.applyNetworkPolicy(namespace, NETWORK_POLICY_NAME, npType);
    }

    /**
     * 清理 K8s 资源（删除 Namespace 级联删除所有资源）。
     */
    private void cleanupK8sResources(String namespace) {
        if (!StringUtils.hasText(namespace) || !k8sClusterService.isAvailable()) {
            return;
        }
        k8sClusterService.deleteNamespace(namespace);
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
        return imageRegistryRouter.route(image.getRegistryType())
                .getImageRef(pool.getTenantId(), image.getRepository(), image.getTag());
    }

    private String generatePoolCode(Long tenantId, SandboxPoolType poolType) {
        String prefix = tenantId == null || tenantId == 0L ? "SYS" : "T" + tenantId;
        return prefix + "-" + (poolType == null ? "CUSTOM" : poolType.name());
    }

    private String generateNamespace(Long tenantId, SandboxPoolType poolType) {
        long tid = tenantId == null ? 0L : tenantId;
        String typeLower = poolType == null ? "custom" : poolType.name().toLowerCase();
        return "aegis-sbx-t" + tid + "-" + typeLower;
    }

    /**
     * 格式化 K8s ResourceQuota 的 CPU 总量（millicores）。
     *
     * @param cpuLimitPerPod 单 Pod CPU 限制（如 "0.5"、"2"）
     * @param count          实例数
     * @return K8s Quantity 格式（如 "2500m"、"10000m"）
     */
    private String formatCpuQuota(String cpuLimitPerPod, int count) {
        double perPod = parseCpuToDouble(cpuLimitPerPod);
        long totalMillicores = Math.round(perPod * count * 1000);
        return totalMillicores + "m";
    }

    /**
     * 解析 CPU 限制字符串为 double（如 "0.5" → 0.5, "2" → 2.0）。
     */
    private double parseCpuToDouble(String cpuLimit) {
        if (cpuLimit == null || cpuLimit.isBlank()) {
            return 1.0;
        }
        try {
            return Double.parseDouble(cpuLimit);
        } catch (NumberFormatException e) {
            return 1.0;
        }
    }
}