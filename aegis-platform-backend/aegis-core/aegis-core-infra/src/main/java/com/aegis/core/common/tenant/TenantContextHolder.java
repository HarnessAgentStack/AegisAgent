package com.aegis.core.common.tenant;

import com.aegis.core.context.TenantContext;

/**
 * 租户上下文持有器（基于 ThreadLocal + Reactor Context 双模式）。
 *
 * <p>贯穿全链路的租户上下文存取入口，支持同步（ThreadLocal）与响应式（Reactor Context）
 * 两种链路模式。在请求入口（网关过滤器 / MQ 消费者）初始化，请求结束清理，
 * 配合 MyBatis-Plus 多租户插件实现 SQL 自动拼装 tenant_id 条件。
 *
 * <h3>双模式设计</h3>
 * <ul>
 *   <li>同步链路（Servlet / MyBatis-Plus 拦截器）：使用 {@link InheritableThreadLocal}，
 *       由 {@link #get()}/{@link #set(TenantContext)} 直接存取</li>
 *   <li>响应式链路（WebFlux / Reactor）：ThreadLocal 在线程切换时丢失，
 *       需通过 Reactor Context 传递。本类提供 {@link #get()} 兜底读取 ThreadLocal，
 *       响应式模块（aegis-runtime / aegis-admin）在装配链路时通过
 *       {@code Mono.deferContextual} 将 Context 中的租户回填到 ThreadLocal，
 *       实现「Reactor Context 为主、ThreadLocal 为桥」的桥接模式</li>
 * </ul>
 *
 * <h3>使用约束</h3>
 * <ul>
 *   <li>必须在 finally 中调用 {@link #clear()}，防止线程池复用导致上下文泄漏</li>
 *   <li>跨进程传递（HTTP → MQ）：发送方将 tenantId 序列化到消息属性，消费方在入口重新初始化</li>
 *   <li>平台级全局操作（如系统初始化）上下文为 null，多租户插件应放行</li>
 * </ul>
 *
 * <p>注：Reactor Context 的读写依赖 reactor-core，由具备 WebFlux 依赖的运行模块桥接，
 * 本类仅承载 ThreadLocal 桥接部分，保持 aegis-core 纯净不引入响应式框架。
 *
 * @author wang.zhen
 * @see TenantContext
 */
public final class TenantContextHolder {

    /** 租户上下文 ThreadLocal，使用 InheritableThreadLocal 支持子线程继承 */
    private static final InheritableThreadLocal<TenantContext> CONTEXT_HOLDER =
            new InheritableThreadLocal<>();

    private TenantContextHolder() {
    }

    /**
     * 设置当前线程的租户上下文。
     *
     * @param context 租户上下文，null 表示清除
     */
    public static void set(TenantContext context) {
        if (context == null) {
            clear();
        } else {
            CONTEXT_HOLDER.set(context);
        }
    }

    /**
     * 获取当前线程的租户上下文。
     *
     * <p>响应式链路中，需由桥接逻辑在订阅前将 Reactor Context 回填至 ThreadLocal，
     * 否则本方法返回 null。
     *
     * @return 租户上下文，未设置时返回 null
     */
    public static TenantContext get() {
        return CONTEXT_HOLDER.get();
    }

    /**
     * 获取当前租户ID（便捷方法）。
     *
     * @return 租户ID，未设置时返回 null
     */
    public static Long getTenantId() {
        TenantContext context = get();
        return context == null ? null : context.getTenantId();
    }

    /**
     * 便捷绑定租户上下文（仅tenantId）。
     *
     * @param tenantId 租户ID，null 时跳过
     */
    public static void bind(Long tenantId) {
        if (tenantId != null) {
            set(TenantContext.builder().tenantId(tenantId).build());
        }
    }

    /**
     * 清除当前线程的租户上下文。
     *
     * <p>必须在请求/任务结束时调用，防止线程池复用导致的上下文泄漏与越权访问。
     */
    public static void clear() {
        CONTEXT_HOLDER.remove();
    }
}
