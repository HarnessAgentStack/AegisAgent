package com.aegis.core.dto.agent;

import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.api.ApiResponseMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 系统智能体 API 配置请求（嵌套在 AgentCreateRequest / AgentUpdateRequest 中）。
 *
 * <p>对齐前端 {@code api/agentApi.AgentApiConfigParams}，后端落库到 {@code agent_api} 表。
 * Key 有效期属于 {@code agent_api_key} 生命周期（KeyManager 生成 Key 时单独指定），不在本配置内。
 *
 *  @author aegis
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApiConfigRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** API 名称（展示用），默认 agentName + " API" */
    private String apiName;

    /** HTTP 方法：GET / POST，默认 POST */
    private String httpMethod;

    /** 响应模式：SYNC / ASYNC / SSE，默认 SYNC */
    private ApiResponseMode responseMode;

    /** 超时时间（秒），默认 30 */
    private Integer timeout;

    /** QPS 限流，默认 20 */
    private Integer rateLimit;

    /** 并发上限 */
    private Integer concurrentLimit;

    /** 鉴权类型：API_KEY / BEARER / OAUTH2 / BASIC / NONE，默认 API_KEY */
    private ApiAuthType authType;

    /** 入参 JSON Schema（JSON 字符串） */
    private String requestSchema;

    /** 出参 JSON Schema（JSON 字符串） */
    private String responseSchema;

    // ===== Bearer Token 鉴权扩展 =====

    /** Bearer Token 管理模式：STATIC / PASSTHROUGH，默认 PASSTHROUGH */
    private String bearerTokenMode;

    /** 静态模式 Token 值 */
    private String bearerTokenValue;

    /** JWT 签名密钥/公钥 */
    private String bearerJwtSecret;

    /** JWT 签名算法：HS256/HS384/HS512/RS256/RS384/RS512/ES256 */
    private String bearerJwtAlgorithm;

    /** Token introspection 端点（远端校验） */
    private String bearerIntrospectionUrl;

    /** 是否将 Bearer Token 透传给下游 Agent */
    private Boolean bearerPassThrough;
}
