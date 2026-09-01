package com.aegis.core.dto.model;

import com.aegis.core.enums.model.ProviderStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模型提供商视图对象。
 *
 * <p>apiKey 以脱敏形式返回（apiKeyMasked），不暴露原始密钥。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelProviderVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提供商ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 提供商唯一编码 */
    private String providerCode;

    /** 提供商展示名称 */
    private String providerName;

    /** 服务接入端点，API 基础 URL */
    private String endpoint;

    /** API Key 脱敏值（前6位***...***后4位） */
    private String apiKeyMasked;

    /** 状态：ACTIVE（已接入）/ PENDING（待接入） */
    private ProviderStatus status;

    /** 模型数量 */
    private Integer modelCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
