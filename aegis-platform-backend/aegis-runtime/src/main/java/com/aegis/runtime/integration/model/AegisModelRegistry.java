package com.aegis.runtime.integration.model;

import io.agentscope.core.model.ModelRegistry;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Aegis 模型注册表（桥接 AgentScope ModelRegistry）。
 *
 * <p>通过两种方式将 Aegis 的模型路由能力注入 AgentScope 运行时：
 * <ul>
 *   <li><b>SPI Provider</b>：{@link AegisModelProvider} 通过
 *       {@code META-INF/services} 自动注册，匹配所有 {@code aegis:*} 模型 ID，
 *       走 ModelRegistry 第 4 级解析（SPI Provider）</li>
 *   <li><b>命名实例</b>：{@code aegis:default} 直接注册为命名实例，
 *       走 ModelRegistry 第 1 级解析（named），供 {@code HarnessAgent.Builder.model(String)} 快速使用</li>
 * </ul>
 *
 * <h3>配置注入</h3>
 * <p>本类在 {@code @PostConstruct} 阶段将 {@link ModelRouteResolver} 的引用注入
 * {@link AegisModelProviderContext}，供 SPI 加载的 {@link AegisModelProvider} 读取。
 * 这是因为 ServiceLoader 加载的类无法直接注入 Spring Bean。
 *
 * @author wang.zhen
 * @see ModelRegistry
 * @see AegisModelProvider
 * @see ModelRouteResolver
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisModelRegistry {

    private final ModelRouteResolver modelRouteResolver;

    /**
     * 初始化 Aegis 模型注册。
     *
     * <p>执行两个操作：
     * <ol>
     *   <li>将 ModelRouteResolver 注入 {@link AegisModelProviderContext}，供 SPI Provider 读取</li>
     *   <li>注册命名实例 {@code aegis:default}（走 ModelRegistry 第 1 级 named 解析，
     *       供 {@code HarnessAgent.Builder.model("aegis:default")} 快速使用）</li>
     * </ol>
     */
    @PostConstruct
    public void register() {
        // 1. 注入 ModelRouteResolver 到 SPI Provider 上下文
        AegisModelProviderContext.setResolver(modelRouteResolver);

        // 2. 注册命名实例（第 1 级 named，优先于 SPI）
        // 使用租户 1 的 STANDARD 档位作为默认（与现有数据一致）
        // 容错降级：model_def 表在部署后由管理员通过管理页面配置，启动期可能为空。
        // 此时不应 fail-fast 阻止 runtime 启动，仅告警并降级——SPI Provider 仍可工作，
        // 待管理员配置模型后，运行时首次调用 aegis:{tier} 会按需解析并缓存。
        try {
            OpenAIChatModel defaultModel = modelRouteResolver.resolve(1L,
                    com.aegis.core.enums.model.ModelTier.STANDARD);
            ModelRegistry.register("aegis:default", defaultModel);
            log.info("AegisModelRegistry 已初始化: SPI=AegisModelProvider, named=aegis:default");
        } catch (Exception e) {
            log.warn("默认模型实例 aegis:default 注册失败（model_def 表可能未配置）: {}。"
                    + "runtime 将以降级模式启动，请通过管理页面配置 STANDARD 档位模型后再使用对话功能。", e.getMessage());
        }
    }
}
