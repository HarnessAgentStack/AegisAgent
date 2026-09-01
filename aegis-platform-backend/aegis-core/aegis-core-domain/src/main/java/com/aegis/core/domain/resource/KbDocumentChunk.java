package com.aegis.core.domain.resource;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 知识库文档切片实体。
 *
 * <p>存储文档切片的实际内容，用于检索溯源。
 * 每个文档处理完成后，切片结果会同步写入本表，与 Milvus 向量存储保持对应关系。
 *
 * <h3>与向量库的关系</h3>
 * <ul>
 *   <li>本表存储切片文本内容</li>
 *   <li>Milvus 存储切片的向量表示，用于语义检索</li>
 *   <li>通过 doc_id + chunk_index 关联</li>
 * </ul>
 *
 *  @author wang.zhen  
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_kb_document_chunk")
public class KbDocumentChunk extends TenantEntity {
    /** 所属知识库 ID */
    private Long kbId;
    /** 所属文档 ID，关联 res_kb_document.id */
    private Long docId;
    /** 切片序号，从0开始，用于排序和溯源 */
    private Integer chunkIndex;
    /** 切片文本内容 */
    private String content;
    /** 切片Token数量（估算值） */
    private Integer tokenCount;
    /** 切片字符数 */
    private Integer charCount;
    /** 切片元数据，JSON格式 */
    private String metadata;
}
