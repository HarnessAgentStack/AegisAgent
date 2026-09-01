package com.aegis.runtime.integration.middleware;

import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * P5-4: 沙箱心跳续约中间件。
 *
 * <p>在每次 Agent 调用（{@code onAgent}）前触发沙箱租约续约 + 心跳更新，
 * 确保长任务执行期间租约不会过期被 Reconcile 回收。
 *
 * <h3>执行策略</h3>
 * <ul>
 *   <li>触发点：{@code onAgent}（最外层洋葱，每次用户请求都会触发）</li>
 *   <li>续约条件：{@code AegisTaskContext.sandboxInstanceId} 不为空</li>
 *   <li>行为：调用 {@link AegisSandboxCoordinator#renewSlot} 续租租约
 *       并刷新 {@code lastHeartbeatTime}</li>
 *   <li>容错：心跳失败不阻塞主流程，仅记录告警日志</li>
 * </ul>
 *
 * <h3>执行顺序</h3>
 * <p>order=15，位于 {@link AegisAuditLogMiddleware}(20) 与
 * {@link AegisMemoryMiddleware}(10) 之间，确保：
 * <ul>
 *   <li>比 Memory(10) 更外层：在记忆抽取前完成续约，避免长会话记忆抽取时租约已过期</li>
 *   <li>比 Audit(20) 更内层：审计日志可以看到本次心跳的成功/失败记录</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisSandboxHeartbeatMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final AegisSandboxCoordinator sandboxCoordinator;

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(input);
        }

        String sandboxInstanceId = taskCtx.getSandboxInstanceId();
        if (sandboxInstanceId == null || sandboxInstanceId.isEmpty()) {
            return next.apply(input);
        }

        // P5-4：触发心跳续约（失败不阻塞主流程）
        try {
            String sessionId = taskCtx.getSessionId();
            sandboxCoordinator.renewSlot(sandboxInstanceId, sessionId);
            log.debug("沙箱心跳续约: instanceId={}, sessionId={}", sandboxInstanceId, sessionId);
        } catch (Exception e) {
            log.warn("沙箱心跳续约失败（忽略，不阻塞主流程）: instanceId={}, error={}",
                    sandboxInstanceId, e.getMessage());
        }

        return next.apply(input);
    }
}
