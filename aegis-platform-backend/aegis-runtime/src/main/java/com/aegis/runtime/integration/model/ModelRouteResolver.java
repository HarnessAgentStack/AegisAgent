package com.aegis.runtime.integration.model;

import com.aegis.core.domain.model.ModelDef;
import com.aegis.core.domain.model.ModelProvider;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.dal.mapper.model.ModelProviderMapper;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型解析器。
 *
 * <p>从数据库 model_def + model_provider 表读取模型配置，动态构建 OpenAIChatModel 实例。
 * 直接按 tier 字段查询 model_def，无需中间路由表。
 *
 * <h3>实例缓存</h3>
 * <p>相同 modelDefId 的 OpenAIChatModel 实例会被缓存复用。
 * Admin 修改模型配置后需调用 {@link #clearCache()} 清除缓存。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouteResolver {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelDefRepository modelDefRepository;

    /** 模型实例缓存：modelDefId → OpenAIChatModel */
    private final ConcurrentHashMap<Long, OpenAIChatModel> modelCache = new ConcurrentHashMap<>();

    /**
     * 根据租户ID和模型档位解析模型实例。
     *
     * @param tenantId 租户ID（当前未使用，预留多租户扩展）
     * @param tier     模型档位
     * @return OpenAIChatModel 实例
     * @throws IllegalStateException 未找到可用模型时抛出
     */
    public OpenAIChatModel resolve(Long tenantId, ModelTier tier) {
        ModelDef modelDef = modelDefRepository.findModelDef(tier);
        ModelProvider provider = modelProviderMapper.selectById(modelDef.getProviderId());
        if (provider == null || provider.getStatus() != com.aegis.core.enums.model.ProviderStatus.ACTIVE) {
            throw new IllegalStateException(
                    "提供商不可用: providerId=" + modelDef.getProviderId());
        }
        return modelCache.computeIfAbsent(modelDef.getId(), id -> buildChatModel(modelDef, provider));
    }

    /**
     * 获取模型能力矩阵（从 DB model_def.capabilities 解析）。
     *
     * @param tenantId 租户ID
     * @param tier     模型档位
     * @return 模型能力矩阵；未找到时返回默认纯文本能力
     */
    public com.aegis.core.domain.model.ModelCapability resolveCapability(Long tenantId, ModelTier tier) {
        try {
            ModelDef modelDef = modelDefRepository.findModelDef(tier);
            if (modelDef.getCapabilities() == null) {
                return com.aegis.core.domain.model.ModelCapability.defaultText();
            }
            return com.alibaba.fastjson2.JSON.parseObject(
                    modelDef.getCapabilities(),
                    com.aegis.core.domain.model.ModelCapability.class);
        } catch (Exception e) {
            log.warn("解析模型能力矩阵失败，使用默认能力: tier={}", tier, e);
            return com.aegis.core.domain.model.ModelCapability.defaultText();
        }
    }

    /**
     * 获取模型上下文窗口大小。
     *
     * @param tenantId 租户ID
     * @param tier     模型档位
     * @return 上下文窗口（token）；未找到时返回默认值 32000
     */
    public int resolveContextWindow(Long tenantId, ModelTier tier) {
        try {
            ModelDef modelDef = modelDefRepository.findModelDef(tier);
            return modelDef.getContextWindow() != null ? modelDef.getContextWindow() : 32000;
        } catch (Exception e) {
            log.warn("获取上下文窗口失败，使用默认值: tier={}", tier, e);
            return 32000;
        }
    }

    /**
     * 清除模型实例缓存与 ModelDef 缓存。
     */
    public void clearCache() {
        modelCache.clear();
        modelDefRepository.clearCache();
        log.info("模型实例缓存已清除");
    }

    /**
     * 构建 OpenAIChatModel 实例。
     */
    private OpenAIChatModel buildChatModel(ModelDef modelDef, ModelProvider provider) {
        log.info("构建模型实例: modelCode={}, provider={}, baseUrl={}",
                modelDef.getModelCode(), provider.getProviderCode(), provider.getEndpoint());

        HttpTransportConfig transportConfig = HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofMinutes(10))
                .responseTimeout(Duration.ofMinutes(10))
                .streamIdleTimeout(Duration.ofMinutes(10))
                .writeTimeout(Duration.ofSeconds(60))
                .ignoreSsl(true)
                .build();

        JdkHttpTransport httpTransport = JdkHttpTransport.builder()
                .config(transportConfig)
                .build();

        log.info("HTTP 传输配置: connectTimeout=60s, readTimeout=10min, ignoreSsl=true");

        return OpenAIChatModel.builder()
                .apiKey(provider.getApiKey())
                .modelName(modelDef.getModelCode())
                .baseUrl(provider.getEndpoint())
                .stream(true)
                .httpTransport(httpTransport)
                .build();
    }
}
