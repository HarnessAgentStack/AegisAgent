package com.aegis.dal.infrastructure.embedding;

import com.aegis.core.domain.model.ModelDef;
import com.aegis.core.domain.model.ModelProvider;
import com.aegis.core.enums.model.ModelStatus;
import com.aegis.core.enums.model.ModelType;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.infrastructure.embedding.ArkEmbeddingClient;
import com.aegis.core.spi.EmbeddingService;
import com.aegis.dal.mapper.model.ModelDefMapper;
import com.aegis.dal.mapper.model.ModelProviderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ARK 嵌入模型服务实现。
 *
 * <p>通过 ARK OpenAI 兼容协议调用嵌入模型。
 * <b>所有模型配置 100% 从数据库 model_provider + model_def 表读取，不再依赖 yml。</b>
 *
 * <h3>Provider 匹配策略（三级 fallback）</h3>
 * <ol>
 *   <li>providerCode 为 volcengine 或 火山引擎 的 ACTIVE 提供商</li>
 *   <li>endpoint 包含 volces.com 的 ACTIVE 提供商</li>
 *   <li>第一个 ACTIVE 的提供商</li>
 * </ol>
 *
 * <h3>Model 匹配策略</h3>
 * <ol>
 *   <li>在选定 provider 下找 model_type=EMBEDDING 且 status=ENABLED 的第一条记录</li>
 *   <li>若当前 provider 下无 EMBEDDING 模型，则全局找一条 ENABLED 的 EMBEDDING 模型</li>
 *   <li>数据库无任何 EMBEDDING 记录 → 返回 null，由调用方决定降级</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class ArkEmbeddingService implements EmbeddingService {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelDefMapper modelDefMapper;

    /** 缓存的客户端（1 分钟 TTL，避免每次嵌入都查库） */
    private volatile ArkEmbeddingClient cachedClient;
    private volatile long lastConfigLoadTime;
    private static final long CONFIG_TTL_MS = 60_000;

    public ArkEmbeddingService(ModelProviderMapper modelProviderMapper,
                                ModelDefMapper modelDefMapper) {
        this.modelProviderMapper = modelProviderMapper;
        this.modelDefMapper = modelDefMapper;
    }

    @Override
    public float[] embed(String text) {
        ArkEmbeddingClient client = getClient();
        if (client == null) {
            log.warn("嵌入客户端不可用，embed 返回空向量");
            return new float[0];
        }
        return client.embed(text);
    }

    @Override
    public float[][] embedBatch(List<String> texts) {
        ArkEmbeddingClient client = getClient();
        if (client == null) {
            log.warn("嵌入客户端不可用，embedBatch 返回空数组");
            return new float[0][];
        }
        return client.embedBatch(texts);
    }

    @Override
    public int getDimension() {
        ArkEmbeddingClient client = getClient();
        if (client == null) {
            log.warn("嵌入客户端不可用，getDimension 返回默认值 1024");
            return 1024;
        }
        return client.getDimension();
    }

    private ArkEmbeddingClient getClient() {
        long now = System.currentTimeMillis();
        if (cachedClient != null && (now - lastConfigLoadTime) < CONFIG_TTL_MS) {
            return cachedClient;
        }

        synchronized (this) {
            if (cachedClient != null && (now - lastConfigLoadTime) < CONFIG_TTL_MS) {
                return cachedClient;
            }

            cachedClient = loadClientFromDatabase();
            lastConfigLoadTime = now;
            return cachedClient;
        }
    }

    /**
     * 从数据库加载 provider + EMBEDDING model，完全不依赖 yml。
     *
     * <p>查找顺序：provider（三级 fallback）→ 当前 provider 下的 EMBEDDING model → 全局 EMBEDDING model。
     * 任何环节查不到都返回 null，由调用方降级。
     */
    private ArkEmbeddingClient loadClientFromDatabase() {
        // Step 1: 查找 provider（三级 fallback）
        ModelProvider provider = findProvider();
        if (provider == null) {
            log.warn("未找到任何 ACTIVE 模型提供商，文档嵌入将被跳过");
            return null;
        }
        log.info("嵌入服务使用 provider: id={}, code={}, endpoint={}",
                provider.getId(), provider.getProviderCode(), provider.getEndpoint());

        // Step 2a: 优先在当前 provider 下找 EMBEDDING 模型
        ModelDef model = modelDefMapper.selectOne(
                new LambdaQueryWrapper<ModelDef>()
                        .eq(ModelDef::getProviderId, provider.getId())
                        .eq(ModelDef::getModelType, ModelType.EMBEDDING)
                        .eq(ModelDef::getStatus, ModelStatus.ENABLED));

        // Step 2b: 当前 provider 下没有 → 全局找一条 ENABLED 的 EMBEDDING
        if (model == null) {
            log.info("当前 provider(id={}) 下无 EMBEDDING 模型，尝试全局查找", provider.getId());
            model = modelDefMapper.selectOne(
                    new LambdaQueryWrapper<ModelDef>()
                            .eq(ModelDef::getModelType, ModelType.EMBEDDING)
                            .eq(ModelDef::getStatus, ModelStatus.ENABLED)
                            .last("LIMIT 1"));
            if (model != null) {
                log.info("全局 EMBEDDING 模型命中: modelCode={}, providerId={}",
                        model.getModelCode(), model.getProviderId());
                // 模型和之前选定的 provider 不是同一个 → 取模型自己的 provider
                if (!model.getProviderId().equals(provider.getId())) {
                    provider = modelProviderMapper.selectById(model.getProviderId());
                    if (provider == null) {
                        log.warn("全局 EMBEDDING 模型(id={}) 关联的 provider(id={}) 不存在",
                                model.getId(), model.getProviderId());
                        return null;
                    }
                    log.info("切换到全局模型的 provider: id={}, code={}",
                            provider.getId(), provider.getProviderCode());
                }
            }
        }

        if (model == null) {
            log.warn("model_def 表中无任何 ENABLED 的 EMBEDDING 模型，嵌入将被跳过");
            return null;
        }

        log.info("嵌入模型配置加载: modelCode={}, providerId={}",
                model.getModelCode(), provider.getId());

        return new ArkEmbeddingClient(
                provider.getEndpoint(),
                provider.getApiKey(),
                model.getModelCode());
    }

    /**
     * 查找可用 provider（三级 fallback）。
     */
    private ModelProvider findProvider() {
        // Fallback 1: providerCode = "volcengine" 或 "火山引擎"
        ModelProvider p1 = modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProvider>()
                        .in(ModelProvider::getProviderCode, "volcengine", "火山引擎")
                        .eq(ModelProvider::getStatus, ProviderStatus.ACTIVE));
        if (p1 != null) return p1;

        // Fallback 2: endpoint 包含 "volces.com"
        ModelProvider p2 = modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProvider>()
                        .likeRight(ModelProvider::getEndpoint, "https://ark.cn-beijing.volces.com")
                        .eq(ModelProvider::getStatus, ProviderStatus.ACTIVE));
        if (p2 != null) return p2;

        // Fallback 3: 第一个 ACTIVE 的 provider
        ModelProvider p3 = modelProviderMapper.selectOne(
                new LambdaQueryWrapper<ModelProvider>()
                        .eq(ModelProvider::getStatus, ProviderStatus.ACTIVE)
                        .last("LIMIT 1"));
        return p3;
    }
}
