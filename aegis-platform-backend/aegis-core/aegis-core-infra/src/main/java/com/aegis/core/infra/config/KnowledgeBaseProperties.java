package com.aegis.core.infra.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * 知识库（KnowledgeBase）模块外部化配置属性。
 *
 * <p>替代 {@code KbConstants} 中的硬编码常量，通过 {@code aegis.kb.*} 配置项注入，
 * 支持 Nacos 热更新。KbConstants 中的常量保留作为默认值兜底（供未启用配置时使用），
 * Service 层优先从本 Properties 读取。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code aegis.kb.max-file-size-bytes}：单文件最大上传字节数（默认 50MB）</li>
 *   <li>{@code aegis.kb.presign-expire-minutes}：预签名 URL 有效期分钟（默认 30）</li>
 *   <li>{@code aegis.kb.chunk-size}：默认切片大小（默认 500）</li>
 *   <li>{@code aegis.kb.chunk-overlap}：默认切片重叠（默认 50）</li>
 *   <li>{@code aegis.kb.top-k}：检索默认 TopK（默认 5）</li>
 *   <li>{@code aegis.kb.similarity-threshold}：相似度阈值（默认 0.40）</li>
 *   <li>{@code aegis.kb.min-similarity-threshold}：相似度阈值下限（默认 0.15）</li>
 *   <li>{@code aegis.kb.default-embedding-model}：默认嵌入模型（默认 doubao-embedding-vision）</li>
 *   <li>{@code aegis.kb.default-retrieval-strategy}：默认检索策略（默认 VECTOR）</li>
 *   <li>{@code aegis.kb.sensitive-words}：敏感词拦截列表（默认 身份证,手机号）</li>
 *   <li>{@code aegis.kb.allowed-file-types}：允许的文件扩展名白名单</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.kb")
public class KnowledgeBaseProperties {

    /** 单文件最大上传字节数（默认 50MB）。 */
    private long maxFileSizeBytes = 50L * 1024 * 1024;

    /** 预签名 URL 有效期分钟（默认 30）。 */
    private int presignExpireMinutes = 30;

    /** 默认切片大小（默认 500）。 */
    private int chunkSize = 500;

    /** 默认切片重叠（默认 50）。 */
    private int chunkOverlap = 50;

    /** 检索默认 TopK（默认 5）。 */
    private int topK = 5;

    /** 默认相似度阈值（COSINE 量纲，默认 0.40）。 */
    private BigDecimal similarityThreshold = new BigDecimal("0.40");

    /** 相似度阈值下限（COSINE 量纲，默认 0.15）。 */
    private BigDecimal minSimilarityThreshold = new BigDecimal("0.15");

    /** 默认嵌入模型标识。 */
    private String defaultEmbeddingModel = "doubao-embedding-vision";

    /** 默认检索策略代码（VECTOR / HYBRID / KEYWORD）。 */
    private String defaultRetrievalStrategy = "VECTOR";

    /** 敏感词拦截列表（仅拦截高风险 PII 与安全关键字）。 */
    private Set<String> sensitiveWords = Set.of("身份证", "手机号");

    /** 允许的文件扩展名白名单（不区分大小写）。 */
    private List<String> allowedFileTypes = List.of(
            "TXT", "MD", "MARKDOWN", "HTML", "HTM", "CSV", "JSON", "XML",
            "PDF", "DOCX", "DOC", "XLSX", "XLS", "PPTX", "PPT");
}
