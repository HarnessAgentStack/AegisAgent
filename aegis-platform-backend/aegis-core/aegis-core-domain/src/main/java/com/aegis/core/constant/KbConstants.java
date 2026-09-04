package com.aegis.core.constant;

import java.util.Set;

/**
 * 知识库统一常量定义。
 *
 * <p>集中管理知识库模块的所有常量。
 *
 *  @author wang.zhen  
 */
public final class KbConstants {

    private KbConstants() {
    }

    /**
     * 向量集合命名空间前缀（含版本号，便于后续迁移）。
     * 格式：aegis_kb_v1_{kbId}
     */
    public static final String VECTOR_COLLECTION_PREFIX = "aegis_kb_v1_";

    /**
     * 对象存储路径前缀。
     * 格式：kb/{kbId}/
     */
    public static final String OSS_PATH_PREFIX = "kb/";

    /** 最大文件上传大小（50MB） */
    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    /** 预签名URL有效期（分钟） */
    public static final int PRESIGN_EXPIRE_MINUTES = 30;

    /** 允许的文件类型白名单 */
    public static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            "TXT", "MD", "MARKDOWN", "HTML", "HTM", "CSV", "JSON", "XML",
            "PDF", "DOCX", "DOC", "XLSX", "XLS", "PPTX", "PPT"
    );

    /** 敏感词列表（仅拦截高风险 PII 与安全关键字）。 */
    public static final Set<String> SENSITIVE_WORDS = Set.of(
            "身份证", "手机号"
    );

    /** 切片策略枚举 */
    public static final String STRATEGY_FIXED = "FIXED";
    public static final String STRATEGY_SENTENCE = "SENTENCE";
    public static final String STRATEGY_PARAGRAPH = "PARAGRAPH";
    public static final String STRATEGY_MARKDOWN = "MARKDOWN";

    /** 默认切片配置 */
    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_CHUNK_OVERLAP = 50;
    public static final int DEFAULT_TOP_K = 5;
    /** 默认相似度阈值（COSINE 量纲）。R-5：0.30→0.40，与 DDL 注释 0.40 对齐，消除文档-代码漂移；
     *  doubao 嵌入实测相关区间约 0.42~0.61，0.30 会放入噪声。 */
    public static final java.math.BigDecimal DEFAULT_SIMILARITY_THRESHOLD = new java.math.BigDecimal("0.40");
    /** 相似度阈值下限（COSINE 量纲），创建/更新知识库时统一钳制。R-5：0.15→0.25。 */
    public static final java.math.BigDecimal MIN_SIMILARITY_THRESHOLD = new java.math.BigDecimal("0.25");
    public static final String DEFAULT_EMBEDDING_MODEL = "doubao-embedding-vision";
    /** R-4：默认检索策略 VECTOR→HYBRID，向量+关键词 RRF 融合，专有名词由关键词路径兜底召回 */
    public static final String DEFAULT_RETRIEVAL_STRATEGY = "HYBRID";

    /** 嵌入模型提供商代码 */
    public static final String PROVIDER_VOLCENGINE = "volcengine";
    public static final String MODEL_CODE_DOUBAO_EMBEDDING = "doubao-embedding-vision";

    /** 智能切片策略映射：根据文件扩展名自动选择最佳策略 */
    public static String suggestStrategy(String fileType) {
        if (fileType == null) return STRATEGY_FIXED;
        String upper = fileType.toUpperCase();
        return switch (upper) {
            case "MD", "MARKDOWN" -> STRATEGY_MARKDOWN;
            case "TXT", "CSV", "JSON", "XML" -> STRATEGY_PARAGRAPH;
            case "DOCX", "DOC" -> STRATEGY_PARAGRAPH;
            case "PDF" -> STRATEGY_SENTENCE;
            default -> STRATEGY_FIXED;
        };
    }
}
