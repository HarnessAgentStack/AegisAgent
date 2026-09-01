package com.aegis.runtime.integration.model;

/**
 * Aegis 模型 SPI Provider 静态配置上下文。
 *
 * <p>桥接 Spring 管理的 {@link ModelRouteResolver} 与
 * SPI 加载的 {@link AegisModelProvider}。由 {@link AegisModelRegistry} 在
 * {@code @PostConstruct} 阶段调用 {@link #setResolver} 注入引用，
 * 供 ServiceLoader 加载的 {@link AegisModelProvider} 读取。
 *
 * <h3>为什么需要静态桥接</h3>
 * <p>AS 的 {@link io.agentscope.core.model.spi.ModelProvider} 通过
 * {@link java.util.ServiceLoader} 加载，要求无参构造器，无法直接注入 Spring Bean。
 * 本类用 {@code volatile} 静态字段保证可见性，在 Spring 启动阶段一次性注入引用。
 *
 * @author wang.zhen
 * @see AegisModelProvider
 * @see AegisModelRegistry
 * @see ModelRouteResolver
 */
public final class AegisModelProviderContext {

    private static volatile ModelRouteResolver resolver;

    private AegisModelProviderContext() {
    }

    /**
     * 设置 ModelRouteResolver 引用（由 AegisModelRegistry 在 Spring 启动阶段调用）。
     *
     * @param resolver 模型路由解析器
     */
    public static void setResolver(ModelRouteResolver resolver) {
        AegisModelProviderContext.resolver = resolver;
    }

    /**
     * 获取 ModelRouteResolver 引用。
     *
     * @return 模型路由解析器
     * @throws IllegalStateException 如果未初始化
     */
    public static ModelRouteResolver getResolver() {
        if (resolver == null) {
            throw new IllegalStateException(
                    "AegisModelProviderContext 未初始化，请确保 AegisModelRegistry 已启动");
        }
        return resolver;
    }

    /**
     * 重置引用（仅用于测试）。
     */
    static void reset() {
        resolver = null;
    }
}
