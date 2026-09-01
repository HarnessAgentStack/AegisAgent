package com.aegis.core.domain.model;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.model.ModelType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 模型定义实体
 *
 * <p>模型定义（ModelDef）描述平台内可用的大模型实例，关联所属提供商，
 * 记录模型层级、性能指标与费用信息，为模型路由与智能体调用提供基础数据。</p>
 *
 * <h3>能力矩阵</h3>
 * <p>通过 {@link #capabilities} 字段（JSON）描述模型的多维能力（多模态/文档/视觉等），
 * 供 Runtime 能力协商层动态决定附件处理策略。</p>
 *
 * <h3>模型用途</h3>
 * <p>{@link #modelType} 区分模型的主要用途（TEXT/MULTIMODAL/EMBEDDING/VISION），
 * 用于智能体按场景选择合适模型。</p>
 *
 * @author wang.zhen
 * @see ModelProvider
 * @see ModelCapability
 * @see ModelType
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("model_def")
public class ModelDef extends BaseEntity {
    /** 所属提供商 ID，关联 model_provider.id */
    private Long providerId;
    /** 模型编码（如 doubao-seed-2.0-lite），与 provider_id 联合唯一 */
    private String modelCode;
    /** 模型展示名称 */
    private String modelName;
    /** 模型层级：LIGHT（轻量）、STANDARD（标准）、STRONG（强力） */
    private ModelTier tier;
    /** 模型用途：TEXT/MULTIMODAL/EMBEDDING/VISION */
    private ModelType modelType;
    /** 上下文窗口（token） */
    private Integer contextWindow;
    /** 输入单价（元/千token） */
    private BigDecimal inputCost;
    /** 输出单价（元/千token） */
    private BigDecimal outputCost;
    /** QPS 限制 */
    private Integer qpsLimit;
    /** 启用状态：ENABLED/DISABLED */
    private String status;
    /** 平均延迟（ms） */
    private BigDecimal latency;
    /** 质量等级：S_PLUS/S/A_PLUS/A/B_PLUS */
    private String qualityGrade;
    /** 模型能力矩阵（JSON 格式） */
    private String capabilities;
}
