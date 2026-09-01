package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import io.agentscope.harness.agent.IsolationScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 空闲释放追踪器（T1 沙箱惰性分配 Phase 3）。
 *
 * <p>记录每个会话末次沙箱工具调用时间，周期扫描超过 {@code idleReleaseMinutes}
 * （默认 5min）的会话，主动 {@code saveSnapshot + releaseSlot} 回池，
 * 将"还坑"时机从 30min 租约兜底提前到 N 分钟，提升并发密度（§4.5）。
 *
 * <h3>触发链</h3>
 * <ul>
 *   <li>{@code touch}：沙箱类工具成功执行后调用，刷新 lastUsedAt</li>
 *   <li>{@code scanAndRelease}：周期扫描，超阈值会话主动释放</li>
 *   <li>释放失败：日志告警，租约 30min 兜底仍会释放（§4.5.2 双重保险）</li>
 * </ul>
 *
 * <p>线程安全：{@code ConcurrentHashMap} + 不可变 {@link IdleEntry}。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class IdleReleaseTracker {

    private final AegisSandboxCoordinator coordinator;
    private final SandboxReadinessGate readinessGate;

    @Value("${aegis.runtime.sandbox.idle-release-minutes:5}")
    private long idleReleaseMinutes;

    /** 会话级空闲追踪表：sessionId → IdleEntry */
    private final ConcurrentHashMap<String, IdleEntry> entries = new ConcurrentHashMap<>();

    public IdleReleaseTracker(AegisSandboxCoordinator coordinator, SandboxReadinessGate readinessGate) {
        this.coordinator = coordinator;
        this.readinessGate = readinessGate;
    }

    /**
     * 刷新会话末次沙箱工具调用时间。
     *
     * <p>由 {@code AegisExecuteTool} 等沙箱类工具在成功执行后调用。
     */
    public void touch(String sessionId, String slotKey, String instanceId,
                      Long tenantId, IsolationScope scope) {
        if (sessionId == null || instanceId == null) {
            return;
        }
        entries.put(sessionId, new IdleEntry(slotKey, instanceId, tenantId, scope, System.currentTimeMillis()));
        log.debug("IdleReleaseTracker touch: sessionId={}, instanceId={}, lastUsedAt={}",
                sessionId, instanceId, System.currentTimeMillis());
    }

    /**
     * 周期扫描并释放空闲超阈值会话。
     *
     * <p>由 {@code AegisAgentInstanceManager.cleaner} 调度（复用 clean-interval-minutes 周期）。
     * 释放动作：{@code saveSnapshot(GLOBAL 跳过) → releaseSlot → clear sessionBindings}。
     */
    public void scanAndRelease() {
        if (entries.isEmpty()) {
            return;
        }
        long threshold = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(idleReleaseMinutes);
        entries.forEach((sid, e) -> {
            if (e.lastUsedAt >= threshold) {
                return;
            }
            releaseIdleSession(sid, e);
        });
    }

    private void releaseIdleSession(String sessionId, IdleEntry e) {
        try {
            boolean saveSnapshot = e.scope != IsolationScope.GLOBAL;
            if (saveSnapshot) {
                try {
                    coordinator.saveSnapshot(e.tenantId, e.instanceId);
                    log.info("IdleReleaseTracker 空闲释放保存快照: sessionId={}, instanceId={}",
                            sessionId, e.instanceId);
                } catch (Exception se) {
                    log.warn("IdleReleaseTracker saveSnapshot 失败(继续释放): sessionId={}, err={}",
                            sessionId, se.getMessage());
                }
            }
            coordinator.releaseSlot(e.tenantId, e.instanceId, saveSnapshot);
            log.info("IdleReleaseTracker 空闲释放成功: sessionId={}, instanceId={}, idleMinutes>={}",
                    sessionId, e.instanceId, idleReleaseMinutes);
        } catch (Exception ex) {
            log.warn("IdleReleaseTracker 空闲释放失败(租约兜底): sessionId={}, instanceId={}, err={}",
                    sessionId, e.instanceId, ex.getMessage());
        } finally {
            entries.remove(sessionId);
            if (readinessGate != null) {
                readinessGate.clear(sessionId);
            }
        }
    }

    /**
     * 会话结束时主动移除追踪（避免 stale entry）。
     *
     * <p>由 {@code closeAgent} 在清理 sessionBindings 时可选调用。
     */
    public void remove(String sessionId) {
        if (sessionId != null) {
            entries.remove(sessionId);
        }
    }

    /** 追踪条目（不可变） */
    private record IdleEntry(String slotKey, String instanceId, Long tenantId,
                              IsolationScope scope, long lastUsedAt) {}
}
