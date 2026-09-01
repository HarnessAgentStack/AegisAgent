package com.aegis.runtime.infrastructure.startup;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import com.aegis.runtime.service.sandbox.SandboxLifecycleManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 沙箱心跳调度器。
 *
 * <p>定时扫描所有 {@link SandboxInstanceStatus#OCCUPIED} 状态的沙箱实例，
 * 对每个实例发送心跳（调用 {@link SandboxLifecycleManager#heartbeat(String)}），
 * 更新 {@code last_heartbeat_time} 字段，用于 admin Reconcile 超时回收精准判定。</p>
 *
 * <h3>心跳策略</h3>
 * <ul>
 *   <li>调度间隔：默认 30 秒，可通过 {@code aegis.runtime.heartbeat.interval-ms} 配置</li>
 *   <li>超时阈值：默认 60 秒，可通过 {@code aegis.runtime.heartbeat.timeout-seconds} 配置</li>
 *   <li>优化：如果 {@code lastHeartbeatTime} 已在超时阈值内，跳过更新（避免频繁 DB 写入）</li>
 * </ul>
 *
 * <h3>与 admin Reconcile 协作</h3>
 * <p>本调度器负责 runtime 侧的心跳写入，admin 的
 * {@code SandboxReconcileScheduler} 负责扫描超时未心跳的 OCCUPIED 实例
 * 并强制回收（标记为 ABNORMAL → 回收）。</p>
 *
 * @author wang.zhen
 * @see SandboxLifecycleManager#heartbeat(String)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxHeartbeatScheduler {

    private final SandboxInstanceMapper instanceMapper;
    private final SandboxLifecycleManager lifecycleManager;

    @Value("${aegis.runtime.heartbeat.interval-ms:30000}")
    private long intervalMs;

    @Value("${aegis.runtime.heartbeat.timeout-seconds:60}")
    private long timeoutSeconds;

    /**
     * 心跳定时任务。
     *
     * <p>默认每 30 秒执行一次，扫描所有 OCCUPIED 状态的实例，
     * 逐个检查 lastHeartbeatTime，仅对超过超时阈值的实例发送心跳。</p>
     */
    @Scheduled(fixedDelayString = "${aegis.runtime.heartbeat.interval-ms:30000}")
    public void heartbeat() {
        List<SandboxInstance> occupiedInstances = instanceMapper.selectByStatus(
                SandboxInstanceStatus.OCCUPIED.name());

        if (occupiedInstances.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int sent = 0;
        int skipped = 0;

        for (SandboxInstance instance : occupiedInstances) {
            if (shouldSkipHeartbeat(instance, now)) {
                skipped++;
                continue;
            }

            try {
                lifecycleManager.heartbeat(instance.getInstanceId());
                sent++;
                log.debug("[heartbeat] 心跳发送成功: instanceId={}", instance.getInstanceId());
            } catch (Exception e) {
                log.warn("[heartbeat] 心跳发送失败: instanceId={}", instance.getInstanceId(), e);
            }
        }

        if (sent > 0 || skipped > 0) {
            log.info("[heartbeat] 心跳完成: total={}, sent={}, skipped={}",
                    occupiedInstances.size(), sent, skipped);
        }
    }

    /**
     * 判断是否跳过心跳（lastHeartbeatTime 仍在超时阈值内）。
     *
     * @param instance 沙箱实例
     * @param now      当前时间
     * @return true=跳过（不需要更新），false=需要发送心跳
     */
    private boolean shouldSkipHeartbeat(SandboxInstance instance, LocalDateTime now) {
        LocalDateTime lastHeartbeat = instance.getLastHeartbeatTime();
        if (lastHeartbeat == null) {
            return false;
        }
        long secondsSinceLastBeat = ChronoUnit.SECONDS.between(lastHeartbeat, now);
        return secondsSinceLastBeat < timeoutSeconds;
    }
}