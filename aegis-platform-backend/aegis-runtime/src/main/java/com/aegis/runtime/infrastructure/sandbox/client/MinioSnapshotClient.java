package com.aegis.runtime.infrastructure.sandbox.client;

import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotClient;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Objects;

/**
 * 基于 MinIO 的沙箱快照远程存储客户端。
 *
 * <p>实现 AgentScope {@link RemoteSnapshotClient} 接口，将沙箱快照（tar 归档）
 * 存储到独立的 MinIO 桶 {@code aegis-sandbox-snapshots}，与会话附件桶隔离。
 *
 * <h3>对象 key 规则</h3>
 * <pre>{prefix}{snapshotId}.tar</pre>
 * 默认前缀为空字符串，snapshotId 即为对象名主体，扩展名固定为 {@code .tar}。
 *
 * <h3>桶初始化</h3>
 * <p>启动时确保快照桶存在，不存在则自动创建；MinIO 服务未启动时仅告警，不阻断应用启动。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioSnapshotClient implements RemoteSnapshotClient {

    /** 沙箱快照专用桶名（独立于附件桶 aegis-attachments） */
    private static final String SNAPSHOT_BUCKET = "aegis-sandbox-snapshots";

    /** 对象 key 前缀（默认空，可扩展为按租户/日期分目录） */
    private static final String KEY_PREFIX = "";

    /** MinIO 分片上传的 partSize（10MB） */
    private static final long PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient minioClient;

    /**
     * 启动时确保快照桶存在。
     *
     * <p>MinIO 服务不可达时仅打印告警，不阻断应用启动。
     */
    @PostConstruct
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(SNAPSHOT_BUCKET).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(SNAPSHOT_BUCKET).build());
                log.info("沙箱快照桶创建成功: {}", SNAPSHOT_BUCKET);
            } else {
                log.info("沙箱快照桶已存在: {}", SNAPSHOT_BUCKET);
            }
        } catch (Exception e) {
            log.warn("沙箱快照桶初始化失败（MinIO 服务可能未启动）: {}", e.getMessage());
        }
    }

    @Override
    public void upload(String snapshotId, InputStream data) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        Objects.requireNonNull(data, "data must not be null");
        String key = objectKey(snapshotId);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(SNAPSHOT_BUCKET)
                .object(key)
                .stream(data, -1, PART_SIZE)
                .contentType("application/x-tar")
                .build());
        log.debug("沙箱快照上传成功: bucket={}, key={}", SNAPSHOT_BUCKET, key);
    }

    @Override
    public InputStream download(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        String key = objectKey(snapshotId);
        if (!exists(snapshotId)) {
            throw new FileNotFoundException("沙箱快照不存在: " + key);
        }
        return minioClient.getObject(GetObjectArgs.builder()
                .bucket(SNAPSHOT_BUCKET)
                .object(key)
                .build());
    }

    @Override
    public boolean exists(String snapshotId) throws Exception {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(SNAPSHOT_BUCKET)
                    .object(objectKey(snapshotId))
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            // P1 SNP-03 修复：仅对 NoSuchKey 返回 false，其他异常（网络超时等）向上传播
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw e;
        }
    }

    /**
     * P1 SNP-04 修复：删除指定快照，防止旧快照永不清理。
     *
     * <p>由 admin 的 SandboxReconcileScheduler 在缩容销毁实例时调用，
     * 清理对应的远程快照对象。
     *
     * @param snapshotId 快照 ID
     */
    public void delete(String snapshotId) {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        String key = objectKey(snapshotId);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(SNAPSHOT_BUCKET)
                    .object(key)
                    .build());
            log.info("P1 SNP-04: 沙箱快照已删除: bucket={}, key={}", SNAPSHOT_BUCKET, key);
        } catch (Exception e) {
            log.warn("P1 SNP-04: 删除沙箱快照失败（可能已不存在）: bucket={}, key={}, error={}",
                    SNAPSHOT_BUCKET, key, e.getMessage());
        }
    }

    /**
     * 构造对象 key：{prefix}{snapshotId}.tar
     */
    private String objectKey(String snapshotId) {
        return KEY_PREFIX + snapshotId + ".tar";
    }
}