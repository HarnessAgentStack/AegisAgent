package com.aegis.runtime.integration.sandbox;

import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxClientOptions;

/**
 * Aegis 沙箱客户端选项（{@link SandboxClientOptions} 子类）。
 *
 * <p>当前承载智能体类型（UNIVERSAL/APPLICATION/SYSTEM，决定 slotKey 隔离粒度）。
 * 框架 {@link io.agentscope.harness.agent.HarnessAgent.Builder} 可通过
 * {@link #createClient()} 自动派生 {@code AegisSandboxClient}。</p>
 *
 * @author wang.zhen
 */
public class AegisSandboxClientOptions extends SandboxClientOptions {

    public static final String TYPE = "aegis";

    private String agentType = "UNIVERSAL";

    public AegisSandboxClientOptions() {
    }

    public AegisSandboxClientOptions(String agentType) {
        this.agentType = agentType;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public SandboxClient<? extends SandboxClientOptions> createClient() {
        // Aegis 走 Spring 注入 AegisSandboxClient（@Component），不走此方法自动派生
        // 此实现仅为满足抽象签名，实际由 AegisAgentInstanceManager 注入
        throw new UnsupportedOperationException(
                "AegisSandboxClient 由 Spring 注入，不走 createClient() 自动派生");
    }

    @Override
    public String getWorkspaceRoot() {
        return "/workspace";
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }
}
