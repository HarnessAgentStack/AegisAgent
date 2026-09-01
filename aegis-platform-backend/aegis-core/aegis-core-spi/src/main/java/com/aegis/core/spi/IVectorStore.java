package com.aegis.core.spi;

import java.util.List;
import java.util.Map;
import com.aegis.core.domain.resource.KnowledgeBase;

/**
 * 向量存储协议。
 *
 * <p>抽象知识库语义检索的统一存储协议，屏蔽底层向量库实现差异（Milvus / Qdrant / PG-Vector 等）。
 * 支持按租户隔离集合、向量 Upsert 与 TopK 相似检索，为知识库 RAG 与技能匹配提供底座。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>租户隔离：集合按租户命名空间划分（如 tenant_{id}_{kb}），杜绝跨租户检索</li>
 *   <li>Upsert 幂等：按主键覆盖写入，支持增量更新</li>
 *   <li>检索返回带分数的命中列表，分数用于后续重排与阈值过滤</li>
 *   <li>实现可由响应式模块通过 {@code Mono.fromCallable} 包装为非阻塞调用</li>
 * </ul>
 *
 * <p>本协议为同步契约，保持 aegis-core 不引入响应式框架；响应式适配由消费模块完成。
 *
 * @author wang.zhen
 * @see com.aegis.core.domain.resource.KnowledgeBase
 */
public interface IVectorStore {

    /**
     * 确保集合存在，不存在则按维度创建。
     *
     * @param tenantId  租户ID
     * @param collection 集合名（已含租户命名空间）
     * @param dimension  向量维度
     * @return true 表示新建，false 表示已存在
     */
    boolean ensureCollection(Long tenantId, String collection, int dimension);

    /**
     * 批量 Upsert 向量与元数据。
     *
     * @param tenantId   租户ID
     * @param collection 集合名
     * @param vectors    向量记录列表（主键/向量/元数据）
     */
    void upsert(Long tenantId, String collection, List<VectorRecord> vectors);

    /**
     * TopK 相似检索。
     *
     * @param tenantId   租户ID
     * @param collection 集合名
     * @param queryVector 查询向量
     * @param topK       返回条数
     * @param filter     元数据过滤条件（可为 null）
     * @return 带分数的命中列表，按相似度降序
     */
    List<SearchHit> search(Long tenantId, String collection, float[] queryVector, int topK, Map<String, Object> filter);

    /**
     * 按主键删除向量。
     *
     * @param tenantId   租户ID
     * @param collection 集合名
     * @param ids        主键列表
     */
    void delete(Long tenantId, String collection, List<String> ids);

    /** 向量记录（主键 + 向量 + 元数据）。 */
    class VectorRecord {
        /** 主键（通常为文档块ID） */
        public String id;
        /** 向量数据 */
        public float[] vector;
        /** 元数据（如 docId、source、chunkIndex） */
        public Map<String, Object> metadata;
    }

    /** 检索命中（主键 + 分数 + 元数据）。 */
    class SearchHit {
        /** 主键 */
        public String id;
        /** 相似度分数（越高越相似） */
        public float score;
        /** 元数据 */
        public Map<String, Object> metadata;
    }
}
