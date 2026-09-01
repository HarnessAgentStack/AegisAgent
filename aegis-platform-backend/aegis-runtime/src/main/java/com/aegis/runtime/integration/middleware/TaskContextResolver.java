package com.aegis.runtime.integration.middleware;

import com.aegis.runtime.service.conversation.AegisTaskContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.extern.slf4j.Slf4j;

/**
 * AgentScope Agent → {@link AegisTaskContext} 解析工具。
 *
 * <p><b>P0-6 改造</b>：替代旧 {@code SessionKeyResolver} + {@code TaskContextRegistry}
 * 双重查找模式，改为通过 AgentScope 框架原生 {@link RuntimeContext} 类型化单例取回：
 *
 * <ol>
 *   <li>{@code TaskExecutionService.runLlmStream} 在构造 {@link RuntimeContext} 后调用
 *       {@code rc.put(AegisTaskContext.class, ctx)} 注入</li>
 *   <li>中间件触发时通过 {@link HarnessAgent#getRuntimeContext()} 取回
 *       {@link RuntimeContext}，再调 {@code rc.get(AegisTaskContext.class)} 取回 ctx</li>
 * </ol>
 *
 * <h3>收益</h3>
 * <ul>
 *   <li>消除全局 {@code ConcurrentHashMap}（线程安全 + 内存泄漏风险）</li>
 *   <li>上下文跟随 {@link RuntimeContext} 生命周期自动回收</li>
 *   <li>符合 AgentScope "无状态引擎 + RuntimeContext 透传" 设计哲学</li>
 * </ul>
 *
 * <h3>支持的 Agent 类型</h3>
 * <ul>
 *   <li>{@link HarnessAgent}：外层 Agent，暴露 {@code getRuntimeContext()}</li>
 *   <li>其他 Agent 类型：暂不支持，返回 null（Aegis 运行时统一通过 HarnessAgent 执行）</li>
 * </ul>
 *
 * <p>注：Agent 接口本身未暴露 {@code getRuntimeContext()}，需 cast 到具体类型。
 *
 * @author wang.zhen
 */
@Slf4j
public final class TaskContextResolver {

    private TaskContextResolver() {
    }

    /**
     * 从 Agent 解析 {@link AegisTaskContext}。
     *
     * <p>解析路径：Agent → RuntimeContext → {@code AegisTaskContext.class} 类型化单例。
     *
     * @param agent AgentScope Agent 实例（预期为 HarnessAgent）
     * @return AegisTaskContext；agent 为 null、非 HarnessAgent、或未注入 ctx 时返回 null
     */
    public static AegisTaskContext resolve(Agent agent) {
        if (agent == null) {
            return null;
        }
        try {
            RuntimeContext rc = null;
            if (agent instanceof HarnessAgent ha) {
                rc = ha.getRuntimeContext();
            }
            if (rc == null) {
                log.debug("TaskContextResolver: RuntimeContext 未就绪: agentId={}, agentClass={}",
                        agent.getAgentId(), agent.getClass().getSimpleName());
                return null;
            }
            AegisTaskContext ctx = rc.get(AegisTaskContext.class);
            if (ctx == null) {
                log.warn("TaskContextResolver: AegisTaskContext 未注入 RuntimeContext: agentId={}",
                        agent.getAgentId());
            }
            return ctx;
        } catch (Exception e) {
            log.warn("TaskContextResolver 解析异常: agentId={}", agent.getAgentId(), e);
            return null;
        }
    }
}
