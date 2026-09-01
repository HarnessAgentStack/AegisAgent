package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.api.ApiResponseMode;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.ScopeType;
import com.aegis.core.enums.common.ValidityType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 智能体开放API实体，支持外部系统调用。
 *
 * <p>将智能体能力以REST API形式对外开放，含认证方式、限流、超时、有效期与数据出境合规配置。
 * 外部系统通过API Key/Bearer/OAuth2认证后调用智能体会话接口。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>apiPath租户内唯一，HTTP方法+路径组合唯一</li>
 *   <li>数据出境场景需配置scopeType合规范围</li>
 *   <li>apiKey仅创建时返回明文，后续仅展示脱敏值</li>
 * </ul>
 *
 * @author wang.zhen
 * @see AgentDef
 * @see com.aegis.core.enums.api.ApiAuthType
 * @see com.aegis.core.enums.api.ApiResponseMode
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_api")
public class AgentApi extends TenantEntity {

    /** 智能体ID，关联AgentDef主键 */
    private Long agentId;

    /** API名称，展示用 */
    private String apiName;

    /** API路径，租户内唯一，格式 /api/v1/agents/{code}/invoke */
    private String apiPath;

    /** HTTP方法：GET/POST，默认POST */
    private String httpMethod;

    /** 认证类型：{@link ApiAuthType}（API_KEY/BEARER/OAUTH2/NONE） */
    private ApiAuthType authType;

    /** 响应模式：{@link ApiResponseMode}（SYNC/ASYNC/SSE） */
    private ApiResponseMode responseMode;

    /** 限流QPS，每秒最大请求数 */
    private Integer rateLimit;

    /** 超时时间（秒），默认30 */
    private Integer timeout;

    /** 有效期类型：{@link ValidityType#PERMANENT}（永久）、{@link ValidityType#DAYS_7}（7天）、{@link ValidityType#DAYS_30}（30天）、{@link ValidityType#CUSTOM}（自定义），控制API可用时间 */
    private ValidityType validityType;

    /** 固定有效期截止时间，validityType非PERMANENT时生效 */
    private LocalDateTime validUntil;

    /** 数据出境范围类型：{@link ScopeType#INTERNAL_IP}（企业内部白名单IP）、{@link ScopeType#DEPT}（指定部门）、{@link ScopeType#PARTNER}（指定外部合作伙伴） */
    private ScopeType scopeType;

    /** 出境范围配置，JSON格式（含白名单域名/字段等） */
    private String scopeConfig;

    /** API密钥，仅创建时明文返回，存储哈希值 */
    private String apiKey;

    /** 回调地址，异步模式结果回调URL */
    private String webhookUrl;

    /** API状态：{@link CommonStatus#NORMAL}（正常）、{@link CommonStatus#DISABLED}（停用） */
    private CommonStatus status;

    // ===== 部署目标（仅 agentType=SYSTEM 时生效）=====

    /** 部署目标沙箱池编码（外键 sbx_pool.pool_code + tenantId）。系统智能体对外 API 必须绑定部署目标，默认填好租户系统保留池。 */
    private String deploymentPoolCode;

    /** 该智能体在绑定池内的预留常驻副本数，默认 1，保证对外 API 的冷启动与容量（对应沙箱常驻钉扎）。 */
    private Integer reservedReplicas = 1;

    /** 沙箱分配状态：PENDING-待分配（审核中）/ ALLOCATED-已分配（审核通过）/ FAILED-分配失败 */
    private String poolAllocateStatus;

    /** 沙箱池分配完成时间 */
    private LocalDateTime allocateTime;

    // ===== API 发布配置扩展（MVP）=====

    /** API 版本号，如 1.0.0 */
    private String version;

    /** 并发请求上限 */
    private Integer concurrentLimit;

    /** 入参 JSON Schema（JSON 字符串） */
    private String requestSchema;

    /** 出参 JSON Schema（JSON 字符串） */
    private String responseSchema;

    /** 示例请求体（JSON 字符串） */
    private String exampleRequest;

    /** 示例响应体（JSON 字符串） */
    private String exampleResponse;

    /** API 文档 URL */
    private String apiDocUrl;

    /** 最近测试时间 */
    private LocalDateTime lastTestedAt;

    // ===== Bearer Token 鉴权配置 =====

    /** Bearer Token 管理模式：STATIC-静态配置（不推荐）、PASSTHROUGH-透传验证（推荐） */
    private String bearerTokenMode;

    /** 静态模式下的 Token 值（STATIC 模式时使用） */
    private String bearerTokenValue;

    /** JWT 签名密钥/公钥（本地校验时使用） */
    private String bearerJwtSecret;

    /** JWT 签名算法：HS256/HS384/HS512/RS256/RS384/RS512/ES256 */
    private String bearerJwtAlgorithm;

    /** Token introspection 端点（远端校验时使用） */
    private String bearerIntrospectionUrl;

    /** 是否将 Bearer Token 透传给下游 Agent 服务 */
    private Boolean bearerPassThrough;
}