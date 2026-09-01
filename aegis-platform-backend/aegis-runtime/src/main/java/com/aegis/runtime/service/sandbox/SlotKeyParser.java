package com.aegis.runtime.service.sandbox;

import com.aegis.runtime.service.sandbox.model.SlotKeyParts;
import io.agentscope.harness.agent.IsolationScope;
import lombok.extern.slf4j.Slf4j;

/**
 * slotKey 解析器。
 *
 * <p>按 IsolationScope 合成与解析沙箱槽位键，决定同一 slot 的并发串行化范围。
 * 与 {@link com.aegis.core.domain.sandbox.IsolationContext#computeSlotKey()} 保持一致的格式。
 *
 * <h3>slotKey 命名规范</h3>
 * <pre>
 * USER    → "aegis:{tenantId}:user:{userId}"
 * AGENT   → "aegis:{tenantId}:agent:{agentId}"
 * GLOBAL  → "aegis:{tenantId}:global"
 * SESSION → 禁用（fail-fast 抛 IllegalStateException）
 * </pre>
 *
 * <h3>A3 扩展：RESIDENT 常驻槽位</h3>
 * <pre>
 * RESIDENT → "aegis:resident:sys:{agentId}"（系统智能体专属，一对一常驻绑定）
 * </pre>
 * SYSTEM 智能体在 AgentScope 层仍以 {@code IsolationScope.GLOBAL} 构建（保持框架语义），
 * 但 slotKey 注入 RESIDENT 专用格式，使每个系统智能体绑定一个专属实例。
 *
 * @author wang.zhen
 */
@Slf4j
public final class SlotKeyParser {

    /** RESIDENT slotKey 前缀 */
    public static final String RESIDENT_PREFIX = "aegis:resident:sys:";

    private SlotKeyParser() {
    }

    /**
     * 合成 slotKey。
     *
     * <p>格式与 {@link com.aegis.core.domain.sandbox.IsolationContext#computeSlotKey()} 保持一致。
     *
     * @param scope    隔离作用域（SESSION 禁用）
     * @param tenantId 租户 ID
     * @param userId   用户 ID（USER scope 必填）
     * @param agentId  智能体 ID（AGENT scope 必填）
     * @return slotKey 字符串
     * @throws IllegalStateException SESSION scope 禁用于沙箱
     */
    public static String build(IsolationScope scope, Long tenantId, Long userId, Long agentId) {
        String tenantPart = tenantId != null ? String.valueOf(tenantId) : "default";
        return switch (scope) {
            case USER -> "aegis:" + tenantPart + ":user:" + userId;
            case AGENT -> "aegis:" + tenantPart + ":agent:" + agentId;
            case GLOBAL -> "aegis:" + tenantPart + ":global";
            case SESSION -> throw new IllegalStateException("SESSION scope 禁用于沙箱，fail-fast");
        };
    }

    /**
     * A3：合成系统智能体常驻绑定 slotKey。
     *
     * <p>格式：{@code aegis:resident:sys:{agentId}}。一个系统智能体对应一个
     * RESIDENT 实例（状态机 {@code RESIDENT}），不参与动态分配与回收。
     *
     * @param agentId 系统智能体 ID（必填）
     * @return RESIDENT slotKey 字符串
     */
    public static String buildResident(Long agentId) {
        if (agentId == null) {
            throw new IllegalArgumentException("RESIDENT slotKey 构建 requires agentId（系统智能体 ID）");
        }
        return RESIDENT_PREFIX + agentId;
    }

    /**
     * A3：判断 slotKey 是否为 RESIDENT 常驻格式。
     *
     * @param slotKey 槽位键（可为 null）
     * @return true 表示 RESIDENT 常驻槽位
     */
    public static boolean isResidentSlot(String slotKey) {
        return slotKey != null && slotKey.startsWith(RESIDENT_PREFIX);
    }

    /**
     * 解析 slotKey。
     *
     * @param slotKey 槽位键
     * @param scope   隔离作用域
     * @return 解析结果
     * @throws IllegalArgumentException slotKey 格式不匹配 scope
     */
    public static SlotKeyParts parse(String slotKey, IsolationScope scope) {
        if (slotKey == null || slotKey.isBlank()) {
            throw new IllegalArgumentException("slotKey 不能为空");
        }

        return switch (scope) {
            case USER -> parseUserSlot(slotKey, scope);
            case AGENT -> parseAgentSlot(slotKey, scope);
            case GLOBAL -> parseGlobalSlot(slotKey, scope);
            case SESSION -> throw new IllegalStateException("SESSION scope 禁用于沙箱，fail-fast");
        };
    }

    private static SlotKeyParts parseUserSlot(String slotKey, IsolationScope scope) {
        // 格式: aegis:{tenantId}:user:{userId}
        String[] parts = slotKey.split(":");
        if (parts.length != 4 || !"aegis".equals(parts[0]) || !"user".equals(parts[2])) {
            throw new IllegalArgumentException("USER scope slotKey 格式错误，期望 aegis:{tenantId}:user:{userId}, 实际: " + slotKey);
        }
        try {
            Long tenantId = Long.parseLong(parts[1]);
            Long userId = Long.parseLong(parts[3]);
            return new SlotKeyParts(tenantId, userId, null, scope, slotKey);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("USER scope slotKey 数字解析失败: " + slotKey, e);
        }
    }

    private static SlotKeyParts parseAgentSlot(String slotKey, IsolationScope scope) {
        // 格式: aegis:{tenantId}:agent:{agentId}
        String[] parts = slotKey.split(":");
        if (parts.length != 4 || !"aegis".equals(parts[0]) || !"agent".equals(parts[2])) {
            throw new IllegalArgumentException("AGENT scope slotKey 格式错误，期望 aegis:{tenantId}:agent:{agentId}, 实际: " + slotKey);
        }
        try {
            Long tenantId = Long.parseLong(parts[1]);
            Long agentId = Long.parseLong(parts[3]);
            return new SlotKeyParts(tenantId, null, agentId, scope, slotKey);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("AGENT scope slotKey 数字解析失败: " + slotKey, e);
        }
    }

    private static SlotKeyParts parseGlobalSlot(String slotKey, IsolationScope scope) {
        // 格式: aegis:{tenantId}:global 或 aegis:default:global
        String[] parts = slotKey.split(":");
        if (parts.length != 3 || !"aegis".equals(parts[0]) || !"global".equals(parts[2])) {
            throw new IllegalArgumentException("GLOBAL scope slotKey 格式错误，期望 aegis:{tenantId}:global, 实际: " + slotKey);
        }
        Long tenantId;
        try {
            tenantId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            tenantId = 0L; // default
        }
        return new SlotKeyParts(tenantId, null, null, scope, slotKey);
    }
}
