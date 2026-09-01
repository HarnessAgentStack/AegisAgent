package com.aegis.runtime.integration.model;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelCreationContext;
import io.agentscope.core.model.spi.ModelProvider;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.util.regex.Pattern;

/**
 * Aegis 模型 SPI Provider（DB 驱动）。
 *
 * <p>通过 AS {@link ModelProvider} SPI 机制注册，匹配 {@code aegis:*} 前缀的模型 ID，
 * 从 {@link ModelRouteResolver} 读取数据库配置创建 {@link OpenAIChatModel} 实例。
 *
 * <h3>模型 ID 格式</h3>
 * <ul>
 *   <li>{@code aegis:default} — 命名实例，由 {@link AegisModelRegistry} 直接注册，
 *       走 ModelRegistry 第 1 级（named），不经过本 Provider</li>
 *   <li>{@code aegis:{tier}} — 走 ModelRegistry 第 4 级（SPI），由本 Provider 创建。
 *       从 DB model_route + model_def + model_provider 读取配置</li>
 *   <li>{@code aegis:{tier}:{tenantId}} — 指定租户的模型路由</li>
 * </ul>
 *
 * @author wang.zhen
 * @see ModelProvider
 * @see ModelRouteResolver
 * @see AegisModelRegistry
 */
public final class AegisModelProvider implements ModelProvider {

    /** Provider 标识符 */
    private static final String PROVIDER_ID = "aegis";

    /** 模型 ID 匹配正则 */
    private static final Pattern MODEL_ID_PATTERN = Pattern.compile("aegis:.+");

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean supports(String modelId) {
        return modelId != null && MODEL_ID_PATTERN.matcher(modelId).matches();
    }

    @Override
    public Model create(String modelId) {
        return create(modelId, ModelCreationContext.empty());
    }

    @Override
    public Model create(String modelId, ModelCreationContext context) {
        if (!supports(modelId)) {
            throw new IllegalArgumentException("Unsupported model id: " + modelId);
        }

        // 解析模型 ID 格式：aegis:{tier} 或 aegis:{tier}:{tenantId}
        String[] parts = modelId.substring(6).split(":");
        String tierStr = parts[0];
        Long tenantId = parts.length > 1 ? Long.parseLong(parts[1]) : null;

        // 从 DB 路由解析模型实例
        com.aegis.core.enums.model.ModelTier tier =
                com.aegis.core.enums.model.ModelTier.valueOf(tierStr.toUpperCase());
        return AegisModelProviderContext.getResolver().resolve(tenantId, tier);
    }
}
