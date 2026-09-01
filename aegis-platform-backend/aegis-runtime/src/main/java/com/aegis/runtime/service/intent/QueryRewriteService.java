package com.aegis.runtime.service.intent;

import java.util.List;

/**
 * 查询改写服务接口。
 *
 * <p>提供 RAG 链路的查询改写能力，在检索前对用户原始 query 进行多轮上下文消解，
 * 提升向量检索与关键词检索的召回率与精确度。</p>
 *
 * <h3>能力</h3>
 * <ul>
 *   <li>共指消解：将多轮对话中的代词（它/这个/那个）替换为具体实体</li>
 * </ul>
 *
 * @author wang.zhen
 */
public interface QueryRewriteService {

    /**
     * 共指消解：基于历史上下文将当前 query 改写为独立检索 query。
     *
     * @param rawQuery       用户最新原始查询
     * @param recentHistory  最近对话历史（按时间正序），可为 null 或空
     * @param tenantId       租户 ID
     * @return 改写后的独立检索 query；改写失败时返回原始 query（永不返回 null）
     */
    String resolveCoreference(String rawQuery, List<String> recentHistory, Long tenantId);
}
