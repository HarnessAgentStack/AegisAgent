package com.aegis.dal.mapper.resource;

import com.aegis.core.domain.resource.KbDocumentChunk;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 知识库文档切片 Mapper。
 *
 * @author wang.zhen  
 */
@Mapper
public interface KbDocumentChunkMapper extends BaseMapper<KbDocumentChunk> {

    /**
     * MySQL FULLTEXT 自然语言检索。
     *
     * <p>依赖表上已存在 {@code FULLTEXT KEY}（如 {@code FULLTEXT KEY ft_content (content)}）。
     * 若索引不存在会抛出 SQL 错误 1191，调用方需降级处理。</p>
     *
     * @param tenantId 租户 ID
     * @param kbId     知识库 ID
     * @param query    检索 query（MySQL NATURAL LANGUAGE MODE 会自动分词去停用词）
     * @param topK     返回条数上限
     * @return 命中切片列表，每条含 id、docId、chunkIndex、content、bm25Score（MySQL MATCH 分值）
     */
    @Select("SELECT id, doc_id as docId, chunk_index as chunkIndex, content, " +
            "MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) as bm25Score " +
            "FROM res_kb_document_chunk " +
            "WHERE tenant_id = #{tenantId} AND kb_id = #{kbId} AND deleted = 0 " +
            "ORDER BY bm25Score DESC LIMIT #{topK}")
    List<Map<String, Object>> keywordSearch(@Param("tenantId") Long tenantId,
                                            @Param("kbId") Long kbId,
                                            @Param("query") String query,
                                            @Param("topK") int topK);
}
