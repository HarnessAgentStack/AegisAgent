package com.aegis.admin.web.resource;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.resource.DocumentPipelineService;
import com.aegis.admin.service.resource.DocumentProgressService;
import com.aegis.admin.service.resource.DocumentUploadService;
import com.aegis.admin.service.resource.KnowledgeBaseService;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.resource.KbChunkVO;
import com.aegis.core.dto.resource.KbDocumentVO;
import com.aegis.core.dto.resource.KnowledgeBaseCreateRequest;
import com.aegis.core.dto.resource.KnowledgeBaseUpdateRequest;
import com.aegis.core.dto.resource.KnowledgeBaseVO;
import com.aegis.core.dto.resource.ProcessProgressVO;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.core.security.ResourceOwner;
import com.aegis.core.security.ResourcePermission;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理 Controller。
 *
 * <p>提供知识库 CRUD、文档上传/删除/重处理、SSE 进度推送、切片预览等完整能力。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/resource/kb")
@RequiredArgsConstructor
public class KbController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentUploadService documentUploadService;
    private final DocumentPipelineService documentPipelineService;
    private final DocumentProgressService progressService;

    // ============ 知识库管理 ============

    @PostMapping
    @Auditable(operation = "CREATE_KB", resourceType = "KNOWLEDGE_BASE")
    public Result<Long> create(@Valid @RequestBody KnowledgeBaseCreateRequest req,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        if (req.getAuthorUserId() == null) req.setAuthorUserId(userId);
        Long id = knowledgeBaseService.create(tenantId, req);
        return Result.success(id);
    }

    @PutMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPDATE_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> updateConfig(@PathVariable Long id, @Valid @RequestBody KnowledgeBaseUpdateRequest req,
                                      @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                      @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.updateConfig(tenantId, id, req, userId);
        return Result.success(null);
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> detail(@PathVariable Long id,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(knowledgeBaseService.getDetail(tenantId, id));
    }

    @GetMapping("/page")
    public Result<Page<KnowledgeBaseVO>> page(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                             @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                             @RequestParam(required = false, defaultValue = "mine") String scope,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        TenantContextHolder.bind(tenantId);
        return Result.success(knowledgeBaseService.page(tenantId, userId, scope, keyword, page, size));
    }

    @DeleteMapping("/{id}")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.DELETE, resourceIdParam = "id")
    @Auditable(operation = "DELETE_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> delete(@PathVariable Long id,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.delete(tenantId, id, userId);
        return Result.success(null);
    }

    @PostMapping("/{id}/submit-review")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "SUBMIT_KB_REVIEW", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> submitForReview(@PathVariable Long id,
                                         @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.submitForReview(tenantId, id, userId);
        return Result.success(null);
    }

    @PostMapping("/{id}/publish")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.PUBLISH, resourceIdParam = "id")
    @Auditable(operation = "PUBLISH_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> publish(@PathVariable Long id,
                                 @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.publish(tenantId, id, userId);
        return Result.success(null);
    }

    @PostMapping("/{id}/take-down")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.MANAGE, resourceIdParam = "id")
    @Auditable(operation = "TAKE_DOWN_KB", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> takeDown(@PathVariable Long id,
                                  @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        knowledgeBaseService.takeDown(tenantId, id, userId);
        return Result.success(null);
    }

    // ============ 文档上传 ============

    @PostMapping("/{id}/upload/apply")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "APPLY_KB_UPLOAD", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Map<String, Object>> applyUpload(@PathVariable Long id,
                                                    @RequestParam String fileName,
                                                    @RequestParam(required = false) String contentType,
                                                    @RequestParam long fileSize,
                                                    @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                                    @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(documentUploadService.applyUpload(tenantId, id, fileName, contentType, fileSize, userId));
    }

    @PostMapping("/{id}/upload/notify")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "NOTIFY_KB_UPLOAD", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<KbDocumentVO> notifyUploaded(@PathVariable Long id,
                                              @RequestParam String objectKey,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(documentUploadService.notifyUploaded(tenantId, id, objectKey));
    }

    /**
     * 直接上传文件（服务端代理存储）。
     */
    @PostMapping(value = "/{id}/upload/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.EDIT, resourceIdParam = "id")
    @Auditable(operation = "UPLOAD_KB_FILE", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Mono<Result<KbDocumentVO>> uploadFile(@PathVariable Long id,
                                            @RequestPart("file") FilePart file,
                                            @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                            @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        final Long tid = tenantId;
        final Long uid = userId;

        return DataBufferUtils.join(file.content())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .doOnSubscribe(s -> TenantContextHolder.bind(tid))
                .map(bytes -> {
                    log.info("处理上传文件: kbId={}, fileName={}, size={}, tenantId={}", id, file.filename(), bytes.length, tid);
                    return documentUploadService.uploadFromBytes(tid, id, file, bytes, uid);
                })
                .map(Result::success)
                .onErrorResume(e -> {
                    log.error("文件上传失败: kbId={}, fileName={}", id, file.filename(), e);
                    return Mono.just(Result.fail(ResultCode.INTERNAL_ERROR, "文件上传失败: " + e.getMessage()));
                });
    }

    // ============ 文档管理 ============

    /**
     * 分页查询文档列表（支持状态/文件类型/关键词筛选）。
     */
    @GetMapping("/{id}/documents")
    public Result<Page<KbDocumentVO>> listDocuments(@PathVariable Long id,
                                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String fileType,
                                                   @RequestParam(required = false) String keyword) {
        TenantContextHolder.bind(tenantId);
        return Result.success(documentUploadService.listDocuments(tenantId, id, page, size, status, fileType, keyword));
    }

    /**
     * 删除文档。
     */
    @DeleteMapping("/{id}/documents/{docId}")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.MANAGE, resourceIdParam = "id")
    @Auditable(operation = "DELETE_KB_DOCUMENT", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> deleteDocument(@PathVariable Long id, @PathVariable Long docId,
                                        @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                        @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        documentUploadService.deleteDocument(tenantId, id, docId, userId);
        return Result.success(null);
    }

    /**
     * 重新处理文档。
     */
    @PostMapping("/{id}/documents/{docId}/reprocess")
    @ResourceOwner(resourceType = ResourceType.KNOWLEDGE_BASE, permission = ResourcePermission.MANAGE, resourceIdParam = "id")
    @Auditable(operation = "REPROCESS_KB_DOCUMENT", resourceType = "KNOWLEDGE_BASE", resourceIdParam = "id")
    public Result<Void> reprocessDocument(@PathVariable Long id, @PathVariable Long docId,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                          @RequestHeader(value = "X-User-Id", required = false) Long userId) {
        TenantContextHolder.bind(tenantId);
        // 写操作权限校验（DocumentUploadService 内部校验）
        documentUploadService.checkWritePermission(tenantId, id, userId);
        final Long tid = tenantId;
        log.info("重新处理文档请求: kbId={}, docId={}, tenantId={}, userId={}", id, docId, tid, userId);
        documentPipelineService.process(tid, id, docId);
        return Result.success(null);
    }

    // ============ 切片预览 ============

    /**
     * 查询文档切片列表。
     */
    @GetMapping("/{id}/documents/{docId}/chunks")
    public Result<List<KbChunkVO>> listChunks(@PathVariable Long id,
                                              @PathVariable Long docId,
                                              @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(documentUploadService.listChunks(tenantId, docId));
    }

    // ============ 处理进度 ============

    /**
     * 订阅文档处理进度（SSE）。
     *
     * <p>建立 SSE 长连接，实时接收文档处理进度事件，每个事件为 JSON 格式，
     * 包含 step、status、progressPercent 等字段。
     * 新连接会立即推送当前所有步骤的最新状态快照。
     */
    @GetMapping(value = "/documents/{docId}/progress/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> subscribeProgress(@PathVariable Long docId,
                                          @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        log.info("SSE订阅文档处理进度: docId={}, tenantId={}", docId, tenantId);
        return progressService.subscribeProgress(docId);
    }

    /**
     * 查询文档当前处理进度快照（非SSE，用于首次加载或断线恢复）。
     */
    @GetMapping("/documents/{docId}/progress")
    public Result<List<ProcessProgressVO>> getProgress(@PathVariable Long docId,
                                                       @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        TenantContextHolder.bind(tenantId);
        return Result.success(progressService.loadAllProgressSnapshot(docId));
    }
}
