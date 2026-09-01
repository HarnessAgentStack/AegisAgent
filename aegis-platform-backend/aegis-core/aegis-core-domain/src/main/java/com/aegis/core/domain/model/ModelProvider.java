package com.aegis.core.domain.model;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.model.ProviderStatus;
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
 * 模型提供商实体
 *
 * <p>模型提供商（ModelProvider）是平台级大模型服务提供方注册实体，由管理员统一配置，
 * 管理各厂商（如 OpenAI、Anthropic、阿里云等）的接入信息、配额与可用模型。</p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *     <li>平台级：继承自 {@link BaseEntity}，无 tenantId 隔离，全租户共享</li>
 *     <li>接入管理：维护 endpoint、apiKey 等接入凭证，支持多区域部署</li>
 *     <li>配额管控：通过 monthlyQuota 与 usedQuota 跟踪月度配额消耗</li>
 *     <li>限流保护：qpsLimit 限制单提供商最大请求速率，防止超额调用</li>
 * </ul>
 *
 * <h3>安全说明</h3>
 * <p>apiKey 为敏感字段，存储时需加密，日志输出需脱敏；
 * 提供商配置变更需经管理员审批，并记录审计日志。</p>
 *
 * @author wang.zhen
 * @see BaseEntity
 * @see ModelDef
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("model_provider")
public class ModelProvider extends BaseEntity {
    /** 提供商唯一编码，全局唯一，如 openai、anthropic、qwen，长度不超过 64 */
    private String providerCode;
    /** 提供商展示名称，长度不超过 128，如"OpenAI"、"通义千问" */
    private String providerName;
    /** 状态：{@link ProviderStatus#ACTIVE}（已接入）、{@link ProviderStatus#PENDING}（待接入），管理员控制提供商可用性 */
    private ProviderStatus status;
    /** 服务接入端点，API 基础 URL，如 https://api.openai.com/v1 */
    private String endpoint;
    /** API 密钥，敏感字段，存储时加密，用于服务端鉴权 */
    private String apiKey;
    /** QPS 限制，该提供商最大允许请求速率，取值范围 1-10000 */
    private Integer qpsLimit;
    /** 月度配额，预算上限，单位元，超出将触发预算告警或熔断 */
    private BigDecimal monthlyQuota;
    /** 已用配额，当月累计消耗，单位元，由系统实时统计 */
    private BigDecimal usedQuota;
    /** 品牌颜色，十六进制色值如 #10A37F */
    private String color;
    /** 模型数量，该提供商下可用模型总数，由系统自动统计 */
    private Integer modelCount;
}