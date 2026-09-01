package com.aegis.runtime.integration.config;

import lombok.Data;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 快照存储配置属性。
 *
 * <p>当 {@code aegis.oss.enabled=true} 时，沙箱快照切换为 AS 原生
 * {@link io.agentscope.extensions.oss.OssSnapshotSpec}，使用阿里云 OSS 存储沙箱快照。
 * 未启用时回退到 {@link com.aegis.runtime.integration.sandbox.client.MinioSnapshotClient}。
 *
 * <h3>配置示例</h3>
 * <pre>
 * aegis:
 *   oss:
 *     enabled: true
 *     endpoint: oss-cn-hangzhou.aliyuncs.com
 *     access-key-id: ${OSS_ACCESS_KEY_ID}
 *     access-key-secret: ${OSS_ACCESS_KEY_SECRET}
 *     bucket-name: aegis-sandbox-snapshots
 *     key-prefix: agentscope/snapshot/
 * </pre>
 *
 * @author wang.zhen
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.oss")
public class OssSnapshotProperties {

    /** 是否启用 OSS 快照存储（默认 false，使用 MinIO） */
    private boolean enabled = false;

    /** OSS 服务端点（如 oss-cn-hangzhou.aliyuncs.com） */
    private String endpoint;

    /** OSS Access Key ID */
    private String accessKeyId;

    /** OSS Access Key Secret */
    private String accessKeySecret;

    /** OSS Bucket 名称 */
    private String bucketName;

    /** 对象 key 前缀（默认 agentscope/snapshot/） */
    private String keyPrefix = "agentscope/snapshot/";

    /**
     * 启动时校验配置完整性。
     *
     * <p>当 enabled=true 时，断言 endpoint/bucketName/accessKeyId/accessKeySecret 非空，
     * 否则抛出 IllegalStateException 使应用启动失败（fail-fast），避免运行时才发现配置缺失。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("P1 SNP-05: aegis.oss.enabled=true 但 endpoint 未配置");
        }
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException("P1 SNP-05: aegis.oss.enabled=true 但 bucket-name 未配置");
        }
        if (accessKeyId == null || accessKeyId.isBlank()) {
            throw new IllegalStateException("P1 SNP-05: aegis.oss.enabled=true 但 access-key-id 未配置");
        }
        if (accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException("P1 SNP-05: aegis.oss.enabled=true 但 access-key-secret 未配置");
        }
    }
}
