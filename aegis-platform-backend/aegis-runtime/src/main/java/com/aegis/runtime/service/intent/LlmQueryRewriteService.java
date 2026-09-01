package com.aegis.runtime.service.intent;

import com.aegis.core.enums.model.ModelTier;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.aegis.runtime.integration.model.LlmHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 LLM 的查询改写服务实现。
 *
 * <p>使用 {@link LlmClientFactory} 构造 LIGHT 档轻量模型客户端，
 * 通过 prompt 引导 LLM 完成共指消解。</p>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>LlmHttpClient 不可用 / LLM 异常 → resolveCoreference 返回原始 query</li>
 * </ul>
 *
 * @author wang.zhen
 * @see QueryRewriteService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmQueryRewriteService implements QueryRewriteService {

    private static final String COREFERENCE_SYSTEM =
            "你是一个查询改写助手。基于以下多轮对话历史，将用户最新提问改写为独立、完整的检索 query。"
                    + "消解代词（它/这个/那个→具体实体），保留用户意图，输出单一简洁 query。只输出改写结果，不要解释。";

    private final LlmClientFactory llmClientFactory;

    @Override
    public String resolveCoreference(String rawQuery, List<String> recentHistory, Long tenantId) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return rawQuery;
        }
        // 无历史或历史过短，无需消解
        if (recentHistory == null || recentHistory.isEmpty()) {
            return rawQuery;
        }
        try {
            LlmHttpClient client = llmClientFactory.create(tenantId, ModelTier.LIGHT);
            String userPrompt = buildCoreferenceUserPrompt(rawQuery, recentHistory);
            String rewritten = client.chat(COREFERENCE_SYSTEM, userPrompt, 0.1f, 256, 5);
            if (rewritten != null && !rewritten.isBlank()) {
                log.debug("查询改写: 原始={}, 改写={}", rawQuery, rewritten);
                return rewritten;
            }
            log.warn("查询改写返回空结果，降级为原始 query: rawQuery={}", rawQuery);
            return rawQuery;
        } catch (Exception e) {
            log.warn("查询改写 LLM 调用失败，降级为原始 query: error={}", e.getMessage());
            return rawQuery;
        }
    }

    /**
     * 构建共指消解的 user prompt。
     * 历史对话按时间倒序展示，突出最近 5 轮。
     */
    private static String buildCoreferenceUserPrompt(String rawQuery, List<String> recentHistory) {
        // 取最近 5 条，倒序
        int size = Math.min(recentHistory.size(), 5);
        List<String> latest = recentHistory.subList(recentHistory.size() - size, recentHistory.size());
        StringBuilder sb = new StringBuilder();
        sb.append("对话历史（按时间正序，最近").append(size).append("轮）：\n");
        for (int i = 0; i < latest.size(); i++) {
            sb.append(i + 1).append(". ").append(latest.get(i)).append('\n');
        }
        sb.append("\n最新提问：").append(rawQuery).append('\n');
        sb.append("改写结果：");
        return sb.toString();
    }
}
