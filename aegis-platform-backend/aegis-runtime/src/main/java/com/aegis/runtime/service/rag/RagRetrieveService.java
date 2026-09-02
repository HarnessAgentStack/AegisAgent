package com.aegis.runtime.service.rag;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;
import com.aegis.core.constant.KbConstants;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.spi.EmbeddingService;
import com.aegis.core.spi.IVectorStore;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.runtime.service.intent.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索领域服务。
 *
 * <p>承载知识库语义检索完整链路：QueryRewrite → 策略路由（VECTOR / KEYWORD / HYBRID） → Rerank。
 * 由 {@link com.aegis.core.domain.resource.KnowledgeBase} 三个字段驱动全部能力落地：
 * <ul>
 *   <li>{@code enableQueryRewrite} — 开启共指消解与 query 扩展</li>
 *   <li>{@code retrievalStrategy} — 路由向量检索、关键词检索或 RRF 融合</li>
 *   <li>{@code enableRerank} — 开启 LLM 重排序</li>
 * </ul>
 *
 * <h3>检索流程</h3>
 * <ol>
 *   <li>查询 KnowledgeBase 配置（含三字段）</li>
 *   <li>若 enableQueryRewrite → 调用 {@link QueryRewriteService#resolveCoreference} 改写 query</li>
 *   <li>按 retrievalStrategy 路由：
 *     VECTOR → {@link IVectorStore#search}
 *     KEYWORD → {@link KeywordRetrieveService#keywordRetrieve}
 *     HYBRID → 向量 top10 + 关键词 top10 → RRF 融合 → 去重</li>
 *   <li>相似度阈值过滤（向量 / 融合结果）</li>
 *   <li>若 enableRerank → 调用 {@link RerankService#rerank} 取最终 topN</li>
 *   <li>返回检索结果列表（每条含 docId、content、score、chunkIndex）</li>
 * </ol>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>阻塞式 JDBC：使用 MyBatis-Plus BaseMapper，不使用响应式</li>
 *   <li>租户隔离：检索前设置 TenantContext，确保向量集合按租户隔离</li>
 *   <li>LLM 依赖全部 try-catch 降级，保证检索主链路不因 LLM 不可用而中断</li>
 *   <li>接口兼容性：保留旧签名 retrieve(tenantId, kbId, query, topK) 委托给新方法</li>
 * </ul>
 *
 * @author wang.zhen
 * @see KnowledgeBase
 * @see IVectorStore
 * @see KeywordRetrieveService
 * @see RerankService
 * @see QueryRewriteService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrieveService {

    private final IVectorStore vectorStore;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingService embeddingService;
    private final QueryRewriteService queryRewriteService;
    private final KeywordRetrieveService keywordRetrieveService;
    private final RerankService rerankService;

    /** 默认 Top-K（引用 KbConstants 统一管理） */
    private static final int DEFAULT_TOP_K = KbConstants.DEFAULT_TOP_K;
    /** 默认相似度阈值 */
    private static final BigDecimal DEFAULT_SIMILARITY_THRESHOLD = KbConstants.DEFAULT_SIMILARITY_THRESHOLD;

    /** HYBRID 模式下每种检索路径的召回条数上限（RRF 融合前各取 top10） */
    private static final int HYBRID_PER_PATH_TOP_K = 10;
    /** RRF 融合平滑常数，标准值 60 */
    private static final double RRF_K = 60.0;

    // ===== 旧签名重载（保持接口兼容） =====

    /**
     * RAG 语义检索（兼容旧调用方）。
     *
     * @deprecated 建议使用 {@link #retrieve(Long, Long, String, int, List)} 传入对话历史
     */
    @Deprecated
    public List<Map<String, Object>> retrieve(Long tenantId, Long kbId, String query, int topK) {
        return retrieve(tenantId, kbId, query, topK, Collections.emptyList());
    }

    // ===== 新签名主入口 =====

    /**
     * RAG 语义检索（主入口）。
     *
     * @param tenantId      租户ID
     * @param kbId          知识库ID
     * @param query         用户查询文本
     * @param topK          返回条数（<=0 时使用知识库配置值）
     * @param recentHistory 最近对话历史（按时间正序），供 QueryRewrite 共指消解使用；可为空列表
     * @return 检索结果列表，每条含 docId、content、score、chunkIndex
     */
    public List<Map<String, Object>> retrieve(Long tenantId, Long kbId, String query,
                                              int topK, List<String> recentHistory) {
        // 设置租户上下文（供 MyBatis-Plus 多租户插件读取）。
        // 保存调用方已绑定的上下文（如租户隔离中间件在 onAgent 入口绑定的租户），
        // 检索结束后恢复——finally 直接 clear() 会清空调用方的租户上下文，
        // 导致同一对话链路后续 DB 操作在 fail-closed 插件下抛"租户上下文缺失"
        TenantContext previousContext = TenantContextHolder.get();
        if (tenantId != null) {
            TenantContextHolder.set(TenantContext.builder().tenantId(tenantId).build());
        }
        try {
            // 1. 查询知识库配置
            KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
            if (kb == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在: " + kbId);
            }
            if (tenantId != null && !tenantId.equals(kb.getTenantId())) {
                throw new BusinessException(ResultCode.FORBIDDEN, "无权检索该知识库");
            }

            // C4: 嵌入模型一致性校验
            if (kb.getEmbeddingModel() != null && !kb.getEmbeddingModel().isEmpty()) {
                String actualModel = kb.getEmbeddingModel();
                if (!KbConstants.DEFAULT_EMBEDDING_MODEL.equals(actualModel)
                        && !KbConstants.MODEL_CODE_DOUBAO_EMBEDDING.equals(actualModel)) {
                    log.warn("C4: 知识库配置嵌入模型({})与系统实际使用模型({})不一致，可能导致维度不匹配: kbId={}",
                            actualModel, KbConstants.DEFAULT_EMBEDDING_MODEL, kbId);
                }
            }

            // 2. QueryRewrite：共指消解
            String effectiveQuery = query;
            if (Boolean.TRUE.equals(kb.getEnableQueryRewrite())) {
                effectiveQuery = queryRewriteService.resolveCoreference(query, recentHistory, tenantId);
                if (!query.equals(effectiveQuery)) {
                    log.debug("QueryRewrite 改写: 原始=[{}], 改写=[{}]", query, effectiveQuery);
                }
            }

            // 3. 按 retrievalStrategy 路由召回
            String strategy = kb.getRetrievalStrategy();
            List<Map<String, Object>> rawResults;
            switch (strategy) {
                case "KEYWORD":
                    rawResults = doKeywordRetrieve(tenantId, kb, effectiveQuery);
                    break;
                case "HYBRID":
                    rawResults = doHybridRetrieve(tenantId, kb, effectiveQuery);
                    break;
                case "VECTOR":
                default:
                    rawResults = doVectorRetrieve(tenantId, kb, effectiveQuery);
                    break;
            }

            if (rawResults == null || rawResults.isEmpty()) {
                log.info("RAG retrieve 无检索结果: kbId={}, query={}", kbId, effectiveQuery);
                return Collections.emptyList();
            }

            // 4. Rerank（可选）
            List<Map<String, Object>> reranked = rawResults;
            if (Boolean.TRUE.equals(kb.getEnableRerank())) {
                reranked = doRerank(effectiveQuery, rawResults, tenantId);
            }

            // 5. TopK 截断 + 返回
            int effectiveTopK = topK > 0 ? topK
                    : (kb.getTopK() != null ? kb.getTopK() : DEFAULT_TOP_K);
            if (reranked.size() > effectiveTopK) {
                reranked = reranked.subList(0, effectiveTopK);
            }

            log.info("RAG retrieve 完成: kbId={}, strategy={}, hits={}, finalTopK={}",
                    kbId, strategy != null ? strategy : "VECTOR", reranked.size(), effectiveTopK);
            return reranked;
        } finally {
            // 恢复调用方上下文（无则清理，防止线程池复用泄漏）
            if (previousContext != null) {
                TenantContextHolder.set(previousContext);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    // ===== 内部召回方法 =====

    /**
     * 纯向量召回。
     */
    private List<Map<String, Object>> doVectorRetrieve(Long tenantId, KnowledgeBase kb, String query) {
        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null || queryVector.length == 0) {
            log.warn("RAG 查询向量化返回空向量，跳过向量检索: kbId={}", kb.getId());
            return Collections.emptyList();
        }

        int topK = kb.getTopK() != null ? Math.max(kb.getTopK() * 2, 10) : HYBRID_PER_PATH_TOP_K;
        String collection = KbConstants.VECTOR_COLLECTION_PREFIX + kb.getId();
        List<IVectorStore.SearchHit> hits = vectorStore.search(
                tenantId, collection, queryVector, topK, null);

        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }

        BigDecimal threshold = kb.getSimilarityThreshold() != null
                ? kb.getSimilarityThreshold() : DEFAULT_SIMILARITY_THRESHOLD;
        float thresholdValue = threshold.floatValue();

        List<Map<String, Object>> results = new ArrayList<>(hits.size());
        for (IVectorStore.SearchHit hit : hits) {
            // Milvus v2.5 COSINE metric 直接返回 cosine similarity（范围 [-1, 1]，越大越相似）
            // 实证：self-search 返回 1.0，随机向量返回接近 0.0
            // threshold 是 similarity 阈值，直接比较即可
            float similarity = hit.score;
            if (similarity < thresholdValue) {
                continue;
            }
            Map<String, Object> item = new HashMap<>(6);
            item.put("docId", hit.metadata != null ? hit.metadata.get("doc_id") : null);
            item.put("content", hit.metadata != null ? hit.metadata.get("content") : null);
            item.put("score", similarity);
            item.put("chunkIndex", hit.metadata != null ? hit.metadata.get("chunk_index") : null);
            item.put("vectorScore", similarity);
            results.add(item);
        }
        return results;
    }

    /**
     * 纯关键词召回。
     */
    private List<Map<String, Object>> doKeywordRetrieve(Long tenantId, KnowledgeBase kb, String query) {
        int topK = kb.getTopK() != null ? Math.max(kb.getTopK() * 2, 10) : HYBRID_PER_PATH_TOP_K;
        List<Map<String, Object>> hits = keywordRetrieveService.keywordRetrieve(tenantId, kb.getId(), query, topK);
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        // 规范化字段名（keywordSearch 返回的字段已按驼峰别名），添加统一的 score 字段
        List<Map<String, Object>> results = new ArrayList<>(hits.size());
        for (Map<String, Object> hit : hits) {
            Map<String, Object> item = new HashMap<>(6);
            item.put("docId", hit.get("docId"));
            item.put("content", hit.get("content"));
            Object bm25ScoreObj = hit.get("bm25Score");
            float bm25 = bm25ScoreObj instanceof Number n ? n.floatValue() : 0f;
            item.put("score", bm25);
            item.put("chunkIndex", hit.get("chunkIndex"));
            item.put("keywordScore", bm25);
            results.add(item);
        }
        return results;
    }

    /**
     * HYBRID 召回：向量 + 关键词 → RRF 融合。
     *
     * <h3>向量相关性锚点保护（v2）</h3>
     * <p>HYBRID 模式下向量路径是语义相关性的锚点。如果向量 top1 都低于知识库自身
     * similarity_threshold，说明 query 与该知识库在语义层面完全不相关，
     * keyword 命中可能只是字面偶然（如 query 含某个通用词恰好出现在 PDF 里）。
     * 此时整体放弃 HYBRID 检索，避免"天津天气"这类 query 从完全不相关的技术文档里
     * 捞回"相似度 0.0%"的碎片。
     */
    private List<Map<String, Object>> doHybridRetrieve(Long tenantId, KnowledgeBase kb, String query) {
        BigDecimal threshold = kb.getSimilarityThreshold() != null
                ? kb.getSimilarityThreshold() : DEFAULT_SIMILARITY_THRESHOLD;

        // 各取 HYBRID_PER_PATH_TOP_K（doVectorRetrieve 内部已做阈值过滤）
        List<Map<String, Object>> vectorHits = doVectorRetrieve(tenantId, kb, query);
        List<Map<String, Object>> keywordHits = doKeywordRetrieve(tenantId, kb, query);

        if ((vectorHits == null || vectorHits.isEmpty())
                && (keywordHits == null || keywordHits.isEmpty())) {
            return Collections.emptyList();
        }

        // 向量相关性锚点：HYBRID 语义上依赖向量路径提供"确实相关"的信号。
        // 如果向量路径被阈值全滤光，说明 query 与该库在语义上不相关 → 整体放弃。
        // 纯 keyword 偶然命中不应作为 RAG 引用（那是 KEYWORD 策略该做的事）。
        if ((vectorHits == null || vectorHits.isEmpty())
                && keywordHits != null && !keywordHits.isEmpty()) {
            log.info("HYBRID 向量路径为空（全部低于阈值 {}），keyword 命中 {} 条但整体放弃: kbId={}, query={}",
                    threshold, keywordHits.size(), kb.getId(), truncateForLog(query));
            return Collections.emptyList();
        }

        if (vectorHits == null || vectorHits.isEmpty()) {
            return Collections.emptyList();
        }
        if (keywordHits == null || keywordHits.isEmpty()) {
            return vectorHits;
        }

        List<Map<String, Object>> fused = fuseByRRF(vectorHits, keywordHits);
        log.info("HYBRID RRF 融合完成: kbId={}, vectorHits={}, keywordHits={}, fused={}",
                kb.getId(), vectorHits.size(), keywordHits.size(), fused.size());
        return fused;
    }

    /** 日志截断辅助：防止 query 过长刷屏 */
    private static String truncateForLog(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    /**
     * Reciprocal Rank Fusion 融合。
     *
     * <p>对两个排序列表按 rank 计算 1/(RRF_K + rank) 分数，相同文档块累加后取最高分，
     * 按 RRF 总分降序排列。以 {@code (docId, chunkIndex)} 作为去重 key。</p>
     */
    private List<Map<String, Object>> fuseByRRF(List<Map<String, Object>> vectorHits,
                                                List<Map<String, Object>> keywordHits) {
        // key = docId|chunkIndex, value = {item, rrfScore}
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        // 向量路径（rank 从 1 开始）
        for (int i = 0; i < vectorHits.size(); i++) {
            Map<String, Object> item = vectorHits.get(i);
            String key = buildChunkKey(item);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            merged.merge(key, buildRrfEntry(item, rrfScore), (old, nue) -> {
                old.put("rrfScore", (Double) old.get("rrfScore") + (Double) nue.get("rrfScore"));
                // 保留向量路径的 content（通常质量更好）
                if (old.get("content") == null) {
                    old.put("content", nue.get("content"));
                }
                return old;
            });
        }
        // 关键词路径
        for (int i = 0; i < keywordHits.size(); i++) {
            Map<String, Object> item = keywordHits.get(i);
            String key = buildChunkKey(item);
            double rrfScore = 1.0 / (RRF_K + i + 1);
            merged.merge(key, buildRrfEntry(item, rrfScore), (old, nue) -> {
                old.put("rrfScore", (Double) old.get("rrfScore") + (Double) nue.get("rrfScore"));
                if (old.get("content") == null) {
                    old.put("content", nue.get("content"));
                }
                return old;
            });
        }

        // 按 rrfScore 降序 → 整理结果
        return merged.values().stream()
                .sorted((a, b) -> Double.compare((Double) b.get("rrfScore"), (Double) a.get("rrfScore")))
                .map(entry -> {
                    Map<String, Object> result = new HashMap<>(6);
                    result.put("docId", entry.get("docId"));
                    result.put("content", entry.get("content"));
                    result.put("chunkIndex", entry.get("chunkIndex"));
                    result.put("score", entry.get("rrfScore"));
                    // 保留路径分数字段用于调试
                    if (entry.containsKey("vectorScore")) result.put("vectorScore", entry.get("vectorScore"));
                    if (entry.containsKey("keywordScore")) result.put("keywordScore", entry.get("keywordScore"));
                    result.put("rrfScore", entry.get("rrfScore"));
                    return result;
                })
                .collect(Collectors.toList());
    }

    /** 构建 (docId, chunkIndex) 去重 key。 */
    private static String buildChunkKey(Map<String, Object> item) {
        Object docId = item.get("docId");
        Object chunkIndex = item.get("chunkIndex");
        return String.valueOf(docId) + "|" + String.valueOf(chunkIndex);
    }

    /** 构建 RRF 条目。 */
    private static Map<String, Object> buildRrfEntry(Map<String, Object> source, double rrfScore) {
        Map<String, Object> entry = new HashMap<>(6);
        entry.put("docId", source.get("docId"));
        entry.put("content", source.get("content"));
        entry.put("chunkIndex", source.get("chunkIndex"));
        entry.put("rrfScore", rrfScore);
        if (source.containsKey("vectorScore")) entry.put("vectorScore", source.get("vectorScore"));
        if (source.containsKey("keywordScore")) entry.put("keywordScore", source.get("keywordScore"));
        return entry;
    }

    // ===== Rerank =====

    /**
     * LLM Rerank 重排序。异常时返回原顺序（由 DoubaoRerankService 内部保证）。
     */
    private List<Map<String, Object>> doRerank(String query, List<Map<String, Object>> rawResults, Long tenantId) {
        // 构造候选列表
        List<RerankService.RerankCandidate> candidates = new ArrayList<>(rawResults.size());
        for (Map<String, Object> item : rawResults) {
            String id = buildChunkKey(item);
            String content = item.get("content") != null ? item.get("content").toString() : "";
            float origScore = 0f;
            Object scoreObj = item.get("score");
            if (scoreObj instanceof Number n) origScore = n.floatValue();
            candidates.add(new RerankService.RerankCandidate(id, content, origScore));
        }

        List<RerankService.RerankResult> reranked = rerankService.rerank(query, candidates, tenantId);
        if (reranked == null || reranked.isEmpty()) {
            return rawResults;
        }

        // 按 RerankResult 映射回原始 item（以 id 匹配）
        Map<String, Map<String, Object>> itemMap = new LinkedHashMap<>();
        for (Map<String, Object> item : rawResults) {
            itemMap.put(buildChunkKey(item), item);
        }
        List<Map<String, Object>> output = new ArrayList<>(reranked.size());
        for (RerankService.RerankResult rr : reranked) {
            Map<String, Object> original = itemMap.get(rr.id());
            if (original == null) continue;
            Map<String, Object> item = new HashMap<>(original);
            item.put("score", rr.rerankScore());
            item.put("rerankScore", rr.rerankScore());
            output.add(item);
        }
        return output;
    }
}
