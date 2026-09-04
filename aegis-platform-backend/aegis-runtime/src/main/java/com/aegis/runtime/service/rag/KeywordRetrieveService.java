package com.aegis.runtime.service.rag;

import com.aegis.dal.mapper.resource.KbDocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 关键词检索服务（MySQL FULLTEXT）。
 *
 * <p>基于 MySQL FULLTEXT 索引执行 {@code MATCH(content) AGAINST(query IN NATURAL LANGUAGE MODE)}
 * 自然语言检索，作为向量检索的辅助路径，支撑 KB {@link com.aegis.core.domain.resource.KnowledgeBase}
 * 的三种检索策略：VECTOR / KEYWORD / HYBRID。</p>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>FULLTEXT 索引不存在（MySQL 表未建 {@code FULLTEXT KEY}）→ catch 异常返回空列表</li>
 *   <li>查询为 null / 空 / 全停用词 → 返回空列表</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordRetrieveService {

    private final KbDocumentChunkMapper kbDocumentChunkMapper;

    /**
     * MySQL FULLTEXT 关键词召回。
     *
     * @param tenantId 租户 ID（MySQL 行级过滤器会自动追加 tenant_id 条件；此处亦显式传入）
     * @param kbId     知识库 ID
     * @param query    检索 query
     * @param topK     返回条数上限
     * @return 命中的切片列表；索引缺失或查询无匹配时返回空列表
     */
    public List<Map<String, Object>> keywordRetrieve(Long tenantId, Long kbId, String query, int topK) {
        if (query == null || query.isBlank()) {
            log.debug("keywordRetrieve: query 为空，跳过: kbId={}", kbId);
            return Collections.emptyList();
        }
        int effectiveTopK = topK > 0 ? topK : 10;
        try {
            List<Map<String, Object>> hits = kbDocumentChunkMapper.keywordSearch(tenantId, kbId, query, effectiveTopK);
            if (hits == null || hits.isEmpty()) {
                log.debug("keywordRetrieve 无命中: kbId={}, query={}", kbId, truncate(query, 50));
                return Collections.emptyList();
            }
            // R-3 相对阈值：保留 bm25Score >= top1 × 0.2，滤除字面偶然命中（MATCH 分值>0 即返回会混入无关片段）
            float topScore = 0f;
            for (Map<String, Object> h : hits) {
                Object s = h.get("bm25Score");
                if (s instanceof Number n) {
                    topScore = Math.max(topScore, n.floatValue());
                }
            }
            float cutoff = topScore * 0.2f;
            List<Map<String, Object>> filtered = new ArrayList<>(hits.size());
            for (Map<String, Object> h : hits) {
                Object s = h.get("bm25Score");
                float sc = s instanceof Number n ? n.floatValue() : 0f;
                if (sc >= cutoff) {
                    filtered.add(h);
                }
            }
            log.debug("keywordRetrieve 命中 {} 条（相对阈值 cutoff={} 后 {}）: kbId={}, query={}",
                    hits.size(), cutoff, filtered.size(), kbId, truncate(query, 50));
            return filtered;
        } catch (Exception e) {
            // 典型异常：索引不存在（SQL 错误 1191）、query 含停用词、表不存在等
            log.warn("keywordRetrieve 失败（可能 FULLTEXT 索引未建），降级为空列表: kbId={}, error={}",
                    kbId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
