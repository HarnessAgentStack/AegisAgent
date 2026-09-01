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
 * 知识库更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库展示名称 */
    private String kbName;

    /** 知识库图标 URL */
    private String icon;

    /** 知识库描述 */
    private String description;

    /** 安全等级 */
    private SecurityLevel securityLevel;

    /** 生命周期状态 */
    private AgentLifeStatus lifeStatus;

    /** 版本号 */
    private String version;

    /** 切片策略 */
    private String chunkStrategy;

    /** 切片大小 */
    private Integer chunkSize;

    /** 切片重叠量 */
    private Integer chunkOverlap;

    /** 嵌入模型标识 */
    private String embeddingModel;

    /** 检索策略 */
    private String retrievalStrategy;

    /** Top-K 值 */
    private Integer topK;

    /** 相似度阈值 */
    private BigDecimal similarityThreshold;

    /** 是否启用 Rerank 重排序 */
    private Boolean enableRerank;

    /** 是否启用查询改写 */
    private Boolean enableQueryRewrite;

    /** 发布可见范围 */
    private Visibility visibility;
}
