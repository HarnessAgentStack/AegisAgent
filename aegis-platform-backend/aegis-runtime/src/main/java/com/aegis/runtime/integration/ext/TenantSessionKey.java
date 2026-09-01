package com.aegis.runtime.integration.ext;

import java.util.Objects;

/**
 * 多租户会话键（5 维分桶）。
 *
 * <p>AS 2.0.2 移除了 {@code SessionKey} 接口，{@code AgentStateStore} 直接使用
 * {@code (userId, sessionId)} 字符串对。本类保留为 Aegis 内部多租户上下文传递的值对象，
 * 用于在 {@code TaskExecutionService} 与 {@code AegisAgentInstanceManager} 之间传递
 * 租户/用户/智能体类型/智能体ID/会话ID 五维信息。
 *
 * <p>标识符格式：{@code {tenantId:userId:agentType:agentId:sessionId}}
 * <br>外层花括号用于保持向后兼容的标识符格式。
 *
 * @author wang.zhen
 */
public final class TenantSessionKey {

    private final long tenantId;
    private final long userId;
    private final String agentType;
    private final long agentId;
    private final String sessionId;

    public TenantSessionKey(long tenantId, long userId, String agentType, long agentId, String sessionId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
        this.agentId = agentId;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    public static TenantSessionKey of(long tenantId, long userId, String agentType, long agentId, String sessionId) {
        return new TenantSessionKey(tenantId, userId, agentType, agentId, sessionId);
    }

    public String toIdentifier() {
        return "{" + tenantId + ":" + userId + ":" + agentType + ":" + agentId + ":" + sessionId + "}";
    }

    public long getTenantId() {
        return tenantId;
    }

    public long getUserId() {
        return userId;
    }

    public String getAgentType() {
        return agentType;
    }

    public long getAgentId() {
        return agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TenantSessionKey that)) return false;
        return tenantId == that.tenantId
                && userId == that.userId
                && agentId == that.agentId
                && agentType.equals(that.agentType)
                && sessionId.equals(that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, userId, agentType, agentId, sessionId);
    }

    @Override
    public String toString() {
        return "TenantSessionKey{" + toIdentifier() + "}";
    }
}

