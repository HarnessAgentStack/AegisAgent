package com.aegis.runtime.infrastructure.startup;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.tenant.TenantQuota;
import com.aegis.core.dto.monitor.SandboxUsageVO;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.SandboxInstanceService;
import com.aegis.runtime.service.metering.TenantQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 沙箱健康监控器。
 *
 * <p>仅做健康探活和标记 ABNORMAL，不执行回收。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>{@link #healthCheck()}：每分钟探活实例，失败标记 ABNORMAL</li>
 *   <li>{@link #getUsage(Long)}：查询租户沙箱使用情况</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxHealthMonitor {

    private final SandboxInstanceService sandboxInstanceService;
    private final TenantQuotaService tenantQuotaService;
    private final ISandboxBackend sandboxBackend;

    /** 探活重试退避基数（毫秒），默认 500 */
    @Value("${aegis.sandbox.health.backoff-base-ms:500}")
    private long backoffBaseMs;

    /** 探活重试退避上限（毫秒），默认 8000，避免单次 sleep 过长阻塞调度 */
    @Value("${aegis.sandbox.health.backoff-max-ms:8000}")
    private long backoffMaxMs;

    /**
     * 健康检查：每分钟执行。
     *
     * <p>仅探活并标记 ABNORMAL，不执行回收。
     */
    @Scheduled(fixedDelay = 60000)
    public void healthCheck() {
        // 1. 探活 OCCUPIED 实例（超时未活动先探活，失败才标记 ABNORMAL）
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(30);
        List<SandboxInstance> stale = sandboxInstanceService.listByStatusAndAllocatedBefore(
                SandboxInstanceStatus.OCCUPIED, threshold);
        for (SandboxInstance inst : stale) {
            boolean alive = probeWithRetry(inst, 1);
            if (!alive) {
                sandboxInstanceService.updateStatus(inst.getInstanceId(), SandboxInstanceStatus.ABNORMAL);
                log.warn("OCCUPIED 沙箱标记 ABNORMAL（超时+探活失败）: instanceId={}", inst.getInstanceId());
            }
        }

        // 2. 探活 IDLE 实例（3 次重试，失败标记 ABNORMAL）
        List<SandboxInstance> idleInstances = sandboxInstanceService.listByStatus(
                SandboxInstanceStatus.IDLE);
        for (SandboxInstance inst : idleInstances) {
            boolean alive = probeWithRetry(inst, 3);
            if (!alive) {
                sandboxInstanceService.updateStatus(inst.getInstanceId(), SandboxInstanceStatus.ABNORMAL);
                log.warn("IDLE 沙箱标记 ABNORMAL（3次探活失败）: instanceId={}", inst.getInstanceId());
            }
        }
    }

    /**
     * 查询租户沙箱使用情况。
     */
    public SandboxUsageVO getUsage(Long tenantId) {
        TenantQuota quota = tenantQuotaService.findQuotaByTenant(tenantId);
        int maxSandboxes = quota != null && quota.getMaxSandboxes() != null ? quota.getMaxSandboxes() : 5;

        long occupied = sandboxInstanceService.countByTenantAndStatus(tenantId, SandboxInstanceStatus.OCCUPIED);
        long idle = sandboxInstanceService.countByTenantAndStatus(tenantId, SandboxInstanceStatus.IDLE);
        long abnormal = sandboxInstanceService.countByTenantAndStatus(tenantId, SandboxInstanceStatus.ABNORMAL);

        return SandboxUsageVO.builder()
                .tenantId(tenantId)
                .maxSandboxes(maxSandboxes)
                .occupied((int) occupied)
                .idle((int) idle)
                .abnormal((int) abnormal)
                .total((int) (occupied + idle + abnormal))
                .build();
    }

    /**
     * 带重试的探活，优先使用 podName 和 namespace。
     */
    private boolean probeWithRetry(SandboxInstance instance, int maxRetry) {
        for (int i = 0; i < maxRetry; i++) {
            try {
                // 优先使用 podName 和 namespace 探活，避免 instanceId 格式不匹配问题
                if (instance.getPodName() != null && instance.getNamespace() != null) {
                    if (sandboxBackend.probeAlive(instance.getTenantId(), 
                            instance.getPodName(), instance.getNamespace())) {
                        return true;
                    }
                } else {
                    // 回退：使用 instanceId 探活
                    if (sandboxBackend.probeAlive(instance.getTenantId(), instance.getInstanceId())) {
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("探活重试 {}/{}: instanceId={}, podName={}", 
                        i + 1, maxRetry, instance.getInstanceId(), instance.getPodName());
            }
            if (i < maxRetry - 1) {
                long sleepMs = Math.min(backoffBaseMs * (i + 1), backoffMaxMs);
                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { break; }
            }
        }
        return false;
    }
}