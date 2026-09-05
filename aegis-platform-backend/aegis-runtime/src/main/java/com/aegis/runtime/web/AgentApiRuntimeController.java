package com.aegis.runtime.web;

import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentApi;
import com.aegis.core.domain.agent.AgentApiKey;
import com.aegis.core.dto.agent.AgentApiInvokeRequest;
import com.aegis.core.dto.agent.AgentApiInvokeResponse;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.dto.chat.ChatRequest;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.util.HashUtils;
import com.aegis.dal.mapper.agent.AgentApiKeyMapper;
import com.aegis.dal.mapper.agent.AgentApiMapper;
import com.aegis.runtime.service.conversation.TaskExecutionService;
import com.aegis.runtime.integration.auth.BearerAuthService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 系统智能体对外 API 运行时控制器。
 *
 * <p>为已审核通过的系统智能体提供 REST API 端点，供外部系统调用。
 * 支持多种鉴权方式：
 * <ul>
 *   <li>API Key 鉴权（通过 X-API-Key Header，哈希匹配）</li>
 *   <li>Bearer Token 鉴权（通过 Authorization: Bearer xxx Header，JWT 本地校验或静态比对）</li>
 *   <li>Key 状态与过期校验</li>
 *   <li>Schema 校验（若配置了 requestSchema）</li>
 *   <li>基础限流（基于内存计数器，单实例默认 100 QPS）</li>
 *   <li>请求转发至智能体会话执行引擎</li>
 *   <li>自动更新 lastUsedAt 调用时间</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/agent-api")
@RequiredArgsConstructor
public class AgentApiRuntimeController {

    private final AgentApiMapper agentApiMapper;
    private final AgentApiKeyMapper agentApiKeyMapper;
    private final BearerAuthService bearerAuthService;
    private final TaskExecutionService taskExecutionService;

