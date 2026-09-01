package com.aegis.runtime.web;

import com.aegis.core.common.web.Result;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;
import com.aegis.runtime.service.document.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.aegis.core.common.web.ResultCode;

/**
 * 文件上传控制器。
 *
 * <p>提供对话附件上传接口，文件存储至本地临时目录后返回 {@link AttachmentRef}，
 * 调用方在对话请求中携带 fileId 即可关联附件。
 *
 * <h3>支持文件类型</h3>
 * PDF / Word / Excel / 图片 / Markdown / 纯文本
 *
 * <h3>限制</h3>
 * 单文件不超过 50MB；批量上传不超过 10 个文件
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/task")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileStorageService fileStorageService;

    /** 允许的文件扩展名（小写） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "md", "txt", "csv", "json", "xml", "html"
    );

    /** 允许的 MIME 类型（精确匹配，不使用 startsWith 前缀通配） */
    private static final Set<String> ALLOWED_MIME_EXACT = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp",
            "image/bmp",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "application/xml",
            "text/html",
            "application/octet-stream"
    );

    /** 最大文件大小：50MB */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    /** 批量上传文件数量上限 */
    private static final int MAX_BATCH_SIZE = 10;

    /**
     * 上传单个文件。
     *
     * @param file     上传的文件（multipart/form-data）
     * @param tenantId 租户ID（filter 注入）
     * @param userId   用户ID（filter 注入）
     * @return 附件引用
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Result<AttachmentRef>> upload(@RequestPart("file") FilePart file,
                                               @TenantId Long tenantId,
                                               @UserId Long userId) {
        String originalName = file.filename();
        String contentType = file.headers().getContentType() != null
                ? file.headers().getContentType().toString() : "application/octet-stream";

        log.info("文件上传: name={}, contentType={}, tenantId={}, userId={}",
                originalName, contentType, tenantId, userId);

        // 校验扩展名
        String ext = getExtension(originalName).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR,
                    "不支持的文件类型: " + ext));
        }

        // MIME 精确匹配（不再使用 startsWith 前缀通配）
        if (!isAllowedMime(contentType)) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR,
                    "不支持的文件类型: " + contentType));
        }

        // 使用 DataBufferUtils.join 合并 DataBuffer 并自动释放中间 buffer，
        // 合并后先校验大小再读取 byte[]，避免超大文件全部进入堆内存
        return DataBufferUtils.join(file.content())
                .map(dataBuffer -> {
                    // 合并后先校验大小，超限直接拒绝（未读取到堆）
                    if (dataBuffer.readableByteCount() > MAX_FILE_SIZE) {
                        DataBufferUtils.release(dataBuffer);
                        throw new IllegalArgumentException("文件大小超过 50MB 限制");
                    }
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    // 读取后释放 DataBuffer（Netty 直接内存）
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .map(bytes -> {
                    // magic bytes 校验，防止扩展名/Content-Type 伪造
                    String detectedType = detectFileType(bytes, ext);
                    if (detectedType == null) {
                        throw new IllegalArgumentException("文件实际类型与声明类型不匹配（magic bytes 校验失败）");
                    }
                    AttachmentRef ref = fileStorageService.store(bytes, originalName, contentType,
                            tenantId, userId);
                    // 不暴露 storagePath/tenantId/userId，创建副本返回
                    AttachmentRef responseRef = AttachmentRef.builder()
                            .fileId(ref.getFileId())
                            .name(ref.getName())
                            .sizeKB(ref.getSizeKB())
                            .contentType(ref.getContentType())
                            .build();
                    return Result.success(responseRef);
                })
                .onErrorResume(e -> {
                    log.error("文件上传失败: name={}", originalName, e);
                    return Mono.just(Result.fail(ResultCode.PARAM_ERROR,
                            e.getMessage() != null ? e.getMessage() : "上传失败"));
                });
    }

    /**
     * 批量上传多个文件。
     *
     * @param files    上传的文件列表
     * @param tenantId 租户ID（filter 注入）
     * @param userId   用户ID（filter 注入）
     * @return 附件引用列表
     */
    @PostMapping(value = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Result<List<AttachmentRef>>> uploadBatch(@RequestPart("files") List<FilePart> files,
                                                          @TenantId Long tenantId,
                                                          @UserId Long userId) {
        // 批量上传文件数量上限校验
        if (files == null || files.isEmpty()) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR, "文件列表为空"));
        }
        if (files.size() > MAX_BATCH_SIZE) {
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR,
                    "批量上传文件数量超过上限: " + MAX_BATCH_SIZE));
        }
        return Mono.fromCallable(() -> {
            List<AttachmentRef> refs = new ArrayList<>();
            for (FilePart fp : files) {
                String name = fp.filename();
                String ct = fp.headers().getContentType() != null
                        ? fp.headers().getContentType().toString() : "application/octet-stream";

                // 使用 DataBufferUtils.join 合并并自动释放中间 buffer
                DataBuffer joined = DataBufferUtils.join(fp.content()).block();
                if (joined == null) continue;
                try {
                    if (joined.readableByteCount() > MAX_FILE_SIZE) {
                        log.warn("批量上传文件超限: name={}, size={}", name, joined.readableByteCount());
                        continue;
                    }
                    byte[] bytes = new byte[joined.readableByteCount()];
                    joined.read(bytes);
                    // magic bytes 校验
                    String detectedExt = getExtension(name).toLowerCase();
                    if (detectFileType(bytes, detectedExt) == null) {
                        log.warn("批量上传文件 magic bytes 校验失败: name={}", name);
                        continue;
                    }
                    AttachmentRef ref = fileStorageService.store(bytes, name, ct, tenantId, userId);
                    // 不暴露 storagePath/tenantId/userId，创建副本返回
                    AttachmentRef responseRef = AttachmentRef.builder()
                            .fileId(ref.getFileId())
                            .name(ref.getName())
                            .sizeKB(ref.getSizeKB())
                            .contentType(ref.getContentType())
                            .build();
                    refs.add(responseRef);
                } finally {
                    // 确保 DataBuffer 释放
                    DataBufferUtils.release(joined);
                }
            }
            return Result.success(refs);
        }).onErrorResume(e -> {
            log.error("批量上传失败", e);
            return Mono.just(Result.fail(ResultCode.PARAM_ERROR,
                    e.getMessage() != null ? e.getMessage() : "批量上传失败"));
        });
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1) : "";
    }

    private boolean isAllowedMime(String contentType) {
        if (contentType == null || contentType.isEmpty()) return false;
        // 精确匹配，不再使用 startsWith 前缀通配
        return ALLOWED_MIME_EXACT.contains(contentType.toLowerCase());
    }

    /**
     * 基于 magic bytes（文件头魔数）探测文件真实类型。
     *
     * <p>读取文件前 N 字节与已知文件签名比对，防止扩展名/Content-Type 伪造。
     * 对于纯文本类文件（md/txt/csv/json/xml/html），无固定 magic bytes，
     * 仅校验内容不含二进制控制字符（简单启发式）。
     *
     * @param bytes 文件字节
     * @param ext   声明的扩展名（小写）
     * @return 探测到的类型描述，不匹配返回 null
     */
    private String detectFileType(byte[] bytes, String ext) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        // 读取前 8 字节用于 magic bytes 比对
        int len = Math.min(bytes.length, 8);

        switch (ext) {
            case "pdf":
                // PDF magic: %PDF (25 50 44 46)
                if (len >= 4 && bytes[0] == 0x25 && bytes[1] == 0x50
                        && bytes[2] == 0x44 && bytes[3] == 0x46) {
                    return "pdf";
                }
                return null;
            case "docx":
            case "xlsx":
            case "pptx":
                // OOXML (ZIP): PK\x03\x04 (50 4B 03 04)
                if (len >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B
                        && bytes[2] == 0x03 && bytes[3] == 0x04) {
                    return ext;
                }
                return null;
            case "doc":
                // OLE2 Compound Document: D0 CF 11 E0 A1 B1 1A E1
                if (len >= 8 && bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF
                        && bytes[2] == 0x11 && bytes[3] == (byte) 0xE0) {
                    return "doc";
                }
                return null;
            case "xls":
                // OLE2 Compound Document
                if (len >= 8 && bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF
                        && bytes[2] == 0x11 && bytes[3] == (byte) 0xE0) {
                    return "xls";
                }
                return null;
            case "ppt":
                if (len >= 8 && bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF
                        && bytes[2] == 0x11 && bytes[3] == (byte) 0xE0) {
                    return "ppt";
                }
                return null;
            case "png":
                // PNG: 89 50 4E 47 0D 0A 1A 0A
                if (len >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                        && bytes[2] == 0x4E && bytes[3] == 0x47) {
                    return "png";
                }
                return null;
            case "jpg":
            case "jpeg":
                // JPEG: FF D8 FF
                if (len >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8
                        && bytes[2] == (byte) 0xFF) {
                    return "jpeg";
                }
                return null;
            case "gif":
                // GIF: 47 49 46 38 (GIF8)
                if (len >= 4 && bytes[0] == 0x47 && bytes[1] == 0x49
                        && bytes[2] == 0x46 && bytes[3] == 0x38) {
                    return "gif";
                }
                return null;
            case "webp":
                // WebP: RIFF....WEBP
                if (len >= 4 && bytes[0] == 0x52 && bytes[1] == 0x49
                        && bytes[2] == 0x46 && bytes[3] == 0x46
                        && bytes.length >= 12 && bytes[8] == 0x57 && bytes[9] == 0x45
                        && bytes[10] == 0x42 && bytes[11] == 0x50) {
                    return "webp";
                }
                return null;
            case "bmp":
                // BMP: 42 4D (BM)
                if (len >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4D) {
                    return "bmp";
                }
                return null;
            case "md":
            case "txt":
            case "csv":
            case "json":
            case "xml":
            case "html":
                // 纯文本类无固定 magic bytes，校验前 512 字节不含 NUL 字节（二进制特征）
                int checkLen = Math.min(bytes.length, 512);
                for (int i = 0; i < checkLen; i++) {
                    if (bytes[i] == 0x00) {
                        return null; // 含 NUL 字节，可能是二进制文件伪装
                    }
                }
                return ext;
            default:
                return null;
        }
    }

    /**
     * 下载文件。
     *
     * <p>调用 {@link FileStorageService#getRef(String, Long, Long)} 校验归属，
     * 跨租户/跨用户访问返回 404（不暴露存在性）。
     *
     * @param fileId   文件ID
     * @param tenantId 租户ID（filter 注入）
     * @param userId   用户ID（filter 注入）
     * @return 文件内容
     */
    @GetMapping(value = "/download/{fileId}")
    public Mono<ResponseEntity<Resource>> download(@PathVariable String fileId,
                                                    @TenantId Long tenantId,
                                                    @UserId Long userId) {
        return Mono.<ResponseEntity<Resource>>defer(() -> {
            // 校验归属（跨租户/跨用户返回 404，不暴露存在性）
            AttachmentRef ref = fileStorageService.getRef(fileId, tenantId, userId);
            if (ref == null) {
                log.warn("附件下载拒绝（不存在或越权）: fileId={}, tenantId={}, userId={}",
                        fileId, tenantId, userId);
                return Mono.just(ResponseEntity.<Resource>notFound().build());
            }
            byte[] content = fileStorageService.readContent(fileId, tenantId, userId);
            if (content == null) {
                return Mono.just(ResponseEntity.<Resource>notFound().build());
            }

            String contentType = ref.getContentType() != null
                    ? ref.getContentType()
                    : MediaType.APPLICATION_OCTET_STREAM_VALUE;

            String encodedFileName;
            try {
                encodedFileName = URLEncoder.encode(ref.getName(), StandardCharsets.UTF_8)
                        .replace("+", "%20");
            } catch (Exception e) {
                encodedFileName = ref.getName();
            }

            return Mono.just(ResponseEntity.<Resource>ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName)
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(content.length)
                    .body(new ByteArrayResource(content)));
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> {
            log.error("下载文件失败: fileId={}", fileId, e);
            return Mono.just(ResponseEntity.<Resource>internalServerError().build());
        });
    }
}
