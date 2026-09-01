package com.aegis.core.domain.resource;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.aegis.core.enums.common.Visibility;

/**
 * 知识库实体
 *
 * <p>知识库（Knowledge Base）是智能体检索增强生成（RAG）的核心资源，承载文档切片、向量化、
 * 检索策略等配置，为智能体提供领域知识与上下文增强能力。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *     <li>文档管理：支持多格式文档上传、切片、向量化与状态追踪</li>
 *     <li>检索配置：支持切片策略、嵌入模型、Top-K、相似度阈值等参数调优</li>
 *     <li>检索增强：支持 Rerank 重排序与 Query Rewrite 查询改写</li>
 *     <li>资源治理：通过安全等级、生命周期、订阅评分实现知识库治理</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，知识库及其中文档均带 tenantId 隔离；
 * 发布到资源中心的知识库可被跨部门订阅，订阅需经审批。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see KbDocument
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("res_knowledge_base")
public class KnowledgeBase extends TenantEntity {
    /** 知识库唯一编码，租户内唯一，由字母、数字、下划线组成，长度不超过 64 */
    private String kbCode;
    /** 知识库展示名称，长度不超过 128 */
    private String kbName;
    /** 知识库图标 URL，可选 */
    private String icon;
    /** 知识库描述，长度不超过 512，说明知识库内容范围与适用场景 */
    private String description;
    /** 安全等级：{@link SecurityLevel#L1}~{@link SecurityLevel#L4}，影响知识库可见范围与检索权限 */
    private SecurityLevel securityLevel;
    /** 生命周期状态：{@link AgentLifeStatus#DRAFT}→{@link AgentLifeStatus#REVIEWING}→{@link AgentLifeStatus#PUBLISHED}→{@link AgentLifeStatus#ARCHIVED} */
    private AgentLifeStatus lifeStatus;
    /** 版本号，语义化版本如 1.0.0 */
    private String version;
    /** 创建者用户 ID，关联 user.id */
    private Long authorUserId;
    /** 创建者部门 ID，关联 department.id */
    private Long authorDeptId;
    /** 文档数量，知识库下有效文档总数，由系统自动统计 */
    private Integer docCount;
    /** 切片策略，取值：FIXED（固定长度）/ SENTENCE（按句）/ PARAGRAPH（按段）/ MARKDOWN（按标题） */
    private String chunkStrategy;
    /** 切片大小，每段最大字符数，取值范围 100-4000，默认 500 */
    private Integer chunkSize;
    /** 切片重叠量，相邻切片重叠字符数，取值范围 0-500，默认 50，用于保持上下文连续性 */
    private Integer chunkOverlap;
    /** 嵌入模型标识，影响向量化质量与维度 */
    private String embeddingModel;
    /** 检索策略，取值：VECTOR（向量检索）/ KEYWORD（关键词）/ HYBRID（混合检索） */
    private String retrievalStrategy;
    /** Top-K 值，检索返回的最大切片数，取值范围 1-20，默认 5 */
    private Integer topK;
    /** 相似度阈值，0-1 之间，低于阈值的切片将被过滤，默认 0.75 */
    private BigDecimal similarityThreshold;
    /** 是否启用 Rerank 重排序，true 时对初步检索结果二次排序提升相关性 */
    private Boolean enableRerank;
    /** 是否启用查询改写，true 时对用户原始查询进行扩展或改写以提升召回率 */
    private Boolean enableQueryRewrite;
    /** 订阅数，该知识库被其他智能体订阅的总次数 */
    private Integer subsCount;
    /** 最近发布时间，知识库从草稿转为已发布时写入 */
    private LocalDateTime publishedTime;

    /** 发布可见范围：{@link com.aegis.core.enums.common.Visibility#TENANT}（本租户可见，默认）/ {@link com.aegis.core.enums.common.Visibility#PUBLIC}（全平台可见） */
    private com.aegis.core.enums.common.Visibility visibility;
}