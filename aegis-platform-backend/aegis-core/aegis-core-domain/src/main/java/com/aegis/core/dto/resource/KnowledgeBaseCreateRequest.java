package com.aegis.core.dto.resource;

import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 知识库创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库唯一编码，租户内唯一 */
    private String kbCode;

    /** 知识库展示名称 */
    private String kbName;

    /** 知识库图标 URL */
    private String icon;

    /** 知识库描述 */
    private String description;

    /** 安全等级：L1~L4 */
    private SecurityLevel securityLevel;

    /** 生命周期状态：DRAFT / REVIEWING / PUBLISHED / ARCHIVED */
    private AgentLifeStatus lifeStatus;

    /** 版本号，语义化版本如 1.0.0 */
    private String version;

    /** 创建者用户 ID */
    private Long authorUserId;

    /** 创建者部门 ID */
    private Long authorDeptId;

    /** 切片策略：FIXED / SENTENCE / PARAGRAPH / MARKDOWN */
    private String chunkStrategy;

    /** 切片大小，每段最大字符数 */
    private Integer chunkSize;

    /** 切片重叠量 */
    private Integer chunkOverlap;

    /** 嵌入模型标识 */
    private String embeddingModel;

    /** 检索策略：VECTOR / KEYWORD / HYBRID */
    private String retrievalStrategy;

    /** Top-K 值，检索返回的最大切片数 */
    private Integer topK;

    /** 相似度阈值，0-1 之间 */
    private BigDecimal similarityThreshold;

    /** 是否启用 Rerank 重排序 */
    private Boolean enableRerank;

    /** 是否启用查询改写 */
    private Boolean enableQueryRewrite;

    /** 发布可见范围：TENANT / PUBLIC */
    private Visibility visibility;
}
