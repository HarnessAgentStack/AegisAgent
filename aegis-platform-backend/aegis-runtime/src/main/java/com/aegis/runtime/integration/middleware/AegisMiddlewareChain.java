package com.aegis.runtime.integration.middleware;

import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aegis 中间件链装配器。
 *
 * <p>收集 Spring 容器中所有 {@link MiddlewareBase} 实现，排序后注入
 * {@code HarnessAgent.Builder.middlewares()}，由 AgentScope 2.0 内核统一驱动。
 *
 * <h3>排序语义</h3>
 * <p>本类按 {@link OrderedMiddleware#order()} 升序排序后传入 Builder。AgentScope 2.0.2 的
 * {@code ReActAgent.Builder} 会在构建时按 {@code order()} <b>降序</b>重排（值越大越靠外层、
 * 最先执行），该排序对全部 5 个拦截点（{@code onAgent} / {@code onReasoning} /
 * {@code onActing} / {@code onModelCall} / {@code onSystemPrompt}）统一生效。
 * 因此 {@code order()} 的值直接决定中间件在洋葱链中的执行顺序——
 * 未实现 {@link OrderedMiddleware} 的中间件使用 {@link #DEFAULT_ORDER}，排序后位于最内层。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisMiddlewareChain {

    /** AgentScope MiddlewareBase 实现（Spring 自动注入） */
    private final List<MiddlewareBase> standaloneMiddlewares;

    /**
     * 装配 MiddlewareBase 列表。
     *
     * <p>所有 MiddlewareBase 按 {@link OrderedMiddleware#order()} 升序排序；
     * 未实现 OrderedMiddleware 的中间件使用默认 order（{@link #DEFAULT_ORDER}）。
     * 最终执行顺序由 AgentScope {@code ReActAgent.Builder} 按 {@code order()} 降序决定。
     *
     * @return 装配后的 MiddlewareBase 列表（不可变）
     */
    public List<MiddlewareBase> build() {
        List<MiddlewareBase> chain = new ArrayList<>();
        if (standaloneMiddlewares != null) {
            log.info("AegisMiddlewareChain 开始装配: 原始Bean数={}", standaloneMiddlewares.size());
            List<MiddlewareBase> sorted = new ArrayList<>(standaloneMiddlewares);
            // P1 MW-12 修复：过滤掉非 com.aegis 包下的中间件，
            // standaloneMiddlewares 由 Spring 自动注入所有 MiddlewareBase Bean，包括 AS 内置中间件，
            // 仅保留 Aegis 自定义中间件，避免内置中间件重复装配或干扰执行顺序
            sorted.removeIf(mw -> mw == null
                    || mw.getClass().getName() == null
                    || !mw.getClass().getName().startsWith("com.aegis."));
            log.info("AegisMiddlewareChain 过滤后: 剩余Bean数={}", sorted.size());
            sorted.sort(Comparator.comparingInt(this::resolveOrder));
            // 打印排序后的中间件列表
            for (MiddlewareBase mw : sorted) {
                log.info("AegisMiddlewareChain 装配中间件: name={}, order={}", 
                        mw.getClass().getSimpleName(), resolveOrder(mw));
            }
            chain.addAll(sorted);
        } else {
            log.warn("AegisMiddlewareChain standaloneMiddlewares 为 null!");
        }

        log.info("AegisMiddlewareChain 装配完成: middlewareCount={}", chain.size());
        return List.copyOf(chain);
    }

    /**
     * 解析 MiddlewareBase 的 order（用于装配阶段排序）。
     *
     * <p>若实现 {@link OrderedMiddleware} 接口，则返回其 order；否则返回
     * {@link #DEFAULT_ORDER}。注意：order 值会影响中间件在 AgentScope 洋葱链中的
     * 执行顺序（对所有拦截点统一生效），未实现 OrderedMiddleware 的中间件
     * 将排在所有已排序中间件之后。
     */
    private int resolveOrder(MiddlewareBase mw) {
        if (mw instanceof OrderedMiddleware om) {
            return om.order();
        }
        return DEFAULT_ORDER;
    }

    /** 默认 order（未实现 OrderedMiddleware 的 MiddlewareBase） */
    private static final int DEFAULT_ORDER = 100;
}
