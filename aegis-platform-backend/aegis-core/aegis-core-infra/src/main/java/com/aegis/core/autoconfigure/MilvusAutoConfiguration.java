package com.aegis.core.autoconfigure;

import com.aegis.core.spi.IVectorStore;
import com.aegis.core.spi.NoopVectorStoreAdapter;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Milvus 向量数据库自动配置。
 *
 * <p>通过 {@code aegis.milvus.enabled} 控制是否启用。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(MilvusServiceClient.class)
@ConfigurationProperties(prefix = "aegis.milvus")
@Data
@ConditionalOnProperty(name = "aegis.milvus.enabled", havingValue = "true")
public class MilvusAutoConfiguration {

    private String host = "localhost";
    private int port = 19530;

    @Bean
    @ConditionalOnMissingBean
    public MilvusServiceClient milvusServiceClient() {
        log.info("初始化 Milvus 客户端: {}:{}", host, port);
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build();
        return new MilvusServiceClient(connectParam);
    }

    /**
     * 创建 Milvus 向量存储适配器。
     * 当 aegis-dal 模块在类路径上时，使用 MilvusVectorStoreAdapter。
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(IVectorStore.class)
    public IVectorStore milvusVectorStore(MilvusServiceClient milvusClient) {
        log.info("初始化 Milvus 向量存储适配器");
        try {
            Class<?> adapterClass = Class.forName("com.aegis.core.infrastructure.MilvusVectorStoreAdapter");
            return (IVectorStore) adapterClass.getConstructor(MilvusServiceClient.class).newInstance(milvusClient);
        } catch (Exception e) {
            log.warn("MilvusVectorStoreAdapter 创建失败，使用 Noop 实现: {}", e.getMessage());
            return new NoopVectorStoreAdapter();
        }
    }

    /**
     * 当 Milvus 未启用或适配器不可用时，使用 Noop 实现。
     */
    @Bean
    @ConditionalOnMissingBean(IVectorStore.class)
    public IVectorStore noopVectorStore() {
        log.warn("Milvus 向量存储不可用，使用 Noop 实现（文档切片将跳过向量入库）");
        return new NoopVectorStoreAdapter();
    }
}
