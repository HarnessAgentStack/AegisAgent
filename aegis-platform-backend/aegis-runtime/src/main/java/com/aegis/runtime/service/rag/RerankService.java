package com.aegis.runtime.service.rag;

import java.util.List;

/**
 * Rerank 重排序服务接口。
 *
 * <p>对初步召回结果（向量检索 / 关键词检索 / 混合检索）进行二次排序，
 * 提升检索结果与用户 query 的相关性，降低噪声。启用时，RAG 链路在最终返回前
 * 调用 rerank 取最优 topN。</p>
 *
 * <h3>能力</h3>
 * <ul>
 *   <li>Candidate：携带原始 id、内容片段、召回阶段得分</li>
 *   <li>Result：携带 rerank 得分与原始得分，供上层融合展示</li>
 * </ul>
 *
 * <h3>降级约定</h3>
 * <p>实现必须保证异常或超时场景下返回原顺序候选，禁止抛出异常向上传播。</p>
 *
 * @author wang.zhen
 */
public interface RerankService {

    /**
     * 对召回结果二次排序。
     *
     * @param query      检索 query
     * @param candidates 候选列表（来自召回阶段），按原始得分降序
     * @param tenantId   租户 ID
     * @return 重排后的结果列表（按 rerankScore 降序）；异常时返回原顺序
     */
    List<RerankResult> rerank(String query, List<RerankCandidate> candidates, Long tenantId);

    /**
     * 重排候选。
     *
     * @param id           候选唯一标识（如 docId + chunkIndex）
     * @param content      候选内容文本
     * @param originalScore 召回阶段的原始相似度/关键词得分
     */
    record RerankCandidate(String id, String content, float originalScore) {}

    /**
     * 重排结果。
     *
     * @param id         候选唯一标识
     * @param rerankScore 重排相关性得分（越高越相关）
     * @param originalScore 召回阶段的原始得分（供调试展示）
     */
    record RerankResult(String id, float rerankScore, float originalScore) {}
}