    private static final int DEFAULT_RATE_LIMIT = 100;
    private final Map<String, AtomicInteger> rateLimitMap = new ConcurrentHashMap<>();
    private final Map<String, Long> rateLimitResetMap = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 系统智能体对外调用入口。
     *
     * <p>支持多种鉴权方式，根据 API 配置的 authType 自动选择鉴权策略：
     * <ul>
     *   <li>API_KEY：通过 X-API-Key Header 传入</li>
     *   <li>BEARER：通过 Authorization: Bearer {@code <token>} Header 传入</li>
     *   <li>NONE：无需鉴权</li>
     * </ul>
     *
     * @param apiKey        API Key（可选，通过 X-API-Key Header 传入）
     * @param authorization 鉴权头（可选，支持 Bearer / Basic）
     * @param request       调用请求
     * @return 智能体响应
     */
    @PostMapping("/invoke")
    public Result<AgentApiInvokeResponse> invoke(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AgentApiInvokeRequest request) {

        long startTime = System.currentTimeMillis();
        log.info("Agent API invoke: agentId={}, hasApiKey={}, hasAuth={}",
                request.getAgentId(), apiKey != null, authorization != null);

        // 1. 查找 API 配置与鉴权
        AgentApi api;
        AgentApiKey apiKeyEntity = null;
        String bearerToken = null;
        boolean bearerPassThrough = false;

        String authType = resolveAuthType(apiKey, authorization);

        switch (authType) {
            case "API_KEY" -> {
                // 通过 X-API-Key 哈希查找
                if (apiKey == null || apiKey.isBlank()) {
                    return Result.fail(ResultCode.FORBIDDEN, "缺少 X-API-Key Header");
                }
                String hash = HashUtils.sha256(apiKey);
                apiKeyEntity = agentApiKeyMapper.findActiveByHash(hash);
                if (apiKeyEntity == null) {
                    log.warn("Invalid API key hash: {}", hash);
                    return Result.fail(ResultCode.FORBIDDEN, "无效的 API Key");
                }
                if (apiKeyEntity.getExpiresAt() != null && apiKeyEntity.getExpiresAt().isBefore(LocalDateTime.now())) {
                    log.warn("API key expired: keyId={}", apiKeyEntity.getId());
                    return Result.fail(ResultCode.FORBIDDEN, "API Key 已过期");
                }
                api = agentApiMapper.selectById(apiKeyEntity.getApiId());
                if (api == null) {
                    return Result.fail(ResultCode.NOT_FOUND, "API 配置不存在");
                }
                // FIX(B-2): 配置驱动校验——API 声明 authType 必须为 API_KEY。
                // 防止前端平台 JWT 噪声头(被 resolveAuthType 误判)旁路真实配置。
                if (api.getAuthType() != null && api.getAuthType() != com.aegis.core.enums.api.ApiAuthType.API_KEY) {
                    log.warn("AuthType mismatch: api declared {} but got API_KEY path, apiId={}",
                            api.getAuthType(), api.getId());
                    return Result.fail(ResultCode.FORBIDDEN, "鉴权方式与 API 配置不匹配");
                }
            }
            case "BEARER" -> {
                // 通过 agentId 查找 API 配置
                api = findApiByAgentId(request.getAgentId());
                if (api == null) {
                    return Result.fail(ResultCode.NOT_FOUND, "API 配置不存在");
                }
                // FIX(B-2): 配置驱动校验——API 声明 authType 必须为 BEARER。
                if (api.getAuthType() != null && api.getAuthType() != com.aegis.core.enums.api.ApiAuthType.BEARER) {
                    log.warn("AuthType mismatch: api declared {} but got BEARER path, apiId={}",
                            api.getAuthType(), api.getId());
                    return Result.fail(ResultCode.FORBIDDEN, "鉴权方式与 API 配置不匹配");
                }
                // 校验 Bearer Token
                bearerToken = extractBearerToken(authorization);
                if (bearerToken == null) {
                    // FIX(B-7): 凭证失败统一 FORBIDDEN，避免 401 触发前端会话过期跳转。
                    return Result.fail(ResultCode.FORBIDDEN, "无效的 Bearer Token 格式");
                }
                BearerAuthService.AuthResult authResult = bearerAuthService.verify(bearerToken, api);
                if (!authResult.isSuccess()) {
                    log.warn("Bearer auth failed: apiId={}, error={}", api.getId(), authResult.getErrorMessage());
                    // FIX(B-7): 凭证失败统一 FORBIDDEN，避免 401 污染会话语义。
                    return Result.fail(ResultCode.FORBIDDEN, authResult.getErrorMessage());
                }
                // 透传配置
                bearerPassThrough = Boolean.TRUE.equals(api.getBearerPassThrough());
                log.info("Bearer auth passed: apiId={}, passThrough={}, claims={}",
                        api.getId(), bearerPassThrough, authResult.getClaims());
            }
            case "NONE" -> {
                // 无需鉴权，直接通过 agentId 查找
                api = findApiByAgentId(request.getAgentId());
                if (api == null) {
                    return Result.fail(ResultCode.NOT_FOUND, "API 配置不存在");
                }
                // FIX(B-2): 配置驱动校验——API 声明 authType 必须为 NONE。
                if (api.getAuthType() != null && api.getAuthType() != com.aegis.core.enums.api.ApiAuthType.NONE) {
                    log.warn("AuthType mismatch: api declared {} but got NONE path, apiId={}",
                            api.getAuthType(), api.getId());
                    return Result.fail(ResultCode.FORBIDDEN, "鉴权方式与 API 配置不匹配");
                }
            }
            default -> {
                return Result.fail(ResultCode.FORBIDDEN, "不支持的鉴权方式: " + authType);
            }
        }

        // 2. 检查 API 状态
        if (api.getStatus() != CommonStatus.NORMAL) {
            log.warn("API disabled: apiId={}, status={}", api.getId(), api.getStatus());
            return Result.fail(ResultCode.FORBIDDEN, "API 已禁用");
        }

        // 3. Schema 校验（如果配置了 requestSchema）
        if (api.getRequestSchema() != null && !api.getRequestSchema().isEmpty()) {
            try {
                validateRequestAgainstSchema(request, api.getRequestSchema());
            } catch (IllegalArgumentException e) {
                return Result.fail(ResultCode.PARAM_ERROR, "请求参数校验失败: " + e.getMessage());
            }
        }

        // 4. 限流检查
        String rateLimitKey = buildRateLimitKey(authType, apiKeyEntity, api);
        if (!checkRateLimit(rateLimitKey, api.getRateLimit())) {
            log.warn("Rate limit exceeded for key: {}", rateLimitKey);
            return Result.fail(ResultCode.TOO_MANY_REQUESTS, "请求过于频繁，请稍后重试");
        }

        // 5. 构建请求上下文并通过 TaskExecutionService 执行智能体
        try {
            String sessionId = request.getSessionId() != null && !request.getSessionId().isEmpty()
                    ? request.getSessionId()
                    : "api_" + UUID.randomUUID().toString().substring(0, 8);

            // 构建透传上下文（P2-7①：身份走强类型字段，context 仅保留鉴权/扩展透传位）
            Map<String, Object> context = new HashMap<>();
            context.put("authType", authType);
            if (bearerToken != null && bearerPassThrough) {
                context.put("bearerToken", bearerToken);
                context.put("bearerTokenPassThrough", true);
                log.debug("Bearer token pass-through enabled: apiId={}", api.getId());
            }
            if (request.getExtraParams() != null) {
                context.putAll(request.getExtraParams());
            }

            // 构建 ChatRequest 并通过真实执行引擎运行智能体
            // tenantId/userId 强类型注入：外部 API 调用归属 API 配置租户，系统用户标识 0
            ChatRequest chatRequest = ChatRequest.builder()
                    .agentId(api.getAgentId())
                    .sessionId(sessionId)
                    .message(request.getInput())
                    .tenantId(api.getTenantId())
                    .userId(0L)
                    .context(context)
                    .replyId("api_" + UUID.randomUUID().toString().replace("-", ""))
                    .build();

            // 同步收集 Flux<AgentEvent> 事件流（最长等待 5 分钟，与内部超时一致）
            int apiTimeout = api.getTimeout() != null ? api.getTimeout() : 300;
            List<AgentEvent> events = taskExecutionService.execute(chatRequest)
                    .collectList()
                    .block(Duration.ofSeconds(apiTimeout));

            // 从事件流提取执行结果
            StringBuilder answerBuilder = new StringBuilder();
            String resultSessionId = sessionId;
            int tokenInput = 0;
            int tokenOutput = 0;
            String errorMessage = null;
            String resultStatus = "SUCCESS";

            if (events != null) {
                for (AgentEvent evt : events) {
                    String eventType = evt.getEvent();
                    Object eventData = evt.getData();

                    if ("agent_start".equals(eventType) && eventData instanceof Map<?, ?> dm) {
                        Object sid = dm.get("sessionId");
                        if (sid != null) {
                            resultSessionId = sid.toString();
                        }
                    } else if ("text.delta".equals(eventType) && eventData instanceof Map<?, ?> dm) {
                        Object delta = dm.get("delta");
                        if (delta != null) {
                            answerBuilder.append(delta);
                        }
                    } else if ("error".equals(eventType) && eventData instanceof Map<?, ?> dm) {
                        Object msg = dm.get("message");
                        Object code = dm.get("code");
                        errorMessage = msg != null ? msg.toString() : "执行失败";
                        resultStatus = (code != null && "BLOCKED".equals(code.toString())) ? "BLOCKED" : "ERROR";
                    } else if (("done".equals(eventType) || "agent_end".equals(eventType))
                            && eventData instanceof Map<?, ?> dm) {
                        Object ti = dm.get("tokenInput");
                        Object to = dm.get("tokenOutput");
                        if (ti instanceof Number n) tokenInput += n.intValue();
                        if (to instanceof Number n) tokenOutput += n.intValue();
                    }
                }
            }

            long latencyMs = System.currentTimeMillis() - startTime;

            Map<String, Object> usage = new HashMap<>();
            usage.put("inputLength", request.getInput() != null ? request.getInput().length() : 0);
            usage.put("callerId", request.getCallerId());
            usage.put("tokenInput", tokenInput);
            usage.put("tokenOutput", tokenOutput);

            AgentApiInvokeResponse response = AgentApiInvokeResponse.builder()
                    .requestId("req_" + UUID.randomUUID().toString().substring(0, 12))
                    .agentId(api.getAgentId())
                    .sessionId(resultSessionId)
                    .answer(answerBuilder.length() > 0 ? answerBuilder.toString() : null)
                    .usage(usage)
                    .latencyMs(latencyMs)
                    .status(resultStatus)
                    .errorMessage(errorMessage)
                    .build();

            // 更新 lastUsedAt
            if (apiKeyEntity != null) {
                agentApiKeyMapper.update(null, new LambdaUpdateWrapper<AgentApiKey>()
                        .eq(AgentApiKey::getId, apiKeyEntity.getId())
                        .set(AgentApiKey::getLastUsedAt, LocalDateTime.now()));
            }

            log.info("Agent API invoke completed: agentId={}, sessionId={}, status={}, latencyMs={}, tokenIn={}, tokenOut={}",
                    api.getAgentId(), resultSessionId, resultStatus, latencyMs, tokenInput, tokenOutput);

            return Result.success(response);
        } catch (Exception e) {
            log.error("Agent API invoke failed: agentId={}, error={}", api.getAgentId(), e.getMessage(), e);
            AgentApiInvokeResponse errorResp = AgentApiInvokeResponse.builder()
                    .requestId("req_" + UUID.randomUUID().toString().substring(0, 12))
                    .agentId(api.getAgentId())
                    .sessionId(null)
                    .answer(null)
                    .usage(null)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .status("ERROR")
                    .errorMessage("智能体执行失败: " + e.getMessage())
                    .build();
            return Result.success(errorResp);
        }
    }

