package com.aegis.runtime.integration.agent;

import com.aegis.core.dto.agent.AgentEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具执行上下文缓存。
 *
 * <p>AgentScope 的 {@code ToolResultEndEvent} 仅携带 toolCallId 和 state（SUCCESS/ERROR），
 * 不包含工具实际输出内容、入参或开始时间。本缓存桥接这一缺口：
 *
 * <ol>
 *   <li>{@code AegisToolBridge.BuiltinToolAdapter.callAsync} 执行工具前，存入入参与开始时间</li>
 *   <li>{@code AegisToolBridge.BuiltinToolAdapter.callAsync} 执行工具后，存入结果</li>
 *   <li>{@code HarnessEventConverter.convertToolResultEnd} 从缓存取出全部信息，注入 tool_result 事件</li>
 *   <li>读取后自动移除，避免内存泄漏</li>
 * </ol>
 *
 * <p>缓存生命周期：工具执行 → 存入 → 事件转换时取出并移除。
 * 若事件转换未发生（异常路径），条目将残留，由 {@link #cleanupStale()} 定期清理。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class ToolResultCache {

    /** 缓存条目：toolCallId → 工具执行上下文 */
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    /** 条目默认 TTL：5 分钟 */
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000;

    /**
     * 存入工具调用入参与开始时间（在工具执行前调用）。
     *
     * @param toolCallId 工具调用 ID
     * @param arguments  工具入参 JSON 字符串
     * @param startTime  工具开始执行时间戳（毫秒）
     */
    public void putCallMeta(String toolCallId, String arguments, long startTime) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            log.debug("ToolResultCache.putCallMeta: toolCallId 为空，跳过缓存");
            return;
        }
        cache.compute(toolCallId, (k, existing) -> {
            long expireAt = System.currentTimeMillis() + DEFAULT_TTL_MS;
            if (existing == null) {
                return new Entry(arguments, null, startTime, expireAt);
            }
            existing.arguments = arguments;
            existing.startTime = startTime;
            return existing;
        });
        log.debug("ToolResultCache.putCallMeta: toolCallId={}, argLen={}",
                toolCallId, arguments != null ? arguments.length() : 0);
    }

    /**
     * 存入工具执行结果（在工具执行完成后调用）。
     *
     * @param toolCallId 工具调用 ID
     * @param result     工具执行结果（JSON 字符串）
     * @param events     附加事件列表（如 skill_creator 编排阶段事件，可为 null）
     */
    public void put(String toolCallId, String result, List<AgentEvent> events) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            log.debug("ToolResultCache.put: toolCallId 为空，跳过缓存");
            return;
        }
        // P1 AGT-06 修复：容量上限检查，超过 1000 条先清理过期条目，防止内存泄漏
        if (cache.size() > 1000) {
            cleanupStale();
        }
        cache.compute(toolCallId, (k, existing) -> {
            long expireAt = System.currentTimeMillis() + DEFAULT_TTL_MS;
            if (existing == null) {
                return new Entry(null, result, 0L, expireAt, events);
            }
            existing.result = result;
            existing.events = events;
            return existing;
        });
        log.debug("ToolResultCache.put: toolCallId={}, resultLen={}, events={}",
                toolCallId, result != null ? result.length() : 0,
                events != null ? events.size() : 0);
    }

    /**
     * 存入工具执行结果（在工具执行完成后调用）。
     *
     * @param toolCallId 工具调用 ID
     * @param result     工具执行结果（JSON 字符串）
     */
    public void put(String toolCallId, String result) {
        put(toolCallId, result, null);
    }

    /**
     * 仅查看缓存条目（不取出，不删除）。
     *
     * <p>用于 HarnessEventConverter.convertMany 在主事件发射前预览 events，
     * 以便在 convertToolResultEnd 取出并删除之前把 events 同步读出。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具执行上下文（只读引用），未命中返回 null
     */
    public Entry peek(String toolCallId) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            return null;
        }
        Entry entry = cache.get(toolCallId);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            return null;
        }
        return entry;
    }

    /**
     * 取出并移除工具执行上下文（含 result、arguments、startTime）。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具执行上下文，未命中返回 null
     */
    public Entry getAndRemove(String toolCallId) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            return null;
        }
        Entry entry = cache.remove(toolCallId);
        if (entry == null) {
            log.debug("ToolResultCache.getAndRemove: 未命中 toolCallId={}", toolCallId);
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            log.debug("ToolResultCache.getAndRemove: 已过期 toolCallId={}", toolCallId);
            return null;
        }
        return entry;
    }

    /**
     * 兼容旧接口：仅取出结果字符串。
     *
     * @deprecated 请使用 {@link #getAndRemove(String)} 获取完整上下文
     */
    @Deprecated
    public String getAndRemoveResult(String toolCallId) {
        Entry entry = getAndRemove(toolCallId);
        return entry != null ? entry.result : null;
    }

    /**
     * 清理过期条目（可由定时任务调用）。
     *
     * <p>P1 AGT-06 修复：添加 @Scheduled 注解，由 Spring 定时任务每 60 秒自动触发清理。
     *
     * @return 清理的条目数
     */
    // P1 AGT-06 修复：定时清理过期条目，每 60 秒执行一次
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 60000)
    public int cleanupStale() {
        long now = System.currentTimeMillis();
        int before = cache.size();
        cache.entrySet().removeIf(e -> now > e.getValue().expireAt);
        int cleaned = before - cache.size();
        if (cleaned > 0) {
            log.debug("ToolResultCache.cleanupStale: 清理 {} 条过期条目", cleaned);
        }
        return cleaned;
    }

    /** 缓存条目：工具执行上下文 */
    public static class Entry {
        /** 工具入参 JSON 字符串 */
        public String arguments;
        /** 工具执行结果 JSON 字符串 */
        public String result;
        /** 工具开始执行时间戳（毫秒） */
        public long startTime;
        /** 过期时间戳（毫秒） */
        final long expireAt;
        /** 编排阶段附加事件列表（如 skill_creator 阶段事件） */
        public List<AgentEvent> events;

        Entry(String arguments, String result, long startTime, long expireAt) {
            this(arguments, result, startTime, expireAt, null);
        }

        Entry(String arguments, String result, long startTime, long expireAt, List<AgentEvent> events) {
            this.arguments = arguments;
            this.result = result;
            this.startTime = startTime;
            this.expireAt = expireAt;
            this.events = events;
        }
    }
}
