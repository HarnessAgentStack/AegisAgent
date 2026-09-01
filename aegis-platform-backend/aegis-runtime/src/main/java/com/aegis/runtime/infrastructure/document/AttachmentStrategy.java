package com.aegis.runtime.infrastructure.document;

import com.aegis.core.dto.agent.AttachmentRef;
import lombok.Builder;
import lombok.Data;

/**
 * 附件处理策略。
 *
 * <p>由能力协商层为每个附件决定最优处理方式：
 * <ul>
 *   <li>{@link StrategyType#NATIVE_PASS} — 模型原生支持，直接传递</li>
 *   <li>{@link StrategyType#ENGINE_PARSE} — 工程解析后传递文本</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Builder
public class AttachmentStrategy {
    /** 附件引用 */
    private AttachmentRef attachment;
    /** 处理策略类型 */
    private StrategyType type;
    /** 文件分类（image/document/text） */
    private String fileCategory;
    /** 解析结果（策略B/C 时填充） */
    private ParsedContent parsedContent;

    public enum StrategyType {
        /** 原生传递（模型直接支持） */
        NATIVE_PASS,
        /** 工程解析后传递文本 */
        ENGINE_PARSE
    }

    public static AttachmentStrategy nativePass(AttachmentRef att, String category) {
        return AttachmentStrategy.builder()
                .attachment(att).type(StrategyType.NATIVE_PASS).fileCategory(category).build();
    }

    public static AttachmentStrategy engineParse(AttachmentRef att, String category) {
        return AttachmentStrategy.builder()
                .attachment(att).type(StrategyType.ENGINE_PARSE).fileCategory(category).build();
    }
}
