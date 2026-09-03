package com.aegis.runtime.integration.sandbox;

/**
 * 扩展的 Aegis 沙箱客户端选项（承载运行时上下文，供 create 分配时用）。
 *
 * <p>框架 {@code SandboxClient.create(WorkspaceSpec, SandboxSnapshotSpec, O options)} 签名
 * 不含 RuntimeContext，但 {@link AegisSandboxAllocator#allocate} 需要 tenantId/userId/agentId/sessionId
 * 做占用绑定与 slotKey 隔离。本类扩展 {@link AegisSandboxClientOptions} 承载这些字段，
 * 由 {@code AegisAgentInstanceManager} 在装配 {@link io.agentscope.harness.agent.sandbox.SandboxContext}
 * 时填充。</p>
 *
 * @author wang.zhen
 */
public class AegisSandboxClientOptionsExt extends AegisSandboxClientOptions {

    private Long tenantId;
    private Long userId;
    private Long agentId;
    private String sessionId;

    public AegisSandboxClientOptionsExt() {
    }

    public AegisSandboxClientOptionsExt(String agentType, Long tenantId, Long userId,
                                         Long agentId, String sessionId) {
        super(agentType);
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = agentId;
        this.sessionId = sessionId;
    }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
