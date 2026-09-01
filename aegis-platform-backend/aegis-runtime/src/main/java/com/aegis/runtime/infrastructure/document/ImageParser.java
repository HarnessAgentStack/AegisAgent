package com.aegis.runtime.infrastructure.document;

import com.aegis.runtime.service.document.VisionDescriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 图片解析器。
 *
 * <p>P1 阶段：集成视觉 LLM 生成图片描述（通过 VisionDescriptionService）。
 * 当主模型不支持多模态时，使用此 Parser 将图片转为文字描述，
 * 再将描述文本传递给主模型。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageParser implements FileParser {

    private static final Set<String> SUPPORTED = Set.of(
            "png", "jpg", "jpeg", "webp", "gif", "bmp");

    private final VisionDescriptionService visionService;

    @Override
    public boolean supports(String extension, String contentType) {
        return SUPPORTED.contains(extension.toLowerCase())
                || (contentType != null && contentType.startsWith("image/"));
    }

    @Override
    public ParsedContent parse(byte[] content, String filename) {
        // 尝试调用视觉模型生成描述
        String description = null;
        try {
            // 注意：这里需要 tenantId，但 FileParser 接口没有传入
            // 暂时使用 null，由 VisionDescriptionService 处理默认逻辑
            description = visionService.describe(content, filename, null);
        } catch (Exception e) {
            log.warn("视觉描述调用失败，降级为元信息: filename={}", filename, e);
        }

        // 降级：返回图片元信息
        if (description == null || description.isEmpty()) {
            description = String.format(
                    "[图片: %s, 大小: %dKB, 格式: %s]\n" +
                    "（当前模型不支持图片理解，图片内容未解析）",
                    filename,
                    content.length / 1024,
                    getExtension(filename).toUpperCase()
            );
        }

        return ParsedContent.builder()
                .text(description)
                .metadata(ParsedContent.ParseMetadata.builder()
                        .imageCount(1)
                        .charCount(description.length())
                        .estimatedTokens(description.length() / 4)
                        .build())
                .build();
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1) : "";
    }
}
