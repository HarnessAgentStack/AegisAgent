package com.aegis.runtime.service.document;

import com.aegis.core.domain.document.AttFileMeta;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.dal.mapper.document.AttFileMetaMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 文件存储服务（MinIO 对象存储实现）。
 *
 * <p>将上传的文件保存至 MinIO 桶 {@code aegis-attachments}，文件字节存 MinIO，
 * 元数据持久化到 {@code att_file_meta} 表，Caffeine 本地缓存加速查询。
 *
 * <h3>存储模型</h3>
 * <ul>
 *   <li>文件字节：写入 MinIO，objectKey 格式 {@code attachments/{tenantId}/{userId}/{fileId}{ext}}</li>
 *   <li>附件元数据（文件名/大小/类型/租户/用户）：持久化到 att_file_meta 表 + Caffeine 缓存</li>
 *   <li>{@link AttachmentRef#getStoragePath()} 存储 MinIO objectKey</li>
 * </ul>
 *
 * <h3>P0 安全治理（ATT-02/03/04 修复）</h3>
 * <ul>
 *   <li>store 时记录 tenantId/userId 归属</li>
 *   <li>getRef/readContent 增加归属校验，跨租户/跨用户访问返回 null（IDOR 防护）</li>
 * </ul>
 *
 * <h3>P2 持久化改造（ATT-10）</h3>
 * <ul>
 *   <li>移除 ConcurrentHashMap 内存索引，改用 DB + Caffeine</li>
 *   <li>应用重启后可从 DB 恢复附件元数据</li>
 *   <li>定时清理 MinIO 孤儿文件（DB 中已不存在的 objectKey）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    /** 附件对象键前缀 */
    private static final String OBJECT_KEY_PREFIX = "attachments/";

    private final MinioClient minioClient;
    private final AttFileMetaMapper fileMetaMapper;

    @Value("${aegis.minio.bucket:aegis-attachments}")
    private String bucket;

    /** Caffeine 本地缓存：fileId → AttachmentRef，5 分钟过期，最大 5000 条 */
    private final Cache<String, AttachmentRef> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(5000)
            .build();

    /**
     * 保存上传文件至 MinIO + 持久化元数据（P0 ATT-02 修复：记录归属）。
     *
     * @param fileContent  文件字节数组
     * @param originalName 原始文件名
     * @param contentType  MIME 类型
     * @param tenantId     上传者租户ID
     * @param userId       上传者用户ID
     * @return 附件引用（含 fileId 与归属信息）
     */
    public AttachmentRef store(byte[] fileContent, String originalName, String contentType,
                                Long tenantId, Long userId) {
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String ext = "";
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx > 0) {
            ext = originalName.substring(dotIdx);
        }
        // objectKey 增加租户/用户前缀分桶，实现 MinIO 层面隔离
        String tenantPart = tenantId != null ? String.valueOf(tenantId) : "0";
        String userPart = userId != null ? String.valueOf(userId) : "0";
        String objectKey = OBJECT_KEY_PREFIX + tenantPart + "/" + userPart + "/" + fileId + ext;

        // 1. 写入 MinIO
        try (InputStream inputStream = new ByteArrayInputStream(fileContent)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(contentType)
                    .stream(inputStream, fileContent.length, -1)
                    .build());
        } catch (Exception e) {
            log.error("写入 MinIO 失败: objectKey={}", objectKey, e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }

        // 2. 持久化元数据到 DB（try-catch 降级：DB 失败不影响 MinIO 已写入的文件）
        // 注：AttFileMeta 继承 TenantEntity，@Builder 不继承父类 tenantId 字段，改用 setter 设置
        AttFileMeta meta = new AttFileMeta();
        meta.setFileId(fileId);
        meta.setFilename(originalName);
        meta.setExt(ext);
        meta.setSizeBytes((long) fileContent.length);
        meta.setContentType(contentType);
        meta.setStorageKey(objectKey);
        meta.setMimeVerified(0);
        meta.setTenantId(tenantId);
        meta.setUserId(userId);
        try {
            fileMetaMapper.insert(meta);
        } catch (Exception e) {
            log.warn("写入 att_file_meta 失败（降级：MinIO 已写入）: fileId={}", fileId, e);
        }

        // 3. 构建 AttachmentRef + 写入 Caffeine 缓存
        AttachmentRef ref = AttachmentRef.builder()
                .fileId(fileId)
                .name(originalName)
                .sizeKB(Math.max(1, fileContent.length / 1024))
                .contentType(contentType)
                .storagePath(objectKey)
                .tenantId(tenantId)
                .userId(userId)
                .build();
        cache.put(fileId, ref);

        log.info("文件已存储: fileId={}, tenantId={}, userId={}, name={}, size={}KB, objectKey={}",
                fileId, tenantId, userId, originalName, ref.getSizeKB(), objectKey);
        return ref;
    }

    /**
     * 根据 fileId 获取附件引用（不校验归属，仅内部使用）。
     *
     * <p>查询顺序：Caffeine 缓存 → DB 查询 → 回填缓存。
     *
     * <p>注意：本方法不校验租户/用户归属，仅限信任的内部服务调用。
     * 外部请求须使用 {@link #getRef(String, Long, Long)}。
     */
    public AttachmentRef getRef(String fileId) {
        if (fileId == null) {
            return null;
        }
        // 1. 查 Caffeine 缓存
        AttachmentRef cached = cache.getIfPresent(fileId);
        if (cached != null) {
            return cached;
        }
        // 2. 查 DB
        try {
            AttFileMeta meta = fileMetaMapper.selectOne(
                    new LambdaQueryWrapper<AttFileMeta>()
                            .eq(AttFileMeta::getFileId, fileId)
                            .last("LIMIT 1"));
            if (meta == null) {
                log.debug("att_file_meta 未命中: fileId={}", fileId);
                return null;
            }
            AttachmentRef ref = toAttachmentRef(meta);
            cache.put(fileId, ref);
            return ref;
        } catch (Exception e) {
            log.warn("查询 att_file_meta 失败: fileId={}", fileId, e);
            return null;
        }
    }

    /**
     * 根据 fileId 获取附件引用（P0 ATT-04 修复：校验归属，IDOR 防护）。
     *
     * @param fileId   文件ID
     * @param tenantId 当前请求租户ID
     * @param userId   当前请求用户ID
     * @return 附件引用，不存在或跨租户/跨用户时返回 null
     */
    public AttachmentRef getRef(String fileId, Long tenantId, Long userId) {
        AttachmentRef ref = getRef(fileId);
        if (ref == null) {
            return null;
        }
        // 跨租户校验
        if (tenantId != null && ref.getTenantId() != null
                && !tenantId.equals(ref.getTenantId())) {
            log.warn("跨租户访问附件拦截: fileId={}, requestTenantId={}, ownerTenantId={}",
                    fileId, tenantId, ref.getTenantId());
            return null;
        }
        // 跨用户校验（仅当附件归属用户已记录时）
        if (userId != null && ref.getUserId() != null
                && !userId.equals(ref.getUserId())) {
            log.warn("跨用户访问附件拦截: fileId={}, requestUserId={}, ownerUserId={}",
                    fileId, userId, ref.getUserId());
            return null;
        }
        return ref;
    }

    /**
     * 读取附件文件内容（不校验归属，仅内部使用）。
     *
     * @param fileId 文件ID
     * @return 文件字节数组，不存在时返回 null
     */
    public byte[] readContent(String fileId) {
        AttachmentRef ref = getRef(fileId);
        if (ref == null || ref.getStoragePath() == null) {
            return null;
        }
        return readFromMinio(fileId, ref.getStoragePath());
    }

    /**
     * 读取附件文件内容（P0 ATT-04 修复：校验归属，IDOR 防护）。
     *
     * @param fileId   文件ID
     * @param tenantId 当前请求租户ID
     * @param userId   当前请求用户ID
     * @return 文件字节数组，不存在或跨租户/跨用户时返回 null
     */
    public byte[] readContent(String fileId, Long tenantId, Long userId) {
        AttachmentRef ref = getRef(fileId, tenantId, userId);
        if (ref == null || ref.getStoragePath() == null) {
            return null;
        }
        return readFromMinio(fileId, ref.getStoragePath());
    }

    /**
     * 删除附件文件（MinIO + DB + 缓存）。
     *
     * @param fileId 文件ID
     */
    public void remove(String fileId) {
        if (fileId == null) {
            return;
        }
        // 1. 拿到 storagePath（优先从缓存，不命中则查 DB）
        AttachmentRef ref = cache.getIfPresent(fileId);
        String storagePath = ref != null ? ref.getStoragePath() : null;
        if (storagePath == null) {
            try {
                AttFileMeta meta = fileMetaMapper.selectOne(
                        new LambdaQueryWrapper<AttFileMeta>()
                                .eq(AttFileMeta::getFileId, fileId)
                                .last("LIMIT 1"));
                if (meta != null) {
                    storagePath = meta.getStorageKey();
                }
            } catch (Exception e) {
                log.warn("查询 att_file_meta 失败（删除降级）: fileId={}", fileId, e);
            }
        }

        // 2. 删 MinIO
        if (storagePath != null) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket)
                        .object(storagePath)
                        .build());
            } catch (Exception e) {
                log.warn("删除 MinIO 文件失败: fileId={}, objectKey={}", fileId, storagePath, e);
            }
        }

        // 3. 删 DB（逻辑删除）
        try {
            fileMetaMapper.delete(
                    new LambdaQueryWrapper<AttFileMeta>()
                            .eq(AttFileMeta::getFileId, fileId));
        } catch (Exception e) {
            log.warn("删除 att_file_meta 失败: fileId={}", fileId, e);
        }

        // 4. 删缓存
        cache.invalidate(fileId);

        log.info("附件已删除: fileId={}", fileId);
    }

    /**
     * 从 MinIO 读取文件字节。
     */
    private byte[] readFromMinio(String fileId, String objectKey) {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("读取 MinIO 文件失败: fileId={}, objectKey={}", fileId, objectKey, e);
            return null;
        }
    }

    /**
     * AttFileMeta → AttachmentRef 转换。
     */
    private AttachmentRef toAttachmentRef(AttFileMeta meta) {
        return AttachmentRef.builder()
                .fileId(meta.getFileId())
                .name(meta.getFilename())
                .sizeKB(meta.getSizeBytes() != null
                        ? Math.max(1, meta.getSizeBytes().intValue() / 1024)
                        : 0)
                .contentType(meta.getContentType())
                .storagePath(meta.getStorageKey())
                .tenantId(meta.getTenantId())
                .userId(meta.getUserId())
                .build();
    }

    /**
     * 孤儿文件定时清理（每天凌晨 3 点）。
     *
     * <p>列出 MinIO bucket 内所有 objects，对每个 objectKey 查 att_file_meta：
     * DB 无记录 → 标记孤儿；孤儿超过 7 天 → 删 MinIO。
     *
     * <p>全 try-catch 降级：MinIO listObjects 可能很慢或失败，不阻断主流程。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOrphanFiles() {
        log.info("开始执行孤儿文件清理任务: bucket={}", bucket);
        int checked = 0;
        int orphan = 0;
        int deleted = 0;
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        try {
            var results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(OBJECT_KEY_PREFIX).build());
            for (Result<Item> result : results) {
                checked++;
                Item item;
                try {
                    item = result.get();
                } catch (Exception e) {
                    log.warn("解析 MinIO list result 失败，跳过", e);
                    continue;
                }
                String objectKey = item.objectName();
                if (objectKey == null || objectKey.isEmpty()) {
                    continue;
                }

                // 查 DB 是否有记录
                String fileId = null;
                try {
                    fileId = fileMetaMapper.findFileIdByStorageKey(objectKey);
                } catch (Exception e) {
                    log.warn("查询 att_file_meta 失败（跳过该 objectKey）: objectKey={}", objectKey, e);
                    continue;
                }

                if (fileId == null) {
                    orphan++;
                    // 检查对象最后修改时间，超过 7 天才删除
                    if (item.lastModified() != null) {
                        LocalDateTime lastModified = item.lastModified().toInstant()
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
                        if (lastModified.isBefore(sevenDaysAgo)) {
                            try {
                                minioClient.removeObject(RemoveObjectArgs.builder()
                                        .bucket(bucket)
                                        .object(objectKey)
                                        .build());
                                deleted++;
                                log.info("删除孤儿文件: objectKey={}, lastModified={}", objectKey, lastModified);
                            } catch (Exception e) {
                                log.warn("删除孤儿文件失败: objectKey={}", objectKey, e);
                            }
                        } else {
                            log.debug("孤儿文件未满 7 天，跳过删除: objectKey={}, lastModified={}", objectKey, lastModified);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 全降级：MinIO 不可用时静默退出
            log.error("孤儿文件清理任务执行异常（已降级跳过）", e);
            return;
        }

        log.info("孤儿文件清理完成: checked={}, orphan={}, deleted={}", checked, orphan, deleted);
    }
}
