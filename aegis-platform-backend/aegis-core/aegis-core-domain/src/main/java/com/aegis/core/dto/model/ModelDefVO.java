package com.aegis.core.dto.model;

import com.aegis.core.enums.model.ModelStatus;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.core.enums.model.ModelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型定义视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDefVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型ID */
    private Long id;

    /** 所属提供商ID */
    private Long providerId;

    /** 所属提供商名称（关联填充） */
    private String providerName;

    /** 所属提供商编码（关联填充） */
    private String providerCode;

    /** 模型唯一编码 */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 模型类型：TEXT / MULTIMODAL / EMBEDDING / VISION */
    private ModelType modelType;

    /** 模型层级：LIGHT / STANDARD / STRONG（前端字段名 tier） */
    private ModelTier tier;

    /** 上下文窗口大小，单位token */
    private Integer contextWindow;

    /** 最大输出token数 */
    private Integer maxTokens;

    /** 输入计费单价，单位元/千token */
    private BigDecimal inputPrice;

    /** 输出计费单价，单位元/千token */
    private BigDecimal outputPrice;

    /** 模型能力矩阵（JSON字符串） */
    private String capabilities;

    /** 状态：ENABLED（启用）/ DISABLED（禁用） */
    private ModelStatus status;

    /** QPS限制 */
    private Integer qpsLimit;

    /** 平均延迟，单位毫秒 */
    private BigDecimal latency;

    /** 创建时间 */
    private LocalDateTime createTime;
}
