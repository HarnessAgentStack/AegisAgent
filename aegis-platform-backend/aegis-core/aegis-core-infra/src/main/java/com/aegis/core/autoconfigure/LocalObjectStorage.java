package com.aegis.core.autoconfigure;

import com.aegis.core.spi.IObjectStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 本地文件存储实现（fallback）。
 *
 * <p>仅当 {@code aegis.minio.enabled=false} 时装配，与 {@link MinioAutoConfiguration}
 * （{@code aegis.minio.enabled=true}，默认启用）形成属性级互斥，保证容器中至多只有一个
 * {@link IObjectStorage} 实现，避免多 bean 注入歧义。
 *
 * <p>存储路径为系统临时目录下的 aegis-objects 文件夹。
 *
 * <p>⚠️ 注意：本地存储仅适用于开发/测试环境，不建议在生产环境使用。
 *
 * <p>作为 {@link AutoConfiguration} 注册，由
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 登记，不受各服务 {@code @ComponentScan} 的 excludeFilters 影响。
 *
 *  @author wang.zhen
 *
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "aegis.minio.enabled", havingValue = "false")
public class LocalObjectStorage implements IObjectStorage {

    private static final Path STORAGE_DIR = Paths.get(System.getProperty("java.io.tmpdir"), "aegis-objects");

    public LocalObjectStorage() {
        try {
            Files.createDirectories(STORAGE_DIR);
            log.warn("使用本地文件存储作为对象存储 fallback（仅限开发/测试环境）: {}", STORAGE_DIR);
        } catch (IOException e) {
            log.error("创建本地存储目录失败", e);
        }
    }

    @Override
    public String upload(Long tenantId, String objectKey, InputStream inputStream, String contentType) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            Path file = STORAGE_DIR.resolve(fullKey);
            Files.createDirectories(file.getParent());
            Files.copy(inputStream, file, StandardCopyOption.REPLACE_EXISTING);
            log.debug("本地存储上传成功: {}", file);
            return fullKey;
        } catch (IOException e) {
            throw new RuntimeException("本地存储上传失败: " + fullKey, e);
        }
    }

    @Override
    public InputStream download(Long tenantId, String objectKey) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            Path file = STORAGE_DIR.resolve(fullKey);
            return new FileInputStream(file.toFile());
        } catch (FileNotFoundException e) {
            throw new RuntimeException("本地存储下载失败: " + fullKey, e);
        }
    }

    @Override
    public String presignedDownloadUrl(Long tenantId, String objectKey, long expire, TimeUnit unit) {
        // 本地存储不支持预签名 URL，返回直接下载路径
        String fullKey = buildFullKey(tenantId, objectKey);
        log.warn("本地存储不支持预签名下载URL，返回本地路径: {}", fullKey);
        return "/local/download/" + fullKey;
    }

    @Override
    public String presignedUploadUrl(Long tenantId, String objectKey, String contentType, long expire, TimeUnit unit) {
        // 本地存储不支持预签名 URL，返回占位 URL
        String fullKey = buildFullKey(tenantId, objectKey);
        log.warn("本地存储不支持预签名上传URL，返回占位: {}", fullKey);
        return "/local/upload/" + fullKey + "?token=" + UUID.randomUUID();
    }

    @Override
    public boolean delete(Long tenantId, String objectKey) {
        String fullKey = buildFullKey(tenantId, objectKey);
        try {
            Path file = STORAGE_DIR.resolve(fullKey);
            boolean deleted = Files.deleteIfExists(file);
            log.debug("本地存储删除: {} -> {}", fullKey, deleted);
            return deleted;
        } catch (IOException e) {
            log.error("本地存储删除失败: {}", fullKey, e);
            return false;
        }
    }

    private String buildFullKey(Long tenantId, String objectKey) {
        return "tenant/" + tenantId + "/" + objectKey;
    }
}