package com.aegis.admin.service.resource;

import com.aegis.dal.mapper.resource.KbDocumentChunkMapper;
import com.aegis.dal.mapper.resource.KbDocumentMapper;
import com.aegis.core.constant.KbConstants;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.KbDocument;
import com.aegis.core.domain.resource.KbDocumentChunk;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.dto.resource.KbChunkVO;
import com.aegis.core.dto.resource.KbDocumentVO;
import com.aegis.core.enums.resource.DocumentStatus;
import com.aegis.core.enums.security.PermissionLevel;
import com.aegis.core.spi.IObjectStorage;
import com.aegis.core.spi.IVectorStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库文档上传领域服务。
 *
 * <p>编排文档上传（预签名 URL 直传 MinIO）→ 上传回调 → 文档列表查询 → 文档删除的完整链路。
 * 支持文件大小校验、文档列表筛选、切片预览查询。
 *
 * @author wang.zhen  
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadService {

    private final IObjectStorage objectStorage;
    private final IVectorStore vectorStore;
    private final KbDocumentMapper kbDocumentMapper;
    private final KbDocumentChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentPipelineService documentPipelineService;
    private final DocumentProgressService progressService;

    /**
     * 申请上传（生成预签名 URL）。
     */
    public Map<String, Object> applyUpload(Long tenantId, Long kbId, String fileName,
                                            String contentType, long fileSize, Long userId) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        // 写操作权限校验
        checkWritePermission(kb, userId);
        if (fileName == null || fileName.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件名不能为空");
        }
        if (fileSize <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件大小必须大于0");
        }
        if (fileSize > KbConstants.MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "文件大小超过限制（最大 " + (KbConstants.MAX_FILE_SIZE_BYTES / 1024 / 1024) + "MB）");
        }

        String objectKey = KbConstants.OSS_PATH_PREFIX + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "") + "_" + fileName;
        String presignedUrl = objectStorage.presignedUploadUrl(tenantId, objectKey,
                contentType, KbConstants.PRESIGN_EXPIRE_MINUTES, TimeUnit.MINUTES);

        Map<String, Object> result = new HashMap<>(3);
        result.put("objectKey", objectKey);
        result.put("uploadUrl", presignedUrl);
        result.put("expire", KbConstants.PRESIGN_EXPIRE_MINUTES * 60);
        log.info("文档上传URL已生成: tenantId={}, kbId={}, objectKey={}, fileSize={}", tenantId, kbId, objectKey, fileSize);
        return result;
    }

    /**
     * 上传完成回调。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbDocumentVO notifyUploaded(Long tenantId, Long kbId, String objectKey) {
        return notifyUploaded(tenantId, kbId, objectKey, 0L, null);
    }

    /**
     * 上传完成回调（含文件大小）。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbDocumentVO notifyUploaded(Long tenantId, Long kbId, String objectKey, long fileSize, Long userId) {
        TenantContextHolder.bind(tenantId);
        KnowledgeBase kb = requireKb(kbId, tenantId);
        // 写操作权限校验
        checkWritePermission(kb, userId);
        if (objectKey == null || objectKey.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "对象键不能为空");
        }

        String fileName = extractFileName(objectKey);
        String fileType = extractFileExtension(fileName);

        KbDocument doc = KbDocument.builder()
                .kbId(kbId)
                .fileName(fileName)
                .fileType(fileType)
                .fileSize(fileSize)
                .ossKey(objectKey)
                .status(DocumentStatus.PENDING)
                .chunkCount(0)
                .permissionLevel(PermissionLevel.ALL)
                .uploadedTime(java.time.LocalDateTime.now())
                .build();
        doc.setTenantId(tenantId);
        kbDocumentMapper.insert(doc);

        log.info("文档上传回调: tenantId={}, kbId={}, docId={}, ossKey={}",
                tenantId, kbId, doc.getId(), objectKey);

        final Long docId = doc.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    documentPipelineService.process(tenantId, kbId, docId);
                }
            });
        } else {
            documentPipelineService.process(tenantId, kbId, docId);
        }
        return toVO(doc);
    }

    /**
     * 直接上传文件（便捷接口，服务端代理上传）。
     */
    @Transactional(rollbackFor = Exception.class)
    public KbDocumentVO uploadFromBytes(Long tenantId, Long kbId, FilePart file, byte[] bytes, Long userId) {
        TenantContextHolder.bind(tenantId);
        KnowledgeBase kb = requireKb(kbId, tenantId);
        // 写操作权限校验
        checkWritePermission(kb, userId);
        if (file == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件不能为空");
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "上传文件内容为空");
        }
        if (bytes.length > KbConstants.MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "文件大小超过限制（最大 " + (KbConstants.MAX_FILE_SIZE_BYTES / 1024 / 1024) + "MB）");
        }

        String fileName = file.filename();
        if (fileName == null || fileName.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件名不能为空");
        }

        String contentType = file.headers().getContentType() != null
                ? file.headers().getContentType().toString()
                : "application/octet-stream";

        String objectKey = KbConstants.OSS_PATH_PREFIX + kbId + "/"
                + UUID.randomUUID().toString().replace("-", "") + "_" + fileName;
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            objectStorage.upload(tenantId, objectKey, is, contentType);
        } catch (Exception e) {
            log.error("文件上传失败: tenantId={}, kbId={}, error={}", tenantId, kbId, e.getMessage(), e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "文件上传失败: " + e.getMessage());
        }

        log.info("文件直接上传完成: tenantId={}, kbId={}, objectKey={}, size={}",
                tenantId, kbId, objectKey, bytes.length);
        return notifyUploaded(tenantId, kbId, objectKey, bytes.length, userId);
    }

    /**
     * 分页查询知识库文档列表（支持筛选）。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @param page     页码
     * @param size     每页条数
     * @param status   状态筛选（可选）
     * @param fileType 文件类型筛选（可选）
     * @param keyword  文件名关键词（可选）
     */
    public Page<KbDocumentVO> listDocuments(Long tenantId, Long kbId, int page, int size,
                                            String status, String fileType, String keyword) {
        requireKb(kbId, tenantId);
        Page<KbDocument> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .eq(KbDocument::getKbId, kbId);

        if (status != null && !status.isEmpty()) {
            wrapper.eq(KbDocument::getStatus, DocumentStatus.valueOf(status));
        }
        if (fileType != null && !fileType.isEmpty()) {
            wrapper.eq(KbDocument::getFileType, fileType.toUpperCase());
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.like(KbDocument::getFileName, keyword);
        }

        wrapper.orderByDesc(KbDocument::getUploadedTime);
        Page<KbDocument> entityPage = kbDocumentMapper.selectPage(pageObj, wrapper);
        return convertPage(entityPage, this::toVO, page, size);
    }

    /**
     * 查询文档切片列表。
     *
     * @param tenantId 租户ID
     * @param docId    文档ID
     * @return 切片列表
     */
    public List<KbChunkVO> listChunks(Long tenantId, Long docId) {
        KbDocument doc = kbDocumentMapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在: " + docId);
        }
        if (tenantId != null && !tenantId.equals(doc.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该文档");
        }

        List<KbDocumentChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KbDocumentChunk>()
                        .eq(KbDocumentChunk::getDocId, docId)
                        .orderByAsc(KbDocumentChunk::getChunkIndex));

        return chunks.stream().map(this::toChunkVO).collect(Collectors.toList());
    }

    /**
     * 删除文档。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long tenantId, Long kbId, Long docId, Long userId) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        // 写操作权限校验
        checkWritePermission(kb, userId);
        KbDocument doc = kbDocumentMapper.selectById(docId);
        if (doc == null || !kbId.equals(doc.getKbId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在: " + docId);
        }
        if (tenantId != null && !tenantId.equals(doc.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该文档");
        }

        // 删除切片数据
        chunkMapper.delete(new LambdaQueryWrapper<KbDocumentChunk>()
                .eq(KbDocumentChunk::getDocId, docId));

        // 删除进度数据
        progressService.cleanProgress(docId);

        // 删除 MinIO 文件
        try {
            objectStorage.delete(tenantId, doc.getOssKey());
        } catch (Exception e) {
            log.warn("删除对象存储文件失败（继续）: docId={}, error={}", docId, e.getMessage());
        }

        // 删除 Milvus 向量
        if (doc.getChunkCount() != null && doc.getChunkCount() > 0) {
            String collection = KbConstants.VECTOR_COLLECTION_PREFIX + kbId;
            List<String> ids = new ArrayList<>(doc.getChunkCount());
            for (int i = 0; i < doc.getChunkCount(); i++) {
                ids.add(docId + "_" + i);
            }
            try {
                vectorStore.delete(tenantId, collection, ids);
            } catch (Exception e) {
                log.warn("删除向量数据失败（继续）: docId={}, error={}", docId, e.getMessage());
            }
        }

        // 删除 DB 记录
        kbDocumentMapper.deleteById(docId);

        // 更新知识库文档计数
        try {
            if (kb != null && kb.getDocCount() != null && kb.getDocCount() > 0) {
                knowledgeBaseMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<KnowledgeBase>()
                        .eq(KnowledgeBase::getId, kbId)
                        .set(KnowledgeBase::getDocCount, kb.getDocCount() - 1));
            }
        } catch (Exception e) {
            log.warn("更新知识库文档计数失败: kbId={}", kbId, e);
        }

        log.info("文档已删除: tenantId={}, kbId={}, docId={}", tenantId, kbId, docId);
    }

    // ============ 内部方法 ============

    private KbDocumentVO toVO(KbDocument doc) {
        KbDocumentVO vo = new KbDocumentVO();
        BeanUtils.copyProperties(doc, vo);
        return vo;
    }

    private KbChunkVO toChunkVO(KbDocumentChunk chunk) {
        return KbChunkVO.builder()
                .id(chunk.getId())
                .docId(chunk.getDocId())
                .chunkIndex(chunk.getChunkIndex())
                .content(chunk.getContent())
                .tokenCount(chunk.getTokenCount())
                .charCount(chunk.getCharCount())
                .metadata(chunk.getMetadata())
                .build();
    }

    private <E, V> Page<V> convertPage(Page<E> entityPage, Function<E, V> converter, int page, int size) {
        Page<V> voPage = new Page<>(page, size);
        voPage.setTotal(entityPage.getTotal());
        voPage.setRecords(entityPage.getRecords().stream().map(converter).collect(Collectors.toList()));
        return voPage;
    }

    private KnowledgeBase requireKb(Long kbId, Long tenantId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在: " + kbId);
        }
        if (tenantId != null && !tenantId.equals(kb.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该知识库");
        }
        return kb;
    }

    /**
     * 公开的写操作权限校验入口。
     *
     * @param tenantId 租户ID
     * @param kbId     知识库ID
     * @param userId   当前用户ID
     */
    public void checkWritePermission(Long tenantId, Long kbId, Long userId) {
        KnowledgeBase kb = requireKb(kbId, tenantId);
        checkWritePermission(kb, userId);
    }

    /**
     * D2/D3: 校验知识库写操作权限（文档上传/删除/重处理等）。
     *
     * <p>仅知识库作者可执行文档写操作。
     * 管理员也不允许越权操作他人知识库的文档。
     * 读取类操作（listDocuments/listChunks）不调用此方法，所有租户内用户可读。
     *
     * @param kb     知识库实体
     * @param userId 当前用户ID
     * @throws BusinessException 无权限时抛出 FORBIDDEN
     */
    private void checkWritePermission(KnowledgeBase kb, Long userId) {
        if (userId != null && userId.equals(kb.getAuthorUserId())) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN, "无权限操作该知识库的文档（仅创建者可操作）");
    }

    private String extractFileName(String objectKey) {
        int lastSlash = objectKey.lastIndexOf('/');
        String tail = lastSlash >= 0 ? objectKey.substring(lastSlash + 1) : objectKey;
        int underscoreIdx = tail.indexOf('_');
        return underscoreIdx >= 0 ? tail.substring(underscoreIdx + 1) : tail;
    }

    private String extractFileExtension(String fileName) {
        if (fileName == null) return null;
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) return null;
        return fileName.substring(dotIdx + 1).toUpperCase();
    }
}
