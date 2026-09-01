package com.aegis.runtime.service.conversation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;

/**
 * 中断信号管理器（Redis Pub/Sub 多实例支持）。
 *
 * <p>管理每个会话的 {@link Sinks.Many} 中断信号通道，提供：
 * <ul>
 *   <li>{@link #register} - 任务执行前注册中断信号 sink</li>
 *   <li>{@link #interrupt} - 外部触发中断，向 sink 发送信号</li>
 *   <li>{@link #getInterruptFlux} - 获取中断信号 Flux，用于 {@code takeUntilOther}</li>
 *   <li>{@link #unregister} - 任务结束后清理 sink</li>
 * </ul>
 *
 * <p>多实例部署下，interrupt() 同时写本地 sink 并 publish 到 Redis channel
 * {@code aegis:interrupt:{sessionId}}；各实例通过 psubscribe 监听并转发到本地 sink。
 * 本地 sink 供 Reactor takeUntilOther 使用，Redis Pub/Sub 作为跨实例通知通道。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterruptSignalManager {

    private final UnifiedJedis jedis;

    /** Redis channel 前缀 */
    private static final String CHANNEL_PREFIX = "aegis:interrupt:";

    /** sessionId -> 注册元信息（包含 sink 和唯一注册ID） */
    private final ConcurrentHashMap<String, SessionRegistration> registrations =
            new ConcurrentHashMap<>();

    /** sessionId -> 最后活跃时间戳（ms），用于检测僵尸 sink */
    private final ConcurrentHashMap<String, Long> sinkHeartbeats =
            new ConcurrentHashMap<>();

    /**
     * 会话注册记录（每次 register 生成唯一ID，用于精确清理）。
     *
     * <p>使用 record 以获得正确的 equals/hashCode，
     * 确保 ConcurrentHashMap.remove(key, value) CAS 操作能正常工作。
     */
    private record SessionRegistration(Sinks.Many<InterruptSignal> sink, String registerId) {}

    /** Redis Pub/Sub 订阅线程池 */
    private ExecutorService subscribeExecutor;

    /** Redis Pub/Sub 订阅器 */
    private volatile JedisPubSub pubSub;

    /** 僵尸 sink 定时清理器 */
    private ScheduledExecutorService heartbeatCleaner;

    @Value("${aegis.runtime.interrupt.redis-enabled:true}")
    private boolean redisEnabled;

    /** sink 心跳超时阈值（秒），超时视为僵尸会话自动清理 */
    @Value("${aegis.runtime.interrupt.heartbeat-timeout-seconds:60}")
    private long heartbeatTimeoutSeconds;

    /** 僵尸 sink 清理间隔（秒） */
    @Value("${aegis.runtime.interrupt.cleanup-interval-seconds:15}")
    private long cleanupIntervalSeconds;

    /**
     * 启动 Redis Pub/Sub 订阅，监听跨实例中断信号。
     */
    @PostConstruct
    public void init() {
        if (!redisEnabled) {
            log.info("InterruptSignalManager Redis Pub/Sub 未启用（单实例模式）");
            return;
        }
        subscribeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "aegis-interrupt-subscriber");
            t.setDaemon(true);
            return t;
        });

        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleRedisMessage(channel, message);
            }

            @Override
            public void onPMessage(String pattern, String channel, String message) {
                handleRedisMessage(channel, message);
            }
        };

        // psubscribe 阻塞调用，需在独立线程执行
        subscribeExecutor.submit(() -> {
            try {
                // subscribe 将 * 当作字面字符，改用 psubscribe 做模式订阅
                jedis.psubscribe(pubSub, CHANNEL_PREFIX + "*");
                log.info("InterruptSignalManager Redis Pub/Sub 已模式订阅: pattern={}", CHANNEL_PREFIX + "*");
            } catch (Exception e) {
                log.error("InterruptSignalManager Redis Pub/Sub 订阅失败", e);
            }
        });
        log.info("InterruptSignalManager 已初始化（Redis Pub/Sub 多实例模式）");

        // 启动僵尸 sink 定时清理任务
        startHeartbeatCleaner();
    }

    /**
     * 启动僵尸 sink 定时清理任务。
     *
     * <p>每隔 {@link #cleanupIntervalSeconds} 秒扫描一次 sink 心跳，
     * 心跳超时（>{@link #heartbeatTimeoutSeconds} 秒未活跃）的 sink 被视为僵尸，
     * 强制清理并 complete，防止客户端断连后 sink 残留导致会话卡死。
     */
    private void startHeartbeatCleaner() {
        heartbeatCleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aegis-sink-heartbeat-cleaner");
            t.setDaemon(true);
            return t;
        });
        heartbeatCleaner.scheduleAtFixedRate(this::cleanupStaleSinks,
                cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
        log.info("僵尸 sink 定时清理已启动: interval={}s, timeout={}s",
                cleanupIntervalSeconds, heartbeatTimeoutSeconds);
    }

    /**
     * 清理所有心跳超时的僵尸 sink。
     *
     * <p>扫描 {@link #sinkHeartbeats}，对心跳超时的 sink 执行 {@link #forceUnregister}。
     * 由定时任务定期调用，也可由 {@link ChatRequestValidator} 在并发检查时主动调用。
     *
     * @return 清理的僵尸 sink 数量
     */
    public int cleanupStaleSinks() {
        if (sinkHeartbeats.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long threshold = heartbeatTimeoutSeconds * 1000;
        int cleaned = 0;

        // 先清理超时的心跳记录
        Iterator<Map.Entry<String, Long>> it = sinkHeartbeats.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Long> entry = it.next();
            String heartbeatKey = entry.getKey();
            long lastActive = entry.getValue();
            if (now - lastActive > threshold) {
                // heartbeatKey 格式: sessionId:registerId
                String sessionId = heartbeatKey.contains(":")
                        ? heartbeatKey.substring(0, heartbeatKey.indexOf(':'))
                        : heartbeatKey;
                log.warn("检测到僵尸 sink（心跳超时 {}s），强制清理: sessionId={}, lastActiveAge={}ms",
                        heartbeatTimeoutSeconds, sessionId, now - lastActive);
                forceUnregister(sessionId);
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("僵尸 sink 清理完成: cleaned={}, remaining={}", cleaned, registrations.size());
        }
        return cleaned;
    }

    /**
     * 处理 Redis Pub/Sub 收到的中断消息。
     */
    private void handleRedisMessage(String channel, String message) {
        try {
            // 从 channel 解析 sessionId: aegis:interrupt:{sessionId}
            if (!channel.startsWith(CHANNEL_PREFIX)) {
                return;
            }
            String sessionId = channel.substring(CHANNEL_PREFIX.length());

            // 解析消息
            JSONObject json = JSON.parseObject(message);
            String reason = json.getString("reason");
            long timestamp = json.getLongValue("timestamp");

            // 查找本地 sink 并发送信号
            SessionRegistration reg = registrations.get(sessionId);
            if (reg != null) {
                InterruptSignal signal = new InterruptSignal(sessionId, reason, timestamp);
                Sinks.EmitResult result = reg.sink.tryEmitNext(signal);
                log.info("P0-05: Redis 中断信号已转发到本地 sink: sessionId={}, reason={}, result={}",
                        sessionId, reason, result);
            } else {
                log.debug("P0-05: Redis 中断信号无本地 sink（非本实例会话）: sessionId={}", sessionId);
            }
        } catch (Exception e) {
            log.warn("P0-05: 处理 Redis 中断消息失败: channel={}", channel, e);
        }
    }

    /**
     * 注册会话中断信号 sink。
     *
     * <p>若检测到上一轮请求的 sink 仍存在（用户在前一轮流式输出未结束时发起新请求），
     * 主动 complete 旧 sink 以触发旧 Flux 链通过 {@code takeUntilOther} 终止，
     * 然后覆盖为新 sink。这保证新请求能正常执行，旧请求被优雅中断。
     *
     * @return 注册结果（包含 sink 和唯一注册ID），用于后续 unregister 时精确清理
     */
    public Registration register(String sessionId) {
        Sinks.Many<InterruptSignal> sink = Sinks.many().multicast().directBestEffort();
        String registerId = sessionId + ":" + System.currentTimeMillis() + ":" + Integer.toHexString(sink.hashCode());

        SessionRegistration newReg = new SessionRegistration(sink, registerId);
        SessionRegistration existing = registrations.put(sessionId, newReg);
        if (existing != null) {
            existing.sink.tryEmitComplete();
            // 清理旧注册的心跳（如果没有新请求覆盖）
            sinkHeartbeats.remove(sessionId + ":" + existing.registerId);
            log.warn("会话已有活跃中断信号 sink，已主动清理旧 sink 并覆盖: sessionId={}", sessionId);
        } else {
            log.debug("注册中断信号 sink: sessionId={}, registerId={}", sessionId, registerId);
        }
        // 初始化心跳（使用 registerId 作为 key 前缀）
        sinkHeartbeats.put(sessionId + ":" + registerId, System.currentTimeMillis());
        return new Registration(sink, registerId);
    }

    /**
     * 注册结果（包含 sink 和唯一注册ID）。
     */
    public record Registration(Sinks.Many<InterruptSignal> sink, String registerId) {}

    /**
     * 更新会话 sink 的心跳时间戳。
     *
     * <p>由 {@code TaskExecutionService} 在流式 {@code doOnNext} 中调用，
     * 表示该 sink 仍在活跃处理事件。若长时间未调用，定时清理任务会将其视为僵尸清理。
     *
     * @param sessionId 会话ID
     * @param registerId 注册ID
     */
    public void touchHeartbeat(String sessionId, String registerId) {
        sinkHeartbeats.put(sessionId + ":" + registerId, System.currentTimeMillis());
    }

    /**
     * 获取 sink 心跳年龄（距上次活跃的毫秒数）。
     *
     * <p>由 {@code ChatRequestValidator} 在并发检查时调用，
     * 若心跳年龄超过阈值则判定为僵尸会话并放行新请求。
     *
     * @param sessionId 会话ID
     * @return 心跳年龄（ms），不存在时返回 -1
     */
    public long getHeartbeatAgeMs(String sessionId) {
        SessionRegistration reg = registrations.get(sessionId);
        if (reg == null) {
            return -1;
        }
        Long lastActive = sinkHeartbeats.get(sessionId + ":" + reg.registerId);
        if (lastActive == null) {
            return -1;
        }
        return System.currentTimeMillis() - lastActive;
    }

    /**
     * 触发会话中断（同时通知本地 sink 和 Redis channel）。
     *
     * @param sessionId 会话ID
     * @param reason    中断原因
     * @return true=信号已发送，false=会话不存在或已完成
     */
    public boolean interrupt(String sessionId, String reason) {
        InterruptSignal signal = new InterruptSignal(sessionId, reason, System.currentTimeMillis());

        // 1. 本地 sink 通知（本实例正在运行的会话）
        SessionRegistration reg = registrations.get(sessionId);
        boolean localSent = false;
        if (reg != null) {
            Sinks.EmitResult result = reg.sink.tryEmitNext(signal);
            localSent = result.isSuccess();
            log.info("中断信号本地发送: sessionId={}, reason={}, result={}", sessionId, reason, result);
        }

        // 2. Redis Pub/Sub 通知（跨实例）
        boolean redisSent = false;
        if (redisEnabled) {
            try {
                String channel = CHANNEL_PREFIX + sessionId;
                JSONObject msg = new JSONObject();
                msg.put("sessionId", sessionId);
                msg.put("reason", reason);
                msg.put("timestamp", signal.timestamp());
                jedis.publish(channel, msg.toJSONString());
                redisSent = true;
            } catch (Exception e) {
                log.warn("P0-05: Redis 中断信号发布失败: sessionId={}", sessionId, e);
            }
        }

        if (!localSent && !redisSent) {
            log.warn("中断信号发送失败：会话不存在或已完成: sessionId={}", sessionId);
            return false;
        }
        return true;
    }

    /**
     * 注销会话中断信号 sink（CAS 语义）。
     *
     * <p>使用 {@link ConcurrentHashMap#remove(Object, Object)} 条件移除，
     * 仅当 map 中当前注册与 {@code expectedRegistration} 匹配时才移除。
     * 这避免旧 Flux 链的 {@code doFinally} 在新 sink 已注册后误删新 sink。
     *
     * <p>即使 CAS 校验失败（有新 sink 注册），也会清理当前注册的心跳，
     * 防止心跳残留导致僵尸会话无法清理。
     *
     * @param sessionId 会话ID
     * @param expectedRegistration register 时返回的注册结果
     */
    public void unregister(String sessionId, Registration expectedRegistration) {
        if (expectedRegistration == null) {
            return;
        }
        // 先清理当前注册的心跳（无论 CAS 是否成功，都要清理）
        sinkHeartbeats.remove(sessionId + ":" + expectedRegistration.registerId);

        // 再尝试 CAS 移除注册信息
        SessionRegistration expectedReg = new SessionRegistration(
                expectedRegistration.sink(), expectedRegistration.registerId());
        boolean removed = registrations.remove(sessionId, expectedReg);
        if (removed) {
            expectedRegistration.sink().tryEmitComplete();
            log.debug("注销中断信号 sink: sessionId={}, registerId={}", sessionId, expectedRegistration.registerId());
        } else {
            log.debug("CAS 校验失败（可能有新 sink 注册），仅清理心跳: sessionId={}, registerId={}",
                    sessionId, expectedRegistration.registerId());
        }
    }

    /**
     * 检查会话是否正在运行（有活跃的 sink）。
     */
    public boolean isRunning(String sessionId) {
        return registrations.containsKey(sessionId);
    }

    /**
     * 强制清理会话残留 sink（无 CAS 校验）。
     *
     * <p>用于 HITL 挂起后会话状态已变为 PAUSED/ENDED 但 sink 未被 doFinally 正确清理的场景。
     * 此时新请求需要清理旧 sink 后才能重新注册，否则 isRunning 永远返回 true 导致会话卡死。
     *
     * @param sessionId 会话ID
     */
    public void forceUnregister(String sessionId) {
        SessionRegistration reg = registrations.remove(sessionId);
        if (reg != null) {
            sinkHeartbeats.remove(sessionId + ":" + reg.registerId);
            reg.sink.tryEmitComplete();
            log.warn("强制清理残留 sink: sessionId={}, registerId={}", sessionId, reg.registerId);
        }
    }

    /**
     * 优雅关闭 Redis Pub/Sub 订阅。
     */
    @PreDestroy
    public void destroy() {
        if (pubSub != null && pubSub.isSubscribed()) {
            try {
                pubSub.unsubscribe();
            } catch (Exception e) {
                log.warn("关闭 Redis Pub/Sub 订阅失败", e);
            }
        }
        if (subscribeExecutor != null) {
            subscribeExecutor.shutdownNow();
        }
        if (heartbeatCleaner != null) {
            heartbeatCleaner.shutdownNow();
        }
        log.info("InterruptSignalManager 已关闭");
    }

    /**
     * 中断信号记录。
     */
    public record InterruptSignal(String sessionId, String reason, long timestamp) {}
}
