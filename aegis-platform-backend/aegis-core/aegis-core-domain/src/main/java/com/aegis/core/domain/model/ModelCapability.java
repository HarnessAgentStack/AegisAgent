package com.aegis.core.domain.model;

import lombok.Data;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 模型能力矩阵。
 *
 * <p>从 model_def.capabilities JSON 字段反序列化，描述模型的多维能力，
 * 供能力协商层决定附件处理策略。
 *
 * @author wang.zhen
 */
@Data
public class ModelCapability {

    /** 多模态能力 */
    private MultimodalCapability multimodal;

    /** 文档直接输入能力 */
    private DocumentCapability document;

    /** 视觉描述能力（将图片转为文字描述） */
    private VisionDescriptionCapability visionDescription;

    /** 上下文窗口大小（token） */
    private int contextWindow;

    /** 最大输出 token */
    private int maxOutputTokens;

    /** 是否支持 Function Calling */
    private boolean supportsFunctionCalling;

    /** 是否支持 JSON Mode */
    private boolean supportsJsonMode;

    @Data
    public static class MultimodalCapability {
        private boolean supported;
        private Set<String> imageTypes = new HashSet<>();
        private int maxImageSizeKb;
        private int maxImagesPerRequest;
    }

    @Data
    public static class DocumentCapability {
        private boolean supported;
        private Set<String> docTypes = new HashSet<>();
        private int maxDocSizeKb;
    }

    @Data
    public static class VisionDescriptionCapability {
        private boolean supported;
        private String description;
    }

    /**
     * 默认能力：纯文本模型。
     */
    public static ModelCapability defaultText() {
        ModelCapability cap = new ModelCapability();
        cap.setContextWindow(32000);
        cap.setMaxOutputTokens(4096);
        cap.setSupportsFunctionCalling(true);
        cap.setMultimodal(new MultimodalCapability());
        cap.setDocument(new DocumentCapability());
        cap.setVisionDescription(new VisionDescriptionCapability());
        return cap;
    }

    /**
     * 判断是否支持指定图片格式。
     */
    public boolean supportsImageType(String imageType) {
        if (multimodal == null || !multimodal.isSupported()) {
            return false;
        }
        Set<String> types = multimodal.getImageTypes();
        return types != null && types.contains(imageType.toLowerCase());
    }

    /**
     * 判断是否支持指定文档格式。
     */
    public boolean supportsDocType(String docType) {
        if (document == null || !document.isSupported()) {
            return false;
        }
        Set<String> types = document.getDocTypes();
        return types != null && types.contains(docType.toLowerCase());
    }
}
