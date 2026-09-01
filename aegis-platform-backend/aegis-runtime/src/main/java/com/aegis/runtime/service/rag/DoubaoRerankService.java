package com.aegis.runtime.service.rag;

import com.aegis.core.enums.model.ModelTier;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.aegis.runtime.integration.model.LlmHttpClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 LLM 的轻量 Rerank 实现。
 *
 * <p>使用 {@link LlmClientFactory} 构造 LIGHT 档模型客户端，
 * 通过 prompt 引导模型对候选内容逐一打分（0.0 ~ 1.0），然后按得分降序重排。</p>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>LLM 不可用 / 调用异常 / 响应解析失败 → 返回原顺序，rerankScore 等于 originalScore</li>
 *   <li>候选数 ≤ 1 → 直接返回（无需重排）</li>
 * </ul>
 *
 * <h3>注意</h3>
 * <p>这是基于 prompt 的简化重排方案。如需生产级 Rerank 能力（如 BGE-reranker、
 * 豆包 rerank 专用接口），可替换本实现的 LLM 调用部分为专用推理服务。</p>
 *
 * @author wang.zhen
 * @see RerankService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoubaoRerankService implements RerankService {

    private static final String RERANK_SYSTEM =
            "你是一个 Rerank 重排序助手。给定一个用户 query 和若干候选文档片段，"
                    + "对每个片段评估其与 query 的相关性，输出 0.0 到 1.0 之间的分数（两位小数）。"
                    + "1.0 表示高度相关且可直接回答，0.0 表示完全无关或噪声。"
                    + "只输出 JSON 数组，格式：[{\"index\": 0, \"score\": 0.85}, ...]，"
                    + "不要解释、不要 markdown、不要多余文本。";

    /** 单次重排候选上限（防止 prompt 过长） */
    private static final int MAX_CANDIDATES_PER_BATCH = 20;

    private final LlmClientFactory llmClientFactory;

    @Override
    public List<RerankResult> rerank(String query, List<RerankCandidate> candidates, Long tenantId) {
        // 异常保护：candidates 为 null 时返回空列表
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        if (candidates.size() == 1) {
            RerankCandidate c = candidates.get(0);
            return List.of(new RerankResult(c.id(), c.originalScore(), c.originalScore()));
        }
        try {
            List<RerankResult> results = doRerank(query, candidates, tenantId);
            // 异常保护：LLM 返回空列表则退回原顺序
            if (results == null || results.isEmpty()) {
                return fallback(candidates);
            }
            // 按 rerankScore 降序
            results.sort(Comparator.comparingDouble(RerankResult::rerankScore).reversed());
            log.debug("Rerank 完成: query={}, candidates={}, topScore={}, bottomScore={}",
                    truncate(query, 50), candidates.size(),
                    results.get(0).rerankScore(),
                    results.get(results.size() - 1).rerankScore());
            return results;
        } catch (Exception e) {
            log.warn("Rerank LLM 调用失败，降级返回原顺序: error={}", e.getMessage());
            return fallback(candidates);
        }
    }

    /**
     * 执行实际 Rerank 调用与结果解析。
     */
    private List<RerankResult> doRerank(String query, List<RerankCandidate> candidates, Long tenantId) {
        // 截断到上限
        List<RerankCandidate> batch = candidates.size() > MAX_CANDIDATES_PER_BATCH
                ? candidates.subList(0, MAX_CANDIDATES_PER_BATCH)
                : candidates;

        LlmHttpClient client = llmClientFactory.create(tenantId, ModelTier.LIGHT);
        String userPrompt = buildRerankUserPrompt(query, batch);
        String response = client.chat(RERANK_SYSTEM, userPrompt, 0.0f, 1024, 10);

        return parseRerankResponse(response, batch);
    }

    /**
     * 构建 Rerank 的 user prompt。
     */
    private static String buildRerankUserPrompt(String query, List<RerankCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户 query：").append(query).append("\n\n");
        sb.append("候选文档片段：\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("--- 候选 #").append(i).append(" ---\n");
            sb.append(truncate(candidates.get(i).content(), 400)).append("\n");
        }
        sb.append("\n请输出每个候选的相关性评分 JSON：");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 数组为 RerankResult 列表。
     * LLM 可能返回包裹了 markdown ```json ... ``` 的代码块，需要清洗。
     */
    private static List<RerankResult> parseRerankResponse(String response, List<RerankCandidate> candidates) {
        List<RerankResult> results = new ArrayList<>(candidates.size());
        // 先给所有候选一个默认分数（使用原始分数），防止 LLM 漏打分
        float[] scores = new float[candidates.size()];
        boolean[] scored = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            scores[i] = candidates.get(i).originalScore();
        }

        String cleaned = cleanJsonResponse(response);
        try {
            com.alibaba.fastjson2.JSONArray arr = com.alibaba.fastjson2.JSON.parseArray(cleaned);
            for (int i = 0; i < arr.size(); i++) {
                com.alibaba.fastjson2.JSONObject item = arr.getJSONObject(i);
                int index = item.getIntValue("index");
                float score = item.getFloat("score") != null ? item.getFloat("score") : 0.0f;
                if (index >= 0 && index < candidates.size()) {
                    scores[index] = score;
                    scored[index] = true;
                }
            }
        } catch (Exception e) {
            log.warn("Rerank 响应 JSON 解析失败: response={}", truncate(response, 300));
        }

        for (int i = 0; i < candidates.size(); i++) {
            float finalScore = scored[i] ? scores[i] : candidates.get(i).originalScore();
            results.add(new RerankResult(candidates.get(i).id(), finalScore, candidates.get(i).originalScore()));
        }
        return results;
    }

    /**
     * 清洗 LLM 响应中的 markdown 代码块标记与前后空白。
     */
    private static String cleanJsonResponse(String raw) {
        if (raw == null) return "";
        String cleaned = raw.trim();
        // 去掉 ```json ... ``` 包裹
        cleaned = cleaned.replaceFirst("^```json\\s*", "");
        cleaned = cleaned.replaceFirst("\\s*```$", "");
        return cleaned.trim();
    }

    /**
     * 降级返回：原顺序 + rerankScore = originalScore。
     */
    private static List<RerankResult> fallback(List<RerankCandidate> candidates) {
        List<RerankResult> results = new ArrayList<>(candidates.size());
        for (RerankCandidate c : candidates) {
            results.add(new RerankResult(c.id(), c.originalScore(), c.originalScore()));
        }
        return results;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
