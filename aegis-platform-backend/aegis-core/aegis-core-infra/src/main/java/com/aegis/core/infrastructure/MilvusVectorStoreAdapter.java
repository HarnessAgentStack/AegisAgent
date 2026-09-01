package com.aegis.core.infrastructure;

import com.aegis.core.spi.IVectorStore;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 向量库适配器。
 *
 * <p>实现 {@link IVectorStore} SPI，按租户命名空间隔离集合，承载知识库文档向量，
 * 支撑 RAG 语义检索。
 *
 * <h3>集合命名</h3>
 * <pre>tenant_{tenantId}_{collection}</pre>
 *
 * <h3>索引配置</h3>
 * <ul>
 *   <li>索引类型：IVF_FLAT（nlist=1024）</li>
 *   <li>度量方式：COSINE（余弦相似度）</li>
 *   <li>字段：id(VarChar主键) + vector(FloatVector) + doc_id(Int64) + chunk_index(Int64) + content(VarChar)</li>
 * </ul>
 *
 * @author wang.zhen
 * @see IVectorStore
 */
@Slf4j
@RequiredArgsConstructor
public class MilvusVectorStoreAdapter implements IVectorStore {

    private final MilvusServiceClient milvusClient;

    /** 默认向量维度 */
    private static final int DEFAULT_DIMENSION = 1024;

    @Override
    public boolean ensureCollection(Long tenantId, String collection, int dimension) {
        String fullCollection = buildCollectionName(tenantId, collection);
        int dim = dimension > 0 ? dimension : DEFAULT_DIMENSION;
        try {
            boolean hasCollection = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withCollectionName(fullCollection).build()).getData();
            if (hasCollection) {
                log.debug("Milvus 集合已存在: {}", fullCollection);
                return true;  // 集合已存在，视为可用
            }

            FieldType idField = FieldType.newBuilder()
                    .withName("id").withDataType(DataType.VarChar).withMaxLength(64)
                    .withPrimaryKey(true).withAutoID(false).build();
            FieldType vectorField = FieldType.newBuilder()
                    .withName("vector").withDataType(DataType.FloatVector).withDimension(dim).build();
            FieldType docIdField = FieldType.newBuilder()
                    .withName("doc_id").withDataType(DataType.Int64).build();
            FieldType chunkIndexField = FieldType.newBuilder()
                    .withName("chunk_index").withDataType(DataType.Int64).build();
            FieldType contentField = FieldType.newBuilder()
                    .withName("content").withDataType(DataType.VarChar).withMaxLength(4096).build();

            CreateCollectionParam param = CreateCollectionParam.newBuilder()
                    .withCollectionName(fullCollection)
                    .addFieldType(idField)
                    .addFieldType(vectorField)
                    .addFieldType(docIdField)
                    .addFieldType(chunkIndexField)
                    .addFieldType(contentField)
                    .build();
            milvusClient.createCollection(param);

            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(fullCollection)
                    .withFieldName("vector")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\":1024}")
                    .build());

            // loadCollection 可能因 protobuf 版本冲突而失败，使用 try-catch 包装
            try {
                milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(fullCollection).build());
            } catch (Exception | Error e) {
                log.warn("Milvus 集合加载失败（不影响功能，将在首次搜索时自动加载）: {}", e.getMessage());
            }

            log.info("Milvus 集合创建成功: {}, dimension={}", fullCollection, dim);
            return true;
        } catch (Exception | Error e) {
            log.error("Milvus 集合创建失败: {}", fullCollection, e);
            throw new RuntimeException("Milvus 集合创建失败: " + fullCollection, e);
        }
    }

    @Override
    public void upsert(Long tenantId, String collection, List<VectorRecord> vectors) {
        String fullCollection = buildCollectionName(tenantId, collection);
        try {
            List<JsonObject> rows = vectors.stream().map(record -> {
                JsonObject row = new JsonObject();
                row.addProperty("id", record.id);
                com.google.gson.JsonArray vecArray = new com.google.gson.JsonArray();
                for (float v : record.vector) {
                    vecArray.add(v);
                }
                row.add("vector", vecArray);
                if (record.metadata != null) {
                    Object docId = record.metadata.get("doc_id");
                    row.addProperty("doc_id", docId != null ? ((Number) docId).longValue() : 0L);
                    Object chunkIndex = record.metadata.get("chunk_index");
                    row.addProperty("chunk_index", chunkIndex != null ? ((Number) chunkIndex).longValue() : 0L);
                    Object content = record.metadata.get("content");
                    row.addProperty("content", content != null ? content.toString() : "");
                }
                return row;
            }).collect(Collectors.toList());

            milvusClient.insert(InsertParam.newBuilder()
                    .withCollectionName(fullCollection)
                    .withRows(rows)
                    .build());
            log.debug("Milvus 向量写入成功: {}, count={}", fullCollection, vectors.size());
        } catch (Exception e) {
            log.error("Milvus 向量写入失败: {}", fullCollection, e);
            throw new RuntimeException("Milvus 向量写入失败: " + fullCollection, e);
        }
    }

    @Override
    public List<SearchHit> search(Long tenantId, String collection, float[] queryVector,
                                   int topK, Map<String, Object> filter) {
        String fullCollection = buildCollectionName(tenantId, collection);
        try {
            List<Float> floatList = new ArrayList<>();
            for (float f : queryVector) {
                floatList.add(f);
            }

            SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                    .withCollectionName(fullCollection)
                    .withVectorFieldName("vector")
                    .withFloatVectors(List.of(floatList))
                    .withTopK(topK)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(List.of("id", "doc_id", "chunk_index", "content"));

            if (filter != null && !filter.isEmpty()) {
                String expr = filter.entrySet().stream()
                        .map(e -> e.getKey() + " == " + e.getValue())
                        .collect(Collectors.joining(" and "));
                searchBuilder.withExpr(expr);
            }

            io.milvus.grpc.SearchResults results = milvusClient.search(searchBuilder.build()).getData();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(results.getResults());

            List<SearchHit> hits = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
                SearchHit hit = new SearchHit();
                hit.id = idScore.getStrID();
                // Milvus v2.5 COSINE metric 直接返回 cosine similarity（范围 [-1, 1]，越大越相似）
                // 实证：self-search=1.0，随机向量≈0.0，不需要 1-distance 转换
                hit.score = idScore.getScore();
                hit.metadata = new HashMap<>();
                hit.metadata.put("doc_id", wrapper.getFieldData("doc_id", 0).get(i));
                hit.metadata.put("chunk_index", wrapper.getFieldData("chunk_index", 0).get(i));
                hit.metadata.put("content", wrapper.getFieldData("content", 0).get(i));
                hits.add(hit);
            }
            log.debug("Milvus 检索完成: {}, topK={}, hits={}", fullCollection, topK, hits.size());
            return hits;
        } catch (Exception e) {
            log.error("Milvus 检索失败: {}", fullCollection, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void delete(Long tenantId, String collection, List<String> ids) {
        String fullCollection = buildCollectionName(tenantId, collection);
        try {
            String expr = "id in [" + ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(",")) + "]";
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(fullCollection)
                    .withExpr(expr)
                    .build());
            log.debug("Milvus 向量删除成功: {}, count={}", fullCollection, ids.size());
        } catch (Exception e) {
            log.error("Milvus 向量删除失败: {}", fullCollection, e);
        }
    }

    /** 构建租户隔离的集合名 */
    private String buildCollectionName(Long tenantId, String collection) {
        return "tenant_" + tenantId + "_" + collection;
    }
}
