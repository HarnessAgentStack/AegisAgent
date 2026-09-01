package com.aegis.core.common.tenant;

import com.aegis.core.context.TenantContext;

/**
 * 租户上下文作用域 —— try-with-resources 模式的安全绑定。
 *
 * <p>根治「bind 后忘 clear」这一类租户上下文泄漏缺陷（P1-1）：
 * 构造时保存进入前的上下文快照，{@link #close()} 时精确恢复，
 * 使任意代码块结束后线程恢复到进入前的租户状态——
 * 无论该块成功、抛异常还是提前 return。
 *
 * <h3>使用范式</h3>
 * <pre>{@code
 * // 恢复式作用域（推荐用于中间件/执行器等可能已携带上下文的线程）
 * try (TenantContextScope scope = TenantContextScope.of(tenantId)) {
 *     // 块内以 tenantId 身份执行 DB 访问（MyBatis-Plus 租户插件读取 ThreadLocal）
 *     doDbWork();
 * } // 自动恢复进入前的上下文（含"进入前为空"的情形 → clear）
 *
 * // 清空式作用域（用于请求边界：线程即将归还线程池，必须清空）
 * try (TenantContextScope scope = TenantContextScope.bound(tenantId)) {
 *     handleRequest();
 * } // 自动 clear，线程可安全复用
 * }</pre>
 *
 * <h3>两种语义的区别</h3>
 * <ul>
 *   <li>{@link #of}：嵌套安全 —— 恢复进入前上下文，适用于在已有上下文的线程上
 *       临时切换租户（如中间件在 AgentScope 内核线程上按请求租户执行）</li>
 *   <li>{@link #bound}：边界安全 —— 结束即清空，适用于「绑定-执行-归还线程」的
 *       请求级生命周期（如 boundedElastic 上的装配段）</li>
 * </ul>
 *
 * <p>线程安全说明：本类仅操作当前线程的 ThreadLocal，实例本身不可跨线程共享。
 *
 * @author wang.zhen
 * @see TenantContextHolder
 */
public final class TenantContextScope implements AutoCloseable {

    /** 无操作作用域：tenantId 为 null 时不绑定任何上下文，close 也不做任何恢复 */
    private static final TenantContextScope NOOP = new TenantContextScope(false, null);

    /** 是否实际执行了绑定（false 表示 no-op，close 时跳过恢复） */
    private final boolean bound;
    /** 进入前的上下文快照（可能为 null，表示进入前未设置） */
    private final TenantContext previous;

    private TenantContextScope(boolean bound, TenantContext previous) {
        this.bound = bound;
        this.previous = previous;
    }

    /**
     * 恢复式作用域：以指定租户执行块内逻辑，结束后恢复进入前上下文。
     *
     * @param tenantId 租户ID；null 时返回无操作作用域（块内沿用当前上下文）
     * @return 作用域句柄（配合 try-with-resources 使用）
     */
    public static TenantContextScope of(Long tenantId) {
        if (tenantId == null) {
            return NOOP;
        }
        return of(TenantContext.builder().tenantId(tenantId).build());
    }

    /**
     * 恢复式作用域：以指定上下文执行块内逻辑，结束后恢复进入前上下文。
     *
     * @param context 租户上下文；null 时返回无操作作用域
     * @return 作用域句柄（配合 try-with-resources 使用）
     */
    public static TenantContextScope of(TenantContext context) {
        if (context == null) {
            return NOOP;
        }
        TenantContext previous = TenantContextHolder.get();
        TenantContextHolder.set(context);
        return new TenantContextScope(true, previous);
    }

    /**
     * 边界式作用域：以指定租户执行块内逻辑，结束后直接清空上下文。
     *
     * <p>专用于「线程即将归还线程池」的请求边界（boundedElastic 装配段、
     * 独立调度任务等），确保线程复用时零残留。
     *
     * @param tenantId 租户ID；null 时返回无操作作用域
     * @return 作用域句柄（配合 try-with-resources 使用）
     */
    public static TenantContextScope bound(Long tenantId) {
        if (tenantId == null) {
            return NOOP;
        }
        TenantContextHolder.bind(tenantId);
        return new TenantContextScope(true, null);
    }

    /**
     * 关闭作用域：恢复进入前上下文（恢复式）或清空（边界式）。
     * 幂等——重复 close 无副作用。
     */
    @Override
    public void close() {
        if (!bound) {
            return;
        }
        if (previous != null) {
            TenantContextHolder.set(previous);
        } else {
            TenantContextHolder.clear();
        }
    }
}
