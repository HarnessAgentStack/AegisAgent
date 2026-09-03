package com.aegis.runtime.integration.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;

/**
 * Aegis 沙箱状态（持久化载体，跨节点 resume 用）。
 *
 * <p>继承框架 {@link SandboxState}，额外承载 admin 池实例的关键标识，
 * 经 {@code AegisSandboxClient.serializeState/deserializeState} 序列化为 JSON
 * 存入 Redis {@code SessionSandboxStateStore}，跨节点 resume 时反序列化回放。</p>
 *
 * <p>关键字段：
 * <ul>
 *   <li>{@code instanceId}：sbx_instance 主键 UUID（resume 时反查实例）</li>
 *   <li>{@code podName} / {@code namespace}：K8s Pod 定位（exec 直接用）</li>
 *   <li>{@code slotKey}：隔离槽位（resume 后校验同槽位）</li>
 *   <li>{@code tenantId} / {@code userId} / {@code agentId}：占用方（审计 + 多租户）</li>
 *   <li>{@code poolCode}：归属池（容量闸门 + Reconcile 纳管）</li>
 * </ul>
 *
 * @author wang.zhen
 */
public class AegisSandboxState extends SandboxState {

    private String instanceId;
    private String podName;
    private String namespace;
    private String slotKey;
    private Long tenantId;
    private Long userId;
    private Long agentId;
    private String sessionId;
    private String poolCode;
    private String status;

    public AegisSandboxState() {
    }

    /**
     * 从 admin 池实例构造（allocate 后立即建 state 供 SandboxManager 持久化）。
     */
    public AegisSandboxState(SandboxInstance inst, String poolCode, WorkspaceSpec workspaceSpec) {
        this.instanceId = inst.getInstanceId();
        this.podName = inst.getPodName();
        this.namespace = inst.getNamespace();
        this.slotKey = inst.getSlotKey();
        this.tenantId = inst.getTenantId();
        this.userId = inst.getUserId();
        this.agentId = inst.getAgentId();
        this.sessionId = inst.getSessionId();
        this.poolCode = poolCode;
        this.status = inst.getStatus() != null ? inst.getStatus().name() : null;
        setSessionId(inst.getSessionId());
        setWorkspaceSpec(workspaceSpec);
        setWorkspaceRootReady(true);
    }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getPodName() { return podName; }
    public void setPodName(String podName) { this.podName = podName; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getSlotKey() { return slotKey; }
    public void setSlotKey(String slotKey) { this.slotKey = slotKey; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    @Override
    public String getSessionId() { return sessionId; }
    @Override
    public void setSessionId(String sessionId) { this.sessionId = sessionId; super.setSessionId(sessionId); }

    public String getPoolCode() { return poolCode; }
    public void setPoolCode(String poolCode) { this.poolCode = poolCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
