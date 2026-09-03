package com.aegis.core.common.tenant;

import com.aegis.core.context.TenantContext;

import java.util.concurrent.Callable;

/**
 * P1-A：租户上下文异步路径传播工具（纯手工实现，零第三方依赖）。
 *
 * <p>设计取舍：放弃 Micrometer Context-Propagation 框架级自动传播
 * （ContextAccessor/ThreadLocalAccessor 接口在不同版本签名差异大、对开源依赖增加风险），
 * 改为提供 {@link #wrap(Runnable)} / {@link #wrap(Callable)} 工具方法，
 * 让非 Reactor 异步路径（CompletableFuture / @Async / new Thread / ScheduledExecutor），
 * 在目标线程执行前自动恢复源线程的 TenantContext，执行后清理。</p>
 *
 * <h3>Reactor 路径处理</h3>
 * <p>本轮已为 SessionManageService 4 处 boundedElastic 异步补丁 {@code bind/clear}（主保障）。
 * 其他 Reactor 跨线程路径应坚持 {@code Mono.deferContextual} + 入口 {@code bind} 范式，
 * 或在 {@code Mono.fromRunnable} 内首行 {@code bind}。</p>
 *
 * <h3>用法示例</h3>
 * <pre>
 *   // CompletableFuture
 *   CompletableFuture.runAsync(TenantContextPropagationInitializer.wrap(() -> {
 *       // 此处 TenantContextHolder.getTenantId() 已自动恢复
 *       mapper.selectById(id);
 *   }), executor);
 *
 *   // @Async 方法：在方法首行 wrap
 *   public void asyncTask() {
 *       Runnable r = TenantContextPropagationInitializer.wrap(() -> doWork());
 *       r.run();
 *   }
 *
 *   // Callable
 *   Future&lt;String&gt; f = executor.submit(
 *       TenantContextPropagationInitializer.wrap(() -> queryAndReturn()));
 * </pre>
 *
 * <h3>幂等性/线程安全</h3>
 * <p>无静态状态，所有方法纯函数式线程安全。capture 在调用线程发生，
 * restore 在目标线程发生，finally 清理防止线程池复用污染。</p>
 *
 * @author wang.zhen
 * @see TenantContextHolder
 */
public final class TenantContextPropagationInitializer {

    private TenantContextPropagationInitializer() {
    }

    /**
     * 无操作钩子（兼容现有调用 {@code init()} 的启动类样板，不影响功能）。
     *
     * <p>历史背景：早期版本在此注册 Micrometer Context-Propagation 框架。
     * 重构后保留方法签名，避免启动类调用方改动，但内部为空。</p>
     */
    public static void init() {
        // no-op：当前为纯手工 wrap 模式，无需框架初始化
    }

    /**
     * 包装 Runnable：捕获当前线程 TenantContext，在目标线程执行前恢复、执行后清理。
     *
     * @param original 原始 Runnable
     * @return 包装后的 Runnable，目标线程内 TenantContextHolder.getTenantId() 可正常返回
     */
    public static Runnable wrap(Runnable original) {
        // 在调用线程捕获（关键：不能延迟到 run() 内，那时已在目标线程）
        final TenantContext captured = TenantContextHolder.get();
        return () -> {
            TenantContext previous = TenantContextHolder.get();
            try {
                if (captured != null) {
                    TenantContextHolder.set(captured);
                } else {
                    TenantContextHolder.clear();
                }
                original.run();
            } finally {
                // 恢复目标线程原有的上下文（池化线程可能已有租户上下文）
                if (previous != null) {
                    TenantContextHolder.set(previous);
                } else {
                    TenantContextHolder.clear();
                }
            }
        };
    }

    /**
     * 包装 Callable：捕获当前线程 TenantContext，在目标线程执行前恢复、执行后清理。
     *
     * @param original 原始 Callable
     * @param <V>      返回值类型
     * @return 包装后的 Callable
     */
    public static <V> Callable<V> wrap(Callable<V> original) {
        final TenantContext captured = TenantContextHolder.get();
        return () -> {
            TenantContext previous = TenantContextHolder.get();
            try {
                if (captured != null) {
                    TenantContextHolder.set(captured);
                } else {
                    TenantContextHolder.clear();
                }
                return original.call();
            } finally {
                if (previous != null) {
                    TenantContextHolder.set(previous);
                } else {
                    TenantContextHolder.clear();
                }
            }
        };
    }
}
