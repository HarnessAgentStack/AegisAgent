package com.aegis.core.domain.sandbox;

import com.aegis.core.enums.sandbox.IsolationScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 沙箱隔离上下文值对象。
 *
 * <p>封装沙箱分配所需的隔离信息，用于决定沙箱实例的复用粒度。
 * 隔离上下文是沙箱资源池多租户隔离的核心，确保不同租户/用户/Agent
 * 的沙箱环境互不干扰。</p>
 *
 * <h3>隔离作用域与复用粒度</h3>
 * <ul>
 *   <li>{@link IsolationScope#SESSION} — 每次会话独占沙箱，会话结束后回收（不复用）</li>
 *   <li>{@link IsolationScope#USER} — 同一用户的会话可复用沙箱</li>
 *   <li>{@link IsolationScope#AGENT} — 同一 Agent 的会话可复用沙箱</li>
 *   <li>{@link IsolationScope#GLOBAL} — 全局共享，所有会话复用同一沙箱（特殊场景）</li>
 * </ul>
 *
 * <h3>slotKey 合成规则</h3>
 * <pre>
 * SESSION: aegis:{tenantId}:session:{sessionId}
 * USER:    aegis:{tenantId}:user:{userId}
 * AGENT:   aegis:{tenantId}:agent:{agentId}
 * GLOBAL:  aegis:{tenantId}:global
 * </pre>
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsolationContext {

    /** 租户 ID */
    private Long tenantId;

    /** 用户 ID */
    private Long userId;

    /** Agent ID */
    private Long agentId;

    /** 会话 ID */
    private String sessionId;

    /** 隔离作用域 */
    private IsolationScope isolationScope;

    /**
     * 计算槽位键（slotKey）。
     *
     * <p>根据隔离作用域合成唯一键，决定沙箱实例的复用映射关系。</p>
     *
     * @return 槽位键
     * @throws IllegalStateException 如果必要字段缺失
     */
    public String computeSlotKey() {
        StringBuilder sb = new StringBuilder("aegis:")
                .append(tenantId != null ? tenantId : "default")
                .append(":");

        switch (isolationScope != null ? isolationScope : IsolationScope.SESSION) {
            case SESSION:
                if (sessionId == null || sessionId.isEmpty()) {
                    throw new IllegalStateException("SESSION scope requires sessionId");
                }
                sb.append("session:").append(sessionId);
                break;
            case USER:
                if (userId == null) {
                    throw new IllegalStateException("USER scope requires userId");
                }
                sb.append("user:").append(userId);
                break;
            case AGENT:
                if (agentId == null) {
                    throw new IllegalStateException("AGENT scope requires agentId");
                }
                sb.append("agent:").append(agentId);
                break;
            case GLOBAL:
                sb.append("global");
                break;
            default:
                sb.append("session:").append(sessionId != null ? sessionId : "unknown");
        }
        return sb.toString();
    }

    /**
     * 计算执行互斥键（用于 SandboxExecutionGuard）。
     *
     * <p>当前实现与 slotKey 相同，后续可根据业务需要调整粒度。</p>
     *
     * @return 执行互斥键
     */
    public String computeExecutionKey() {
        return computeSlotKey();
    }
}
