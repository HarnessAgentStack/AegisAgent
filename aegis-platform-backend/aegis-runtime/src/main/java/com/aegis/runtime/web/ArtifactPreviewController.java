package com.aegis.runtime.web;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.session.AegisArtifact;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;
import com.aegis.runtime.service.artifact.AegisArtifactService;
import com.aegis.runtime.service.artifact.ArtifactPreviewService;
import com.aegis.runtime.service.artifact.ArtifactPreviewService.PreviewPayload;
import com.aegis.runtime.service.artifact.ShareLinkService;
import com.aegis.runtime.service.artifact.ShareLinkService.ShareMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 产物预览与分享控制器，提供产物内嵌预览与分享链接管理能力。
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/artifact")
@RequiredArgsConstructor
public class ArtifactPreviewController {

    private final AegisArtifactService artifactService;
    private final ArtifactPreviewService previewService;
    private final ShareLinkService shareLinkService;

    /**
     * 获取产物预览元数据。
     */
    @GetMapping("/{artifactId}/preview")
    public Mono<Result<Map<String, Object>>> preview(
            @PathVariable String artifactId,
            @RequestHeader(value = "X-Share-Token", required = false) String shareToken,
            @TenantId Long tenantId,
            @UserId Long userId) {
        return Mono.<Result<Map<String, Object>>>fromCallable(() -> {
            // 分享令牌优先：匿名访问走令牌校验
            if (shareToken != null && !shareToken.isBlank()) {
                ShareMeta meta = shareLinkService.verifyToken(shareToken);
                if (meta == null || !artifactId.equals(meta.artifactId())) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "分享令牌无效或已过期");
                }
                return Result.success(previewService.buildPreviewMeta(artifactId, tenantId, null, shareToken));
            }
            return Result.success(previewService.buildPreviewMeta(artifactId, tenantId, userId, null));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式读取产物原始字节（供 iframe / 下载使用）。
     */
    @GetMapping("/{artifactId}/content")
    public Mono<ResponseEntity<InputStreamResource>> content(
            @PathVariable String artifactId,
            @RequestParam(value = "mode", required = false) String mode,
            @RequestHeader(value = "X-Share-Token", required = false) String shareToken,
            @TenantId Long tenantId,
            @UserId Long userId) {
        return Mono.fromCallable(() -> {
            PreviewPayload payload;
            if (shareToken != null && !shareToken.isBlank()) {
                ShareMeta meta = shareLinkService.verifyToken(shareToken);
                if (meta == null || !artifactId.equals(meta.artifactId())) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "分享令牌无效或已过期");
                }
                payload = previewService.buildPreviewPayload(artifactId, tenantId, null, shareToken);
            } else {
                payload = previewService.buildPreviewPayload(artifactId, tenantId, userId, null);
            }
            AegisArtifact artifact = artifactService.findByArtifactId(artifactId);
            if (artifact == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "产物不存在");
            }
            InputStream in = previewService.loadBytes(artifact);
            String fileName = artifact.getName() == null ? "artifact" : artifact.getName();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(payload.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + encode(fileName) + "\"")
                    .header("X-Frame-Options", "ALLOW-FROM " + sameOriginOnly())
                    .contentLength(artifact.getSize() != null ? artifact.getSize() : -1L)
                    .body(new InputStreamResource(in));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 下载产物（附件形式）。
     */
    @GetMapping("/{artifactId}/download")
    public Mono<ResponseEntity<InputStreamResource>> download(
            @PathVariable String artifactId,
            @RequestHeader(value = "X-Share-Token", required = false) String shareToken,
            @TenantId Long tenantId,
            @UserId Long userId) {
        return Mono.fromCallable(() -> {
            PreviewPayload payload;
            if (shareToken != null && !shareToken.isBlank()) {
                ShareMeta meta = shareLinkService.verifyToken(shareToken);
                if (meta == null || !artifactId.equals(meta.artifactId())) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "分享令牌无效或已过期");
                }
                payload = previewService.buildPreviewPayload(artifactId, tenantId, null, shareToken);
            } else {
                payload = previewService.buildPreviewPayload(artifactId, tenantId, userId, null);
            }
            AegisArtifact artifact = artifactService.findByArtifactId(artifactId);
            if (artifact == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "产物不存在");
            }
            InputStream in = previewService.loadBytes(artifact);
            String fileName = artifact.getName() == null ? "artifact" : artifact.getName();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(payload.mimeType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + encode(fileName) + "\"")
                    .contentLength(artifact.getSize() != null ? artifact.getSize() : -1L)
                    .body(new InputStreamResource(in));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 创建分享链接。
     */
    @PostMapping("/{artifactId}/share")
    public Mono<Result<Map<String, Object>>> createShare(
            @PathVariable String artifactId,
            @RequestBody(required = false) ShareRequest request,
            @TenantId Long tenantId,
            @UserId Long userId) {
        return Mono.<Result<Map<String, Object>>>fromCallable(() -> {
            Integer expireMinutes = request != null ? request.getExpireMinutes() : null;
            String note = request != null ? request.getNote() : null;
            ShareLinkService.ShareResult result =
                    shareLinkService.createShareLink(artifactId, userId, expireMinutes, note);
            Map<String, Object> map = new HashMap<>();
            map.put("token", result.token());
            map.put("shareUrl", result.shareUrl());
            map.put("expireAt", result.expireAt().toString());
            map.put("note", result.note());
            map.put("accessCount", result.accessCount());
            return Result.success(map);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 列出某产物的分享链接。
     */
    @GetMapping("/{artifactId}/share/list")
    public Mono<Result<List<Map<String, Object>>>> listShares(
            @PathVariable String artifactId,
            @TenantId Long tenantId,
            @UserId Long userId) {
        return Mono.<Result<List<Map<String, Object>>>>fromCallable(() -> {
            List<ShareLinkService.ShareResult> shares = shareLinkService.listShares(artifactId, userId);
            List<Map<String, Object>> result = new java.util.ArrayList<>();
            for (ShareLinkService.ShareResult s : shares) {
                Map<String, Object> map = new HashMap<>();
                map.put("token", s.token());
                map.put("shareUrl", s.shareUrl());
                map.put("expireAt", s.expireAt().toString());
                map.put("note", s.note());
                map.put("accessCount", s.accessCount());
                result.add(map);
            }
            return Result.success(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 吊销分享链接。
     */
    @DeleteMapping("/{artifactId}/share/{token}")
    public Mono<Result<Void>> revokeShare(
            @PathVariable String artifactId,
            @PathVariable String token,
            @TenantId Long tenantId,
            @UserId Long userId) {
        return Mono.<Result<Void>>fromCallable(() -> {
            shareLinkService.revokeShareLink(artifactId, userId, token);
            return Result.success(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ========== 辅助 ==========

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * iframe X-Frame-Options ALLOW-FROM 的占位实现。
     * 生产可替换为实际来源域名白名单。
     */
    private String sameOriginOnly() {
        // 安全默认值：禁止外部嵌入
        return "'self'";
    }

    // ========== DTO ==========

    @lombok.Data
    public static class ShareRequest {
        /** 有效期（分钟），默认 7 天，最大 30 天 */
        private Integer expireMinutes;
        /** 分享备注 */
        private String note;
    }
}
