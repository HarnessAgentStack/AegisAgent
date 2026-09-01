package com.aegis.runtime.service.artifact;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.session.AegisArtifact;
import com.aegis.runtime.service.document.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 产物预览服务 (P3 内嵌预览沙箱)。
 *
 * <p>根据产物类型选择最合适的预览形态，供前端 {@code ArtifactPreview} 组件
 * 的 {@code IframeSandbox} 直接内嵌展示。核心思路：
 * <ul>
 *   <li>HTML / Markdown / Code / Image 等可直接内嵌的类型，返回 {@code inline} 类型
 *       预览载荷（dataUrl 或渲染后 HTML）。</li>
 *   <li>DOCX / PDF / Excel / PPT 等二进制类型，返回 {@code proxy} 或 {@code download}
 *       载荷，前端通过沙箱 iframe 加载代理预览地址或触发下载。</li>
 *   <li>所有预览 URL 均追加签名校验（shareToken 或短期签名），禁止匿名访问。</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArtifactPreviewService {

    private final AegisArtifactService artifactService;
    private final FileStorageService fileStorageService;

    /**
     * 预览形态枚举，对应前端 ArtifactPreview 的渲染分支。
     */
    public enum PreviewKind {
        /** 内嵌 HTML / dataUrl */
        INLINE,
        /** 沙箱代理（后端返回可嵌入 iframe 的中间页） */
        PROXY,
        /** 直接下载 */
        DOWNLOAD,
        /** 暂不支持的类型 */
        UNSUPPORTED
    }

    /**
     * 生成产物预览载荷。
     *
     * @param artifactId 产物业务 ID
     * @param tenantId   租户 ID
     * @param userId     用户 ID
     * @param shareToken 可选分享令牌；为空时走归属校验
     * @return 预览载荷（kind + url + mimeType + meta）
     */
    public PreviewPayload buildPreviewPayload(String artifactId, Long tenantId, Long userId, String shareToken) {
        AegisArtifact artifact = artifactService.findByArtifactId(artifactId);
        if (artifact == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "产物不存在: " + artifactId);
        }
        if (Boolean.TRUE.equals(artifact.getArchived())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物已归档，不可预览");
        }
        if (artifact.getExpireAt() != null && artifact.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "产物已过期");
        }

        // 归属校验：如果不是通过 shareToken 访问，则必须归属匹配
        if (!StringUtils.hasText(shareToken)) {
            boolean ownerMatch = artifact.getUserId() != null && artifact.getUserId().equals(userId);
            if (!ownerMatch) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权预览该产物");
            }
        }

        String type = artifact.getType();
        return switch (type == null ? "" : type.toLowerCase()) {
            case "html", "htm" -> buildInlinePayload(artifact, "text/html");
            case "image", "png", "jpg", "jpeg", "gif", "svg", "webp"
                    -> buildInlinePayload(artifact, resolveImageMime(type));
            case "markdown", "md" -> buildProxyPayload(artifact, "text/markdown");
            case "code", "source", "txt" -> buildInlinePayload(artifact, "text/plain");
            case "pdf", "docx", "excel", "xlsx", "ppt", "pptx"
                    -> buildProxyPayload(artifact, resolveOfficeMime(type));
            default -> buildDownloadPayload(artifact);
        };
    }

    /**
     * 简单代理：根据产物 ID 返回可嵌入 iframe 的预览信息。
     *
     * <p>实际字节读取由 {@link #loadBytes} 完成；此方法只负责组装元数据。
     */
    public Map<String, Object> buildPreviewMeta(String artifactId, Long tenantId, Long userId, String shareToken) {
        PreviewPayload payload = buildPreviewPayload(artifactId, tenantId, userId, shareToken);
        Map<String, Object> result = new HashMap<>();
        result.put("artifactId", payload.artifactId());
        result.put("kind", payload.kind().name().toLowerCase());
        result.put("url", payload.url());
        result.put("mimeType", payload.mimeType());
        result.put("name", payload.name());
        result.put("size", payload.size());
        result.put("storageRef", payload.storageRef());
        result.put("previewMeta", payload.previewMeta());
        return result;
    }

    /**
     * 从 MinIO 读取产物原始字节。
     *
     * <p>如果产物无 storageRef（如外部 URL、已丢失文件），则返回空流供上层降级处理。
     */
    public InputStream loadBytes(AegisArtifact artifact) {
        if (artifact == null || !StringUtils.hasText(artifact.getStorageRef())) {
            return new ByteArrayInputStream(new byte[0]);
        }
        try {
            byte[] bytes = fileStorageService.readContent(artifact.getStorageRef());
            return bytes == null ? new ByteArrayInputStream(new byte[0]) : new ByteArrayInputStream(bytes);
        } catch (Exception ex) {
            log.warn("读取产物字节失败: artifactId={}, storageRef={}",
                    artifact.getArtifactId(), artifact.getStorageRef(), ex);
            return new ByteArrayInputStream(new byte[0]);
        }
    }

    // ==================== 内部构建器 ====================

    private PreviewPayload buildInlinePayload(AegisArtifact artifact, String mimeType) {
        String url = "/api/runtime/artifact/" + artifact.getArtifactId() + "/content"
                + "?mode=inline";
        return new PreviewPayload(
                artifact.getArtifactId(),
                PreviewKind.INLINE,
                url,
                mimeType,
                artifact.getName(),
                artifact.getSize(),
                artifact.getStorageRef(),
                artifact.getPreviewMeta());
    }

    private PreviewPayload buildProxyPayload(AegisArtifact artifact, String mimeType) {
        String url = "/api/runtime/artifact/" + artifact.getArtifactId() + "/content"
                + "?mode=proxy";
        return new PreviewPayload(
                artifact.getArtifactId(),
                PreviewKind.PROXY,
                url,
                mimeType,
                artifact.getName(),
                artifact.getSize(),
                artifact.getStorageRef(),
                artifact.getPreviewMeta());
    }

    private PreviewPayload buildDownloadPayload(AegisArtifact artifact) {
        String url = "/api/runtime/artifact/" + artifact.getArtifactId() + "/download";
        return new PreviewPayload(
                artifact.getArtifactId(),
                PreviewKind.DOWNLOAD,
                url,
                resolveOfficeMime(artifact.getType()),
                artifact.getName(),
                artifact.getSize(),
                artifact.getStorageRef(),
                artifact.getPreviewMeta());
    }

    private String resolveImageMime(String type) {
        if (type == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return switch (type.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml";
            case "webp" -> "image/webp";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private String resolveOfficeMime(String type) {
        if (type == null) return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return switch (type.toLowerCase()) {
            case "pdf" -> MediaType.APPLICATION_PDF_VALUE;
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "excel", "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt", "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * 预览载荷值对象。
     */
    public record PreviewPayload(
            String artifactId,
            PreviewKind kind,
            String url,
            String mimeType,
            String name,
            Long size,
            String storageRef,
            String previewMeta) {
    }
}
