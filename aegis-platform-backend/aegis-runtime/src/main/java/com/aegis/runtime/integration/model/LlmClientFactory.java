package com.aegis.runtime.integration.model;

import com.aegis.core.domain.model.ModelDef;
import com.aegis.core.domain.model.ModelProvider;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.dal.mapper.model.ModelProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 轻量 LLM 客户端工厂。
 *
 * <p>从 model_def + model_provider 表读取模型配置，构建 {@link LlmHttpClient} 实例，
 * 供辅助 LLM 任务（查询改写、意图识别、摘要、标题、视觉描述、Rerank）使用。</p>
 *
 * <h3>缓存策略</h3>
 * <p>相同 modelDefId 的客户端实例会被缓存复用；配置变更后需调用 {@link #clearCache()}
 * 刷新。keyed by 租户 ID + 档位。</p>
 *
 * <h3>与 ModelRouteResolver 的关系</h3>
 * <p>{@link ModelRouteResolver} 面向 AgentScope 主对话模型（构建 {@code OpenAIChatModel}），
 * 本工厂面向辅助任务的原生 HTTP 调用（构建 {@link LlmHttpClient}）。两者共享同一份
 * {@link ModelDefRepository}（按 tier 查询并缓存 ModelDef），避免重复 SQL 与重复定义。</p>
 *
 * @author wang.zhen
 * @see LlmHttpClient
 * @see ModelRouteResolver
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClientFactory {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelDefRepository modelDefRepository;

    /** 客户端实例缓存：modelDefId → LlmHttpClient */
    private final ConcurrentMap<Long, LlmHttpClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 按租户 + 档位创建 LlmHttpClient。
     *
     * @param tenantId 租户ID（预留多租户隔离，当前仅用于日志上下文）
     * @param tier     模型档位
     * @return 可直接发起 Chat Completions 调用的客户端实例
     * @throws IllegalStateException 未找到可用模型或提供商时抛出
     */
    public LlmHttpClient create(Long tenantId, ModelTier tier) {
        ModelDef modelDef = modelDefRepository.findModelDef(tier);
        return clientCache.computeIfAbsent(modelDef.getId(), id -> buildClient(modelDef));
    }

    /**
     * 清除客户端实例缓存与 ModelDef 缓存。
     */
    public void clearCache() {
        clientCache.clear();
        modelDefRepository.clearCache();
        log.info("LlmHttpClient 实例缓存已清除");
    }

    /**
     * 构建 LlmHttpClient 实例。
     */
    private LlmHttpClient buildClient(ModelDef modelDef) {
        ModelProvider provider = modelProviderMapper.selectById(modelDef.getProviderId());
        if (provider == null) {
            throw new IllegalStateException(
                    "模型提供商不存在: providerId=" + modelDef.getProviderId());
        }
        if (provider.getStatus() != com.aegis.core.enums.model.ProviderStatus.ACTIVE) {
            throw new IllegalStateException(
                    "模型提供商不可用: provider=" + provider.getProviderCode() + ", status=" + provider.getStatus());
        }
        String endpoint = provider.getEndpoint();
        String apiKey = provider.getApiKey();
        String modelName = modelDef.getModelCode();

        log.info("构建 LlmHttpClient: modelCode={}, provider={}, endpoint={}",
                modelName, provider.getProviderCode(), endpoint);

        return new LlmHttpClient(endpoint, apiKey, modelName);
    }
}
