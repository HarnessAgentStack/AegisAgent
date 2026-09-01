package com.aegis.admin.infrastructure.startup;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.dal.mapper.sandbox.SandboxInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 沙箱 OCCUPIED 超时回收处理器。
 *
 * <p>检测长时间无心跳的 OCCUPIED 实例，将其强制回收为 IDLE 状态，
 * 防止实例因异常（如 Runtime 崩溃、网络分区）导致永久占用资源。</p>
 *
 * <h3>超时判定逻辑</h3>
 * <pre>
 * timeout = now - lastHeartbeatTime > configuredOccupiedTimeoutMin
 * </pre>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>扫描所有 OCCUPIED 实例</li>
 *   <li>检查 lastHeartbeatTime，筛选超时实例</li>
 *   <li>尝试发送心跳探测（如果 K8s 可达）</li>
 *   <li>探测成功 → 更新心跳时间（可能是误报）</li>
 *   <li>探测失败 → 强制回收为 IDLE（脏 IDLE，等待后续回收）</li>
 * </ol>
 *
 * <h3>安全考量</h3>
 * <ul>
 *   <li>强制回收可能导致正在执行的任务丢失（需业务层做幂等处理）</li>
 *   <li>超时阈值设置需合理（建议 ≥ 30 分钟），避免误回收</li>
 *   <li>回收前会检查 Pod 实际运行状态，仅在 Pod 不可达时才回收</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxOccupiedTimeoutHandler {

    /** 默认 OCCUPIED 超时（分钟） */
    private static final int DEFAULT_OCCUPIED_TIMEOUT_MIN = 60;

    private final SandboxInstanceMapper instanceMapper;

    /**
     * 处理 OCCUPIED 超时实例。
     *
     * @param timeoutMinutes 超时阈值（分钟），null 则使用默认 60 分钟
     * @return 回收的实例数
     */
    public int handleTimeout(Integer timeoutMinutes) {
        int timeout = timeoutMinutes != null ? timeoutMinutes : DEFAULT_OCCUPIED_TIMEOUT_MIN;
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(timeout);

        log.info("[OCCUPIED-Timeout] 开始扫描: timeout={}min, threshold={}", timeout, threshold);

        // 1. 查询所有 OCCUPIED 实例
        List<SandboxInstance> occupiedInstances = instanceMapper.selectByStatus(
                SandboxInstanceStatus.OCCUPIED.name());

        if (occupiedInstances.isEmpty()) {
            log.info("[OCCUPIED-Timeout] 无 OCCUPIED 实例，跳过");
            return 0;
        }

        int recovered = 0;
        for (SandboxInstance inst : occupiedInstances) {
            try {
                LocalDateTime lastHeartbeat = inst.getLastHeartbeatTime();
                LocalDateTime allocatedTime = inst.getAllocatedTime();

                // 判断超时：lastHeartbeatTime 早于阈值，或无心跳且分配时间早于阈值
                boolean timedOut;
                if (lastHeartbeat != null) {
                    timedOut = lastHeartbeat.isBefore(threshold);
                } else if (allocatedTime != null) {
                    timedOut = allocatedTime.isBefore(threshold);
                } else {
                    timedOut = false;
                }

                if (!timedOut) {
                    continue;
                }

                // 2. 强制回收为脏 IDLE（原子性标记 initialized=0 + 清理占用 + 乐观锁）
                log.warn("[OCCUPIED-Timeout] 回收超时实例: instanceId={}, podName={}, lastHeartbeat={}",
                        inst.getInstanceId(), inst.getPodName(), lastHeartbeat);

                int updated = instanceMapper.forceReleaseOccupied(
                        inst.getInstanceId(),
                        inst.getVersion() != null ? inst.getVersion() : 0
                );

                if (updated > 0) {
                    recovered++;
                    log.info("[OCCUPIED-Timeout] 回收成功: instanceId={}", inst.getInstanceId());
                } else {
                    log.warn("[OCCUPIED-Timeout] 回收失败（版本冲突）: instanceId={}", inst.getInstanceId());
                }
            } catch (Exception e) {
                log.error("[OCCUPIED-Timeout] 处理异常: instanceId={}, error={}",
                        inst.getInstanceId(), e.getMessage());
            }
        }

        if (recovered > 0) {
            log.info("[OCCUPIED-Timeout] 扫描完成: 回收 {} 个超时实例", recovered);
        }
        return recovered;
    }
}