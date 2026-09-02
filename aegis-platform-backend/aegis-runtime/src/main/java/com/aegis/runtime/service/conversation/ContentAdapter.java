package com.aegis.runtime.service.conversation;

import com.aegis.core.common.text.TokenEstimator;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.runtime.infrastructure.document.AttachmentStrategy;
import com.aegis.runtime.infrastructure.document.ParsedContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 内容适配器。
 *
 * <p>将多个附件的解析结果适配为适合模型输入的文本，
 * 实现智能裁剪与多附件合并。
 *
 * <h3>适配策略</h3>
 * <ol>
 *   <li>计算附件内容 token 预算 = contextWindow × 0.7 - 用户消息token - 2000(系统提示词预留)</li>
 *   <li>按附件数量均分预算</li>
 *   <li>对超长内容在段落边界处截断</li>
 *   <li>输出带来源标注的合并文本</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class ContentAdapter {

    /** 预留比例：为系统提示词 + 对话历史预留 30% token */
    private static final double CONTENT_BUDGET_RATIO = 0.7;

    /**
     * 附件区起始标记（用户手输文本与附件解析内容的分界）。
     *
     * <p>安全中间件依据本标记拆分检测：手输部分命中 BLOCK 词拦截，附件部分降级脱敏放行。
     * 标记变更须两处同步（本常量为单一事实来源）。</p>
     */
    public static final String ATTACHMENT_SECTION_MARKER = "\n\n---\n📎 用户上传了以下附件：\n";

    /** 来源标注模板 */
    private static final String SOURCE_PREFIX = "\n\n---\n📎 附件 [%d/%d]：%s";

    /**
     * 适配附件内容为模型输入文本。
     *
     * @param strategies    附件处理策略列表（已解析）
     * @param contextWindow 模型上下文窗口（token）
     * @param userMessage   用户原始消息
     * @return 适配后的完整用户消息
     */
    public String adapt(List<AttachmentStrategy> strategies, int contextWindow,
                         String userMessage) {
        if (strategies == null || strategies.isEmpty()) {
            return userMessage;
        }

        // 1. 计算附件内容 token 预算
        int totalBudget = (int) (contextWindow * CONTENT_BUDGET_RATIO);
        int attachmentBudget = Math.max(totalBudget - estimateTokens(userMessage) - 2000, 1000);

        // 2. 按文件类型优先级分配预算
        // 优先级：图片(1.5x) > 文档(1.0x) > 文本(0.5x)
        int totalWeight = 0;
        for (AttachmentStrategy strategy : strategies) {
            totalWeight += getPriorityWeight(strategy.getFileCategory());
        }

        // 3. 逐个适配附件内容
        StringBuilder sb = new StringBuilder(userMessage);
        sb.append(ATTACHMENT_SECTION_MARKER);

        for (int i = 0; i < strategies.size(); i++) {
            AttachmentStrategy strategy = strategies.get(i);
            AttachmentRef att = strategy.getAttachment();

            sb.append(String.format(SOURCE_PREFIX, i + 1, strategies.size(), att.getName()));
            if (att.getSizeKB() != null) {
                sb.append(" (").append(att.getSizeKB()).append("KB)");
            }
            sb.append("\n");

            switch (strategy.getType()) {
                case NATIVE_PASS:
                    if ("image".equals(strategy.getFileCategory())) {
                        // 图片 NATIVE_PASS：多模态 ImageBlock 由 AgentAssemblyService.buildImageBlocks 构造
                        // 此处仅输出文本标注作为上下文提示
                        sb.append("[图片附件: ").append(att.getName());
                        if (att.getSizeKB() != null) {
                            sb.append(", 大小: ").append(att.getSizeKB()).append("KB");
                        }
                        sb.append("]\n");
                    } else {
                        // 非图片 NATIVE_PASS（理论上不会到这里，策略层应将非图片归为 ENGINE_PARSE）
                        sb.append("[文件将直接传递给模型处理]\n");
                    }
                    break;

                case ENGINE_PARSE:
                    ParsedContent parsed = strategy.getParsedContent();
                    if (parsed != null && parsed.getText() != null && !parsed.getText().trim().isEmpty()) {
                        // 按优先级分配预算
                        int weight = getPriorityWeight(strategy.getFileCategory());
                        int perFileBudget = (int) (attachmentBudget * ((double) weight / totalWeight));
                        String adapted = smartTruncate(parsed.getText(), perFileBudget);
                        sb.append("```\n").append(adapted).append("\n```\n");
                    } else {
                        sb.append("[文件解析失败或内容为空]\n");
                    }
                    break;
            }
        }

        return sb.toString();
    }

    /**
     * 获取文件类型的优先级权重。
     *
     * <p>优先级：图片(1.5x) > 文档(1.0x) > 文本(0.5x)
     * 图片通常包含更多视觉信息，需要更多 token 描述；
     * 文本文件信息密度较低，分配较少预算。
     */
    private int getPriorityWeight(String fileCategory) {
        if (fileCategory == null) return 10;
        return switch (fileCategory.toLowerCase()) {
            case "image" -> 15;  // 1.5x
            case "document" -> 10; // 1.0x
            case "text" -> 5;    // 0.5x
            default -> 10;
        };
    }

    /**
     * 智能截断。
     *
     * <p>策略：
     * <ol>
     *   <li>如果文本在预算内，完整保留</li>
     *   <li>超出预算时，优先保留开头（通常是摘要/标题）</li>
     *   <li>在段落边界处截断（避免句子中间断开）</li>
     *   <li>添加截断提示</li>
     * </ol>
     */
    private String smartTruncate(String text, int tokenBudget) {
        // P1-4：字符集感知的 token→字符预算换算（中文按 1.3 token/字，英文按 4 字符/token）
        int charBudget = TokenEstimator.tokenBudgetToCharBudget(tokenBudget, text);

        if (text.length() <= charBudget) {
            return text;
        }

        // 在段落边界处截断
        int truncateAt = charBudget;
        int lastNewline = text.lastIndexOf('\n', truncateAt);
        if (lastNewline > charBudget * 0.5) {
            truncateAt = lastNewline;
        }

        return text.substring(0, truncateAt)
                + "\n\n... (内容已智能截断，保留前 " + TokenEstimator.estimateTokens(text.substring(0, truncateAt)) + " token)";
    }

    private int estimateTokens(String text) {
        // P1-4：使用字符集感知估算替代 length()/4，中文输入不再严重偏低
        return TokenEstimator.estimateTokens(text);
    }
}
