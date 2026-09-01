package com.aegis.runtime.service.agent;

import com.aegis.core.domain.model.ModelCapability;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.runtime.infrastructure.document.AttachmentStrategy;
import com.aegis.runtime.infrastructure.document.FileParseEngine;
import com.aegis.runtime.infrastructure.document.ParsedContent;
import com.aegis.runtime.service.document.FileStorageService;
import com.aegis.runtime.integration.model.ModelRouteResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 模型能力协商器。
 *
 * <p>根据当前会话使用的模型能力矩阵，为每个附件决定最优处理策略。
 * 核心原则：工程层不假设模型能力，而是探测能力后动态选择传递策略。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelCapabilityResolver {

    private final ModelRouteResolver modelRouteResolver;
    private final FileParseEngine parseEngine;
    private final FileStorageService fileStorageService;

    private static final Set<String> IMAGE_TYPES = Set.of(
            "png", "jpg", "jpeg", "webp", "gif", "bmp");
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "pdf", "docx", "xlsx", "pptx");

    /**
     * 为附件列表协商处理策略。
     *
     * @param tenantId  租户ID
     * @param modelTier 模型档位
     * @param attachments 附件列表
     * @return 每个附件的处理策略与解析结果
     */
    public List<AttachmentStrategy> resolve(Long tenantId, ModelTier modelTier,
                                             List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        // 1. 从 DB 读取模型能力矩阵
        ModelCapability capability = modelRouteResolver.resolveCapability(tenantId, modelTier);

        // 2. 为每个附件决定策略
        List<AttachmentStrategy> strategies = new ArrayList<>();
        for (AttachmentRef att : attachments) {
            String ext = getExtension(att.getName());
            AttachmentStrategy strategy = decideStrategy(att, ext, capability, tenantId);
            strategies.add(strategy);
        }
        return strategies;
    }

    /**
     * 为单个附件决定处理策略。
     */
    private AttachmentStrategy decideStrategy(AttachmentRef att, String ext,
                                               ModelCapability capability, Long tenantId) {
        // 图片文件
        if (IMAGE_TYPES.contains(ext.toLowerCase())) {
            if (capability.supportsImageType(ext)) {
                return AttachmentStrategy.nativePass(att, "image");
            } else {
                return AttachmentStrategy.engineParse(att, "image");
            }
        }

        // 文档文件（PDF/DOCX/XLSX/PPTX）
        if (DOCUMENT_TYPES.contains(ext.toLowerCase())) {
            if (capability.supportsDocType(ext)) {
                return AttachmentStrategy.nativePass(att, "document");
            }
            return AttachmentStrategy.engineParse(att, "document");
        }

        // 文本文件（TXT/MD/CSV/JSON/XML/HTML）— 始终工程解析
        return AttachmentStrategy.engineParse(att, "text");
    }

    /**
     * 解析附件内容（如策略为 ENGINE_PARSE）。
     *
     * @param strategy 附件策略
     * @param tenantId 租户ID
     * @param userId   用户ID
     */
    public void parseAttachment(AttachmentStrategy strategy, Long tenantId, Long userId) {
        if (strategy.getType() != AttachmentStrategy.StrategyType.ENGINE_PARSE) {
            return;
        }
        AttachmentRef att = strategy.getAttachment();
        if (att.getFileId() == null) {
            strategy.setParsedContent(ParsedContent.builder()
                    .text("[附件无文件ID]")
                    .metadata(ParsedContent.ParseMetadata.builder().build())
                    .build());
            return;
        }

        byte[] content = fileStorageService.readContent(att.getFileId(), tenantId, userId);
        if (content == null || content.length == 0) {
            strategy.setParsedContent(ParsedContent.builder()
                    .text("[文件内容为空]")
                    .metadata(ParsedContent.ParseMetadata.builder().build())
                    .build());
            return;
        }

        ParsedContent parsed = parseEngine.parse(content, att.getName(), att.getContentType(), att.getFileId());
        strategy.setParsedContent(parsed);
    }

    private String getExtension(String filename) {
        if (filename == null || filename.isEmpty()) return "";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot + 1) : "";
    }
}
