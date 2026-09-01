package com.aegis.core.dto.model;

import com.aegis.core.enums.model.ProviderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 模型提供商更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelProviderUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提供商唯一编码 */
    private String providerCode;

    /** 提供商展示名称 */
    private String providerName;

    /** 提供商类型 */
    private String providerType;

    /** 服务接入端点，API 基础 URL（前端字段名 endpoint） */
    private String endpoint;

    /** API 密钥，敏感字段，存储时加密 */
    private String apiKey;

    /** 支持的模型列表 */
    private List<String> supportedModels;

    /** 状态：ACTIVE（已接入）/ PENDING（待接入） */
    private ProviderStatus status;
}
