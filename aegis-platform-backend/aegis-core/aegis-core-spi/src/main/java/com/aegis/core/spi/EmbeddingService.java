package com.aegis.core.spi;

import java.util.List;

/**
 * 嵌入服务接口：文本向量化抽象。
 *
 * <p>屏蔽底层嵌入模型供应商差异（ARK/OpenAI/本地模型），
 * 供 DocumentPipelineService（文档入库）和 RagRetrieveService（查询检索）共用。
 *
 * <h3>实现要求</h3>
 * <ul>
 *   <li>支持批量嵌入：一次 API 调用处理多个文本片段，减少网络开销</li>
 *   <li>维度自适应：不同模型维度不同，实现应从 API 响应中读取实际维度</li>
 *   <li>错误降级：API 调用失败时记录错误日志并抛出异常，不静默回退到 stub</li>
 * </ul>
 *
 * <h3>已知实现</h3>
 * <ul>
 *   <li>{@code ArkEmbeddingService} - 默认嵌入模型实现</li>
 * </ul>
 *
 * @author wang.zhen
 */
public interface EmbeddingService {

    /**
     * 单文本嵌入。
     *
     * @param text 输入文本
     * @return 嵌入向量（维度由模型决定）
     */
    float[] embed(String text);

    /**
     * 批量文本嵌入。
     *
     * @param texts 输入文本列表
     * @return 向量数组，与输入一一对应
     */
    float[][] embedBatch(List<String> texts);

    /**
     * 获取当前嵌入模型的维度。
     *
     * <p>供 MilvusVectorStoreAdapter.ensureCollection() 使用，
     * 确保向量集合维度与模型输出一致。
     *
     * @return 向量维度
     */
    int getDimension();
}
