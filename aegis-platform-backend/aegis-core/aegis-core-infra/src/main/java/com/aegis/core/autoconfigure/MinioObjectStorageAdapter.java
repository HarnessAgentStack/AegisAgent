package com.aegis.core.autoconfigure;

import com.aegis.core.spi.IObjectStorage;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储适配器（core 统一版本）。
 *
 * <p>实现 {@link IObjectStorage} SPI，按租户前缀隔离对象命名空间。
 * 替代 admin/runtime 各自维护的重复实现。
 */
@Slf4j
public class MinioObjectStorageAdapter implements IObjectStorage {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioObjectStorageAdapter(MinioClient minioClient, String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public String upload(Long tenantId, String objectKey, InputStream inputStream, String contentType) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            byte[] data = inputStream.readAllBytes();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(fullKey)
                    .contentType(contentType)
                    .stream(new java.io.ByteArrayInputStream(data), data.length, -1)
                    .build());
            log.debug("MinIO 上传成功: {}/{}", bucket, fullKey);
            return fullKey;
        } catch (Exception e) {
            log.error("MinIO 上传失败: {}/{}, error={}", bucket, fullKey, e.getMessage(), e);
            throw new RuntimeException("MinIO 上传失败: " + fullKey, e);
        }
    }

    @Override
    public InputStream download(Long tenantId, String objectKey) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(fullKey)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 下载失败: " + fullKey, e);
        }
    }

    @Override
    public String presignedDownloadUrl(Long tenantId, String objectKey, long expire, TimeUnit unit) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(fullKey)
                    .expiry((int) unit.toSeconds(expire))
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 生成下载预签名URL失败: " + fullKey, e);
        }
    }

    @Override
    public String presignedUploadUrl(Long tenantId, String objectKey, String contentType, long expire, TimeUnit unit) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(fullKey)
                    .expiry((int) unit.toSeconds(expire))
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 生成上传预签名URL失败: " + fullKey, e);
        }
    }

    @Override
    public boolean delete(Long tenantId, String objectKey) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(fullKey)
                    .build());
            log.debug("MinIO 删除成功: {}/{}", bucket, fullKey);
            return true;
        } catch (Exception e) {
            log.error("MinIO 删除失败: {}/{}", bucket, fullKey, e);
            return false;
        }
    }

    private String buildFullKey(Long tenantId, String objectKey) {
        return "tenant/" + tenantId + "/" + objectKey;
    }
}