    /**
     * 查询 API 状态（健康检查）。
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> status(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "agentId", required = false) Long agentId) {

        AgentApi api = null;

        if (apiKey != null && !apiKey.isBlank()) {
            String hash = HashUtils.sha256(apiKey);
            AgentApiKey keyEntity = agentApiKeyMapper.findActiveByHash(hash);
            if (keyEntity == null) {
                return Result.fail(ResultCode.FORBIDDEN, "无效的 API Key");
            }
            if (keyEntity.getExpiresAt() != null && keyEntity.getExpiresAt().isBefore(LocalDateTime.now())) {
                return Result.fail(ResultCode.FORBIDDEN, "API Key 已过期");
            }
            api = agentApiMapper.selectById(keyEntity.getApiId());
        } else if (authorization != null && authorization.startsWith("Bearer ")) {
            if (agentId == null) {
                return Result.fail(ResultCode.PARAM_ERROR, "agentId 参数必填（Bearer 模式）");
            }
            api = findApiByAgentId(agentId);
            if (api != null) {
                String token = extractBearerToken(authorization);
                BearerAuthService.AuthResult result = bearerAuthService.verify(token, api);
                if (!result.isSuccess()) {
                    // FIX(B-7): 凭证失败统一 FORBIDDEN，与 invoke 端点一致，避免 401 污染。
                    return Result.fail(ResultCode.FORBIDDEN, result.getErrorMessage());
                }
            }
        } else if (agentId != null) {
            api = findApiByAgentId(agentId);
        }

        if (api == null) {
            // FIX(B-7): 无法定位 API 属凭证/参数问题，统一 FORBIDDEN。
            return Result.fail(ResultCode.FORBIDDEN, "无法定位 API 配置");
        }

        Map<String, Object> status = new HashMap<>();
        status.put("agentId", api.getAgentId());
        status.put("apiName", api.getApiName());
        status.put("status", api.getStatus() == CommonStatus.NORMAL ? "active" : "inactive");
        status.put("authType", api.getAuthType() != null ? api.getAuthType().name() : "API_KEY");
        status.put("bearerTokenMode", api.getBearerTokenMode() != null ? api.getBearerTokenMode() : "PASSTHROUGH");
        status.put("responseMode", api.getResponseMode() != null ? api.getResponseMode().name() : "SYNC");
        status.put("rateLimit", api.getRateLimit() != null ? api.getRateLimit() : DEFAULT_RATE_LIMIT);
        status.put("deploymentPool", api.getDeploymentPoolCode());
        status.put("reservedReplicas", api.getReservedReplicas() != null ? api.getReservedReplicas() : 1);

        return Result.success(status);
    }

    // ==================== 鉴权辅助方法 ====================

    /**
     * 根据请求头判断应使用的鉴权类型。
     */
    private String resolveAuthType(String apiKey, String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return "BEARER";
        }
        if (apiKey != null && !apiKey.isBlank()) {
            return "API_KEY";
        }
        if (authorization != null && authorization.startsWith("Basic ")) {
            return "BASIC";
        }
        return "NONE";
    }

    /**
     * 从 Authorization 头中提取 Bearer Token。
     */
    private String extractBearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * 通过 agentId 查找 API 配置。
     */
    private AgentApi findApiByAgentId(Long agentId) {
        if (agentId == null) {
            return null;
        }
        return agentApiMapper.selectOne(new LambdaQueryWrapper<AgentApi>()
                .eq(AgentApi::getAgentId, agentId)
                .orderByDesc(AgentApi::getId)
                .last("LIMIT 1"));
    }

    /**
     * 构建限流 key。
     */
    private String buildRateLimitKey(String authType, AgentApiKey apiKeyEntity, AgentApi api) {
        return switch (authType) {
            case "API_KEY" -> HashUtils.sha256(apiKeyEntity.getApiKeyHash());
            case "BEARER", "NONE" -> "api_" + api.getId();
            default -> "unknown";
        };
    }

    // ==================== 限流与校验 ====================

    /**
     * 限流检查（基于内存计数器，每秒重置）。
     */
    private boolean checkRateLimit(String key, Integer rateLimit) {
        int limit = rateLimit != null ? rateLimit : DEFAULT_RATE_LIMIT;
        long now = System.currentTimeMillis() / 1000;

        rateLimitResetMap.computeIfAbsent(key, k -> now);
        Long resetTime = rateLimitResetMap.get(key);

        if (resetTime < now) {
            rateLimitMap.put(key, new AtomicInteger(0));
            rateLimitResetMap.put(key, now);
        }

        AtomicInteger counter = rateLimitMap.computeIfAbsent(key, k -> new AtomicInteger(0));
        return counter.incrementAndGet() <= limit;
    }

    /**
     * 基于 JSON Schema 校验请求体。
     */
    private void validateRequestAgainstSchema(AgentApiInvokeRequest request, String schemaJson) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            JsonNode propertiesNode = schemaNode.get("properties");
            if (propertiesNode == null) {
                return;
            }

            JsonNode requiredNode = schemaNode.get("required");
            if (requiredNode != null && requiredNode.isArray()) {
                for (JsonNode req : requiredNode) {
                    String fieldName = req.asText();
                    Object value = getFieldValue(request, fieldName);
                    if (value == null || (value instanceof String s && s.isEmpty())) {
                        throw new IllegalArgumentException("必填字段缺失: " + fieldName);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse request schema: {}", e.getMessage());
        }
    }

    private Object getFieldValue(AgentApiInvokeRequest request, String fieldName) {
        return switch (fieldName) {
            case "agentId" -> request.getAgentId();
            case "input" -> request.getInput();
            case "sessionId" -> request.getSessionId();
            case "callerId" -> request.getCallerId();
            case "extraParams" -> request.getExtraParams();
            default -> null;
        };
    }
}
