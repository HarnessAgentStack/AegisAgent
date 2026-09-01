package com.aegis.core.infrastructure;

import com.aegis.core.spi.IVectorStore;
import com.aegis.core.spi.NoopVectorStoreAdapter;
import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;

/**
 * 向量存储配置。
 *
 * <p>作为 MilvusAutoConfiguration 的补充：
 * <ul>
 *   <li>当 aegis.milvus.enabled=true 时，提供备用路径创建 MilvusVectorStoreAdapter</li>
 *   <li>当 aegis.milvus.enabled=false 时，创建 NoopVectorStoreAdapter 作为兜底</li>
 * </ul>
 */
@Slf4j
@Configuration
public class VectorStoreConfiguration {

    /**
     * 当 MilvusServiceClient 存在且 IVectorStore 不存在时，
     * 创建 MilvusVectorStoreAdapter（备用路径）。
     * 依赖 milvusServiceClient 确保在 MilvusAutoConfiguration 之后评估。
     */
    @Bean
    @Primary
    @ConditionalOnBean(MilvusServiceClient.class)
    @ConditionalOnMissingBean(IVectorStore.class)
    @DependsOn("milvusServiceClient")
    public IVectorStore milvusVectorStoreFallback(MilvusServiceClient milvusClient) {
        log.info("初始化 Milvus 向量存储适配器（备用路径）");
        return new MilvusVectorStoreAdapter(milvusClient);
    }

    /**
     * 当 Milvus 未启用且没有 IVectorStore 实现时，使用 Noop 实现。
     * 仅在 aegis.milvus.enabled=false 时激活。
     */
    @Bean
    @ConditionalOnProperty(name = "aegis.milvus.enabled", havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(IVectorStore.class)
    public IVectorStore noopVectorStore() {
        log.warn("Milvus 向量存储未启用，使用 Noop 实现（文档切片将跳过向量入库）");
        return new NoopVectorStoreAdapter();
    }
}
