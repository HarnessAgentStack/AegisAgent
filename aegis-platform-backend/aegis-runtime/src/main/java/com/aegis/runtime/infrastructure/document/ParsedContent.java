package com.aegis.runtime.infrastructure.document;

import lombok.Builder;
import lombok.Data;

/**
 * 文件解析结果。
 *
 * <p>由 {@link FileParser} 实现类产出，包含解析后的文本和结构化元数据，
 * 供内容适配层做 token 预算分配和智能裁剪。
 *
 * @author wang.zhen
 */
@Data
@Builder
public class ParsedContent {
    /** 解析后的文本（Markdown 格式） */
    private String text;
    /** 解析元数据 */
    private ParseMetadata metadata;

    @Data
    @Builder
    public static class ParseMetadata {
        /** PDF 页数 */
        private int pageCount;
        /** Excel sheet 数 */
        private int sheetCount;
        /** 表格数 */
        private int tableCount;
        /** 图片数 */
        private int imageCount;
        /** 字符数 */
        private int charCount;
        /** 估算 token 数（字符数/4） */
        private int estimatedTokens;
    }
}
