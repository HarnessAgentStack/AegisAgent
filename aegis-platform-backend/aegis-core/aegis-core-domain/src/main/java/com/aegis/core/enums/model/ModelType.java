package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 模型用途分类。
 *
 * <p>标识模型的主要用途，用于能力协商层决定附件处理策略。
 *
 * @author wang.zhen
 */
@Getter
public enum ModelType {
    /** 纯文本对话模型 */
    TEXT("纯文本"),
    /** 多模态模型（文本+图片） */
    MULTIMODAL("多模态"),
    /** 向量嵌入模型 */
    EMBEDDING("向量嵌入"),
    /** 视觉理解模型（图片→文字描述） */
    VISION("视觉理解");

    private final String desc;

    ModelType(String desc) {
        this.desc = desc;
    }
}
