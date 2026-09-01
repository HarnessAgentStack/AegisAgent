package com.aegis.core.spi;

import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 向量存储 Noop 实现。
 *
 * <p>当 Milvus 等向量库未部署时作为兜底实现，保证服务可启动。
 * 所有操作均为空操作并打印 warn 日志。
 *
 * @author wang.zhen
 */
@Slf4j
public class NoopVectorStoreAdapter implements IVectorStore {

    @Override
    public boolean ensureCollection(Long tenantId, String collection, int dimension) {
        log.warn("NoopVectorStore: ensureCollection skipped (tenantId={}, collection={})", tenantId, collection);
        return false;
    }

    @Override
    public void upsert(Long tenantId, String collection, List<VectorRecord> vectors) {
        log.warn("NoopVectorStore: upsert skipped (tenantId={}, collection={}, count={})", tenantId, collection, vectors.size());
    }

    @Override
    public List<SearchHit> search(Long tenantId, String collection, float[] queryVector, int topK, Map<String, Object> filter) {
        log.warn("NoopVectorStore: search skipped (tenantId={}, collection={})", tenantId, collection);
        return Collections.emptyList();
    }

    @Override
    public void delete(Long tenantId, String collection, List<String> ids) {
        log.warn("NoopVectorStore: delete skipped (tenantId={}, collection={}, count={})", tenantId, collection, ids.size());
    }
}
