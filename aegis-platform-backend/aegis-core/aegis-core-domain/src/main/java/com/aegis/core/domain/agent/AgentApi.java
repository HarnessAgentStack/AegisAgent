package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.api.ApiResponseMode;
import com.aegis.core.enums.common.CommonStatus;
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
 * <p>将智能体能力以REST API形式对外开放，含认证方式、限流、超时与 Bearer 鉴权配置。
 * 外部系统通过 API Key（{@code agent_api_key} 表，哈希存储 + 轮换/吊销生命周期）
 * 或 Bearer Token 认证后调用智能体会话接口。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>apiPath租户内唯一，HTTP方法+路径组合唯一</li>
 *   <li>API Key 明文仅生成时返回，仅存储哈希值于 {@code agent_api_key} 表</li>
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

    /** API状态：{@link CommonStatus#NORMAL}（正常）、{@link CommonStatus#DISABLED}（停用） */
    private CommonStatus status;

    // ===== 部署目标（仅 agentType=SYSTEM 时生效）=====

    /** 部署目标沙箱池编码（外键 sbx_pool.pool_code + tenantId）。系统智能体对外 API 必须绑定部署目标，默认填好租户系统保留池。
     * 注:本字段为审核通过瞬间的池分配快照(只读展示+审计),不参与运行时沙箱路由决策(实际路由由 sec_sandbox_policy 驱动)。 */
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