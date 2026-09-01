package com.aegis.core.autoconfigure;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * MinIO 对象存储自动配置。
 *
 * <p>通过 {@code aegis.minio.enabled} 控制是否启用。
 * 各服务在 application.yml 中覆盖 {@code aegis.minio.bucket} 即可使用各自的桶。
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(MinioClient.class)
@EnableConfigurationProperties(MinioAutoConfiguration.MinioProperties.class)
@ConditionalOnProperty(name = "aegis.minio.enabled", havingValue = "true", matchIfMissing = true)
public class MinioAutoConfiguration {

    @Data
    @ConfigurationProperties(prefix = "aegis.minio")
    public static class MinioProperties {
        private String endpoint = "http://localhost:9000";
        private String accessKey;
        private String secretKey;
        private String bucket = "aegis-resources";
    }

    @Bean
    @ConditionalOnMissingBean
    public MinioClient minioClient(MinioProperties properties) {
        if (properties.getAccessKey() == null || properties.getAccessKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new IllegalStateException("aegis.minio.access-key / aegis.minio.secret-key 未配置，请通过环境变量 MINIO_ACCESS_KEY / MINIO_SECRET_KEY 注入");
        }
        log.info("初始化 MinIO 客户端: endpoint={}, bucket={}", properties.getEndpoint(), properties.getBucket());
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        initBucket(client, properties.getBucket());
        return client;
    }

    private void initBucket(MinioClient client, String bucket) {
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO 桶创建成功: {}", bucket);
            } else {
                log.info("MinIO 桶已存在: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO 桶初始化失败（服务可能未启动）: {}", e.getMessage());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public MinioObjectStorageAdapter minioObjectStorageAdapter(MinioClient minioClient, MinioProperties properties) {
        return new MinioObjectStorageAdapter(minioClient, properties.getBucket());
    }
}
