package com.aegis.runtime.integration.middleware;

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
 * 输出脱敏中间件（洋葱链 order=50）。
 *
 * <p>原基于内容安全策略引擎的脱敏评估链路已随策略引擎下线，当前仅保留对下游事件流
 * 的异常日志记录，不再对流式输出做脱敏变换或阻断。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisMaskMiddleware implements MiddlewareBase {

    @Override
    public int order() {
        // Phase 2 精简：order=50，输出脱敏层（唯一无替代的输出安全中间件）
        return 50;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnError(e -> log.error("MaskMiddleware 异常", e));
    }
}
