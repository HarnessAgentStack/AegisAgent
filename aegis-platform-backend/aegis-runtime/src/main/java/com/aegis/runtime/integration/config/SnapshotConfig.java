package com.aegis.runtime.integration.config;

import com.aegis.runtime.infrastructure.sandbox.client.MinioSnapshotClient;
import io.agentscope.extensions.oss.OssSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 沙箱快照存储配置。
 *
 * <p>根据 {@link OssSnapshotProperties#getEnabled()} 决定快照存储后端：
 * <ul>
 *   <li><b>OSS 启用</b>：创建 AS 原生 {@link OssSnapshotSpec}，使用阿里云 OSS 存储快照</li>
 *   <li><b>OSS 未启用</b>：回退到 {@link MinioSnapshotClient}（通过 {@link RemoteSnapshotSpec} 包装）</li>
 * </ul>
 *
 * <p>产出的 {@link SandboxSnapshotSpec} Bean 标记为 {@code @Primary}，
 * 供 {@link com.aegis.runtime.integration.agent.AegisAgentInstanceManager} 注入使用。
 *
 * <h3>设计理由</h3>
 * <p>AS 2.0.2 中不存在 MinIO 原生支持。Aegis 的 {@link MinioSnapshotClient} 已实现
 * AS {@link io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient} 接口，
 * 通过 {@link RemoteSnapshotSpec} 包装后与 {@link OssSnapshotSpec} 共享同一套
 * {@link SandboxSnapshotSpec} 接口，实现无缝切换。
 *
 * @author wang.zhen
 * @see OssSnapshotSpec
 * @see MinioSnapshotClient
 * @see RemoteSnapshotSpec
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SnapshotConfig {

    private final OssSnapshotProperties ossProperties;
    private final MinioSnapshotClient minioSnapshotClient;

    /**
     * 创建沙箱快照存储 Spec。
     *
     * <p>OSS 启用时返回 {@link OssSnapshotSpec}，否则返回基于 MinIO 的 {@link RemoteSnapshotSpec}。
     *
     * @return 沙箱快照存储 Spec
     */
    @Bean
    @Primary
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        if (ossProperties.isEnabled()) {
            log.info("沙箱快照存储: OSS 模式, endpoint={}, bucket={}, keyPrefix={}",
                    ossProperties.getEndpoint(),
                    ossProperties.getBucketName(),
                    ossProperties.getKeyPrefix());
            return new OssSnapshotSpec(
                    ossProperties.getEndpoint(),
                    ossProperties.getAccessKeyId(),
                    ossProperties.getAccessKeySecret(),
                    ossProperties.getBucketName(),
                    ossProperties.getKeyPrefix());
        }
        log.info("沙箱快照存储: MinIO 模式 (aegis.oss.enabled=false)");
        return new RemoteSnapshotSpec(minioSnapshotClient);
    }
}
