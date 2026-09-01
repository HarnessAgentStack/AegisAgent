package com.aegis.runtime.integration.observe;

import com.aegis.core.dto.observe.SpanRecord;
import com.aegis.core.dto.observe.TraceRecord;
import com.aegis.core.dto.observe.ObserveProperties;
import com.aegis.core.spi.TraceStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链路追踪记录器。
 *
 * <p>将智能体执行过程中的 Trace 和 Span 数据异步批量写入 {@link TraceStore}。
 * 使用 {@link ConcurrentHashMap} 替代 ThreadLocal 以支持 Reactor 响应式线程切换。
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>Trace：在 {@link #startTrace} 时只记录元数据到内存上下文，在 {@link #endTrace} 时
 *       构建完整 TraceRecord 并一次性写入（避免 trace_id 唯一约束冲突）</li>
 *   <li>Span：增量写入，通过 ConcurrentLinkedQueue 缓冲后批量 flush</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <ol>
 *   <li>{@link #startTrace} - 执行开始时调用，初始化链路上下文</li>
 *   <li>{@link #recordSpan} / {@link #recordToolCall} - 关键节点创建 Span</li>
 *   <li>{@link #endTrace} - 执行结束时调用，构建完整 Trace 并触发 flush</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceSpanRecorder {

    private final TraceStore traceStore;
    private final ObserveProperties properties;

    /** 待写入的 Span 队列（Trace 在 endTrace 时一次性写入） */
    private final Queue<SpanRecord> spanQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);

    /** 基于 traceId 的链路上下文存储（支持 Reactor 线程切换） */
    private final ConcurrentHashMap<String, TraceContext> traceContextMap = new ConcurrentHashMap<>();

    /** 每个 traceId 的 Span 计数器（避免异步 flush 竞态导致 spanCount 为 0） */
    private final ConcurrentHashMap<String, AtomicInteger> traceSpanCountMap = new ConcurrentHashMap<>();

    /**
     * 开始一条链路追踪（仅记录元数据到内存，不写库）。
     *
     * @param traceId   链路唯一ID
     * @param sessionId 会话ID
     * @param agentId   智能体ID
     * @param agentName 智能体名称
     * @param userId    用户ID
     * @param userName  用户名
     * @param tenantId  租户ID
     * @param apiPath   API路径
     */
    public void startTrace(String traceId, String sessionId, Long agentId, String agentName,
                          Long userId, String userName, Long tenantId, String apiPath) {
        // 幂等：retry 场景下同一个 traceId 可能被多次 startTrace，只保留第一次的上下文
        if (traceContextMap.containsKey(traceId)) {
            log.debug("Trace already started, skipping: traceId={}", traceId);
            return;
        }
        TraceContext ctx = new TraceContext(traceId, sessionId, agentId, agentName,
                userId, userName, tenantId, apiPath, LocalDateTime.now());
        traceContextMap.put(traceId, ctx);
        traceSpanCountMap.put(traceId, new AtomicInteger(0));
        log.debug("Trace started: traceId={}, agentId={}, sessionId={}", traceId, agentId, sessionId);
    }

    /**
     * 更新链路上下文（在 assemble 完成后回填 sessionId、agentName、userName 等）。
     */
    public void updateTraceContext(String traceId, String sessionId, String agentName, String userName) {
        TraceContext ctx = traceContextMap.get(traceId);
        if (ctx != null) {
            TraceContext updated = new TraceContext(
                    ctx.traceId,
                    sessionId != null ? sessionId : ctx.sessionId,
                    ctx.agentId,
                    agentName != null ? agentName : ctx.agentName,
                    ctx.userId,
                    userName != null ? userName : ctx.userName,
                    ctx.tenantId,
                    ctx.apiPath,
                    ctx.startTime);
            traceContextMap.put(traceId, updated);
            log.debug("Trace context updated: traceId={}, sessionId={}, agentName={}",
                    traceId, sessionId, agentName);
        } else {
            log.warn("updateTraceContext: traceId={} not found", traceId);
        }
    }

    /**
     * 记录一个 Span（使用预构建的 SpanRecord）。
     */
    public void recordSpan(SpanRecord span) {
        if (span.getTraceId() != null
                && (span.getSessionId() == null || span.getAgentId() == null || span.getUserId() == null)) {
            TraceContext ctx = traceContextMap.get(span.getTraceId());
            if (ctx != null) {
                span.setSessionId(ctx.sessionId);
                span.setAgentId(ctx.agentId);
                span.setUserId(ctx.userId);
            }
        }
        spanQueue.offer(span);
        if (span.getTraceId() != null) {
            traceSpanCountMap.computeIfAbsent(span.getTraceId(), k -> new AtomicInteger(0))
                    .incrementAndGet();
        }
        checkFlush();
    }

    /**
     * 记录一个 Span（便捷方法，不含费用）。
     */
    public void recordSpan(String traceId, String spanType, String name, String status,
                           LocalDateTime startTime, LocalDateTime endTime,
                           Long durationMs, String inputSummary, String outputSummary,
                           Integer tokenInput, Integer tokenOutput,
                           String errorMsg, String meta) {
        TraceContext ctx = traceContextMap.get(traceId);

        SpanRecord.SpanRecordBuilder builder = SpanRecord.builder()
                .traceId(traceId)
                .spanId(UUID.randomUUID().toString().replace("-", "").substring(0, 32))
                .spanType(spanType)
                .name(name)
                .status(status)
                .startTime(startTime)
                .endTime(endTime)
                .durationMs(durationMs)
                .inputSummary(truncate(inputSummary, 1024))
                .outputSummary(truncate(outputSummary, 1024))
                .tokenInput(tokenInput)
                .tokenOutput(tokenOutput)
                .errorMsg(truncate(errorMsg, 512))
                .meta(meta);

        if (ctx != null) {
            builder.sessionId(ctx.sessionId)
                    .agentId(ctx.agentId)
                    .userId(ctx.userId);
        }

        SpanRecord span = builder.build();
        spanQueue.offer(span);
        if (traceId != null) {
            traceSpanCountMap.computeIfAbsent(traceId, k -> new AtomicInteger(0))
                    .incrementAndGet();
        }
        checkFlush();
    }

    /**
     * 记录工具调用 Span。
     */
    public void recordToolCall(String traceId, String toolName, String toolType, String status,
                               LocalDateTime startTime, Long durationMs,
                               String inputArgs, String output, String errorMsg) {
        recordSpan(traceId, "TOOL_CALL", toolName, status,
                startTime, LocalDateTime.now(), durationMs,
                inputArgs, output, null, null, errorMsg,
                String.format("{\"toolType\":\"%s\"}", toolType));
    }

    /**
     * 记录工具调用 Span（含自定义 meta）。
     */
    public void recordToolCall(String traceId, String toolName, String toolType, String status,
                               LocalDateTime startTime, Long durationMs,
                               String inputArgs, String output, String errorMsg, String meta) {
        recordSpan(traceId, "TOOL_CALL", toolName, status,
                startTime, LocalDateTime.now(), durationMs,
                inputArgs, output, null, null, errorMsg,
                meta != null ? meta : String.format("{\"toolType\":\"%s\"}", toolType));
    }

    /**
     * 结束链路追踪，构建完整 TraceRecord 并写入数据库。
     */
    public void endTrace(String traceId, String status, String errorMsg, Integer tokenInput,
                         Integer tokenOutput, int sseEventCount) {
        TraceContext ctx = traceContextMap.remove(traceId);
        if (ctx == null) {
            log.warn("endTrace: traceId={} not found in context map, skipping", traceId);
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int durationMs = (int) Duration.between(ctx.startTime, now).toMillis();

        AtomicInteger counter = traceSpanCountMap.remove(traceId);
        int spanCount = counter != null ? counter.get() : 0;

        TraceRecord trace = TraceRecord.builder()
                .traceId(traceId)
                .sessionId(ctx.sessionId)
                .agentId(ctx.agentId)
                .agentName(ctx.agentName)
                .userId(ctx.userId)
                .userName(ctx.userName)
                .tenantId(ctx.tenantId)
                .apiPath(ctx.apiPath)
                .status(status)
                .startTime(ctx.startTime)
                .endTime(now)
                .durationMs((long) durationMs)
                .tokenInput(tokenInput)
                .tokenOutput(tokenOutput)
                .errorMsg(truncate(errorMsg, 512))
                .spanCount(spanCount)
                .sseEventCount(sseEventCount)
                .build();

        try {
            traceStore.saveBatch(List.of(trace), Collections.emptyList());
            log.debug("Trace saved: traceId={}, status={}, durationMs={}, spans={}, events={}",
                    traceId, status, durationMs, spanCount, sseEventCount);
        } catch (Exception e) {
            log.error("Failed to save trace: traceId={}", traceId, e);
        }

        doFlush();
    }

    @Async
    public void flushAsync() {
        doFlush();
    }

    @Scheduled(fixedDelayString = "${aegis.observe.batch.flush-seconds:5}000")
    public void scheduledFlush() {
        doFlush();
    }

    private void checkFlush() {
        int batchSize = properties.getBatch().getSize();
        if (spanQueue.size() >= batchSize) {
            flushAsync();
        }
    }

    private synchronized void doFlush() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            List<SpanRecord> spans = new ArrayList<>();
            SpanRecord span;
            while ((span = spanQueue.poll()) != null) {
                spans.add(span);
            }

            if (!spans.isEmpty()) {
                traceStore.saveBatch(Collections.emptyList(), spans);
                log.debug("Flushed {} spans to {}", spans.size(), traceStore.storeType());
            }
        } catch (Exception e) {
            log.error("Failed to flush span data", e);
        } finally {
            flushInProgress.set(false);
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * 链路上下文（存储在内存中，endTrace 时一次性写入）。
     */
    private static class TraceContext {
        final String traceId;
        final String sessionId;
        final Long agentId;
        final String agentName;
        final Long userId;
        final String userName;
        final Long tenantId;
        final String apiPath;
        final LocalDateTime startTime;

        TraceContext(String traceId, String sessionId, Long agentId, String agentName,
                    Long userId, String userName, Long tenantId, String apiPath,
                    LocalDateTime startTime) {
            this.traceId = traceId;
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.agentName = agentName;
            this.userId = userId;
            this.userName = userName;
            this.tenantId = tenantId;
            this.apiPath = apiPath;
            this.startTime = startTime;
        }
    }
}
