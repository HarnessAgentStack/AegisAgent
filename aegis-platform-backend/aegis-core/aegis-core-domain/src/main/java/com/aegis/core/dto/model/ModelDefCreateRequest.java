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

/**
 * 模型定义创建请求。
 *
 * <p>字段命名对齐前端 ModelInstanceForm（tier / capabilities 对象）。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelDefCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 所属提供商ID */
    private Long providerId;

    /** 模型唯一编码，提供商内唯一 */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 模型类型：TEXT / MULTIMODAL / EMBEDDING / VISION */
    private ModelType modelType;

    /** 模型层级：LIGHT / STANDARD / STRONG（前端字段名 tier） */
    private ModelTier tier;

    /** 上下文窗口大小，单位token */
    private Integer contextWindow;

    /** 最大输出token数（前端 maxOutputTokens） */
    private Integer maxTokens;

    /** 输入计费单价，单位元/千token */
    private BigDecimal inputPrice;

    /** 输出计费单价，单位元/千token */
    private BigDecimal outputPrice;

    /**
     * 模型能力矩阵。
     * <p>前端传 JSON 对象，Service 层序列化为字符串入库。
     */
    private Object capabilities;

    /** 状态：ENABLED（启用）/ DISABLED（禁用） */
    private ModelStatus status;
}
