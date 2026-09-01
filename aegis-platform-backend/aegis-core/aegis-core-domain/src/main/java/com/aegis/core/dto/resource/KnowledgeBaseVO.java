package com.aegis.core.dto.resource;

import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.common.Visibility;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识库视图对象。
 *
 * <p>所有 Long 类型 ID 字段通过 {@code @JsonSerialize(ToStringSerializer)} 序列化为字符串，
 * 防止前端 JavaScript Number 精度丢失。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 知识库ID（雪花ID，序列化为字符串防止JS精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 租户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 知识库唯一编码 */
    private String kbCode;

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

    /** 创建者用户 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorUserId;

    /** 创建者部门 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorDeptId;

    /** 文档数量 */
    private Integer docCount;

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

    /** 订阅数 */
    private Integer subsCount;

    /** 最近发布时间 */
    private LocalDateTime publishedTime;

    /** 发布可见范围 */
    private Visibility visibility;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
