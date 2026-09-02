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
 * 输出安全审计中间件（洋葱链 order=10）。
 *
 * <p>原基于内容安全策略引擎的脱敏评估链路已随策略引擎下线，当前仅保留对下游事件流
 * 的异常日志记录，不再对流式输出做脱敏变换或阻断。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisMaskMiddleware implements MiddlewareBase, OrderedMiddleware {

    @Override
    public int order() {
        // order=10：最内层，在所有安全控制之后、输出之前执行
        return 10;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input)
                .doOnError(e -> log.error("MaskMiddleware 异常", e));
    }
}
