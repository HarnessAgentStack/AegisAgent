package com.aegis.admin.service.agent;

import com.aegis.core.domain.agent.AgentApi;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AgentApiDocService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> generateOpenApiSpec(AgentApi api) {
        Map<String, Object> spec = new HashMap<>();
        spec.put("openapi", "3.0.3");

        Map<String, Object> info = new HashMap<>();
        info.put("title", api.getApiName() != null ? api.getApiName() : "Agent API");
        info.put("version", api.getVersion() != null ? api.getVersion() : "1.0.0");
        info.put("description", "智能体开放 API - " + (api.getApiName() != null ? api.getApiName() : ""));
        spec.put("info", info);

        Map<String, Object> server = new HashMap<>();
        server.put("url", "/api/runtime/agent-api");
        server.put("description", "Aegis Runtime API");
        spec.put("servers", List.of(server));

        Map<String, Object> paths = new HashMap<>();
        Map<String, Object> postPath = new HashMap<>();
        Map<String, Object> invokeOp = new HashMap<>();
        invokeOp.put("summary", "调用智能体");
        invokeOp.put("operationId", "invokeAgentApi");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("required", true);
        Map<String, Object> content = new HashMap<>();
        Map<String, Object> jsonContent = new HashMap<>();
        Map<String, Object> schema = new HashMap<>();

        if (api.getRequestSchema() != null && !api.getRequestSchema().isEmpty()) {
            try {
                JsonNode reqSchemaNode = objectMapper.readTree(api.getRequestSchema());
                Map<String, Object> reqSchemaMap = objectMapper.convertValue(reqSchemaNode,
                        new TypeReference<Map<String, Object>>() {});
                schema.putAll(reqSchemaMap);
            } catch (Exception e) {
                log.warn("Failed to parse requestSchema, using default: {}", e.getMessage());
                schema.put("$ref", "#/components/schemas/AgentApiInvokeRequest");
            }
        } else {
            schema.put("$ref", "#/components/schemas/AgentApiInvokeRequest");
        }

        jsonContent.put("schema", schema);
        content.put("application/json", jsonContent);
        requestBody.put("content", content);
        invokeOp.put("requestBody", requestBody);

        Map<String, Object> responses = new HashMap<>();

        Map<String, Object> okResponse = new HashMap<>();
        okResponse.put("description", "成功响应");
        Map<String, Object> respContent = new HashMap<>();
        Map<String, Object> respJson = new HashMap<>();
        Map<String, Object> respSchema = new HashMap<>();

        if (api.getResponseSchema() != null && !api.getResponseSchema().isEmpty()) {
            try {
                JsonNode respSchemaNode = objectMapper.readTree(api.getResponseSchema());
                Map<String, Object> respSchemaMap = objectMapper.convertValue(respSchemaNode,
                        new TypeReference<Map<String, Object>>() {});
                respSchema.putAll(respSchemaMap);
            } catch (Exception e) {
                log.warn("Failed to parse responseSchema, using default: {}", e.getMessage());
                respSchema.put("$ref", "#/components/schemas/AgentApiInvokeResponse");
            }
        } else {
            respSchema.put("$ref", "#/components/schemas/AgentApiInvokeResponse");
        }

        respJson.put("schema", respSchema);
        respContent.put("application/json", respJson);
        okResponse.put("content", respContent);
        responses.put("200", okResponse);

        List<Map<String, Object>> errorResponses = getErrorResponses();
        for (Map<String, Object> errorResp : errorResponses) {
            String httpStatus = (String) errorResp.get("httpStatus");
            Map<String, Object> responseObj = new HashMap<>();
            responseObj.put("description", errorResp.get("message"));
            responses.put(httpStatus, responseObj);
        }

        invokeOp.put("responses", responses);

        if (api.getApiPath() != null) {
            postPath.put("post", invokeOp);
            paths.put(api.getApiPath(), postPath);
        } else {
            postPath.put("post", invokeOp);
            paths.put("/invoke", postPath);
        }
        spec.put("paths", paths);

        Map<String, Object> components = new HashMap<>();
        Map<String, Object> schemas = new HashMap<>();

        Map<String, Object> reqSchema = new HashMap<>();
        reqSchema.put("type", "object");
        Map<String, Object> reqProps = new HashMap<>();
        reqProps.put("agentId", Map.of("type", "integer", "description", "智能体ID"));
        reqProps.put("input", Map.of("type", "string", "description", "用户输入内容"));
        reqProps.put("sessionId", Map.of("type", "string", "description", "会话ID（可选）"));
        reqProps.put("callerId", Map.of("type", "string", "description", "调用方标识"));
        reqProps.put("extraParams", Map.of("type", "object", "description", "额外参数"));
        reqSchema.put("properties", reqProps);
        reqSchema.put("required", List.of("agentId", "input"));
        schemas.put("AgentApiInvokeRequest", reqSchema);

        Map<String, Object> respSchema2 = new HashMap<>();
        respSchema2.put("type", "object");
        Map<String, Object> respProps = new HashMap<>();
        respProps.put("requestId", Map.of("type", "string", "description", "请求ID"));
        respProps.put("agentId", Map.of("type", "integer", "description", "智能体ID"));
        respProps.put("sessionId", Map.of("type", "string", "description", "会话ID"));
        respProps.put("answer", Map.of("type", "string", "description", "智能体回答"));
        respProps.put("usage", Map.of("type", "object", "description", "Token用量等统计"));
        respProps.put("latencyMs", Map.of("type", "integer", "description", "延迟（毫秒）"));
        respProps.put("status", Map.of("type", "string", "description", "状态"));
        respProps.put("errorMessage", Map.of("type", "string", "description", "错误信息"));
        respSchema2.put("properties", respProps);
        schemas.put("AgentApiInvokeResponse", respSchema2);

        Map<String, Object> securityScheme = new HashMap<>();
        securityScheme.put("type", "apiKey");
        securityScheme.put("in", "header");
        securityScheme.put("name", "X-API-Key");
        Map<String, Object> securitySchemes = new HashMap<>();
        securitySchemes.put("ApiKeyAuth", securityScheme);
        components.put("securitySchemes", securitySchemes);
        components.put("schemas", schemas);
        spec.put("components", components);

        spec.put("security", List.of(Map.of("ApiKeyAuth", List.of())));

        return spec;
    }

    public List<Map<String, Object>> getErrorCodes() {
        List<Map<String, Object>> errorCodes = new ArrayList<>();

        errorCodes.add(buildErrorCode("400", "SCHEMA_VALIDATION_FAILED",
                "入参 Schema 校验失败", "请求参数不满足 API 定义的 Schema 约束"));
        errorCodes.add(buildErrorCode("401", "INVALID_API_KEY",
                "无效的 API Key", "API Key 不存在或已被吊销"));
        errorCodes.add(buildErrorCode("401", "API_KEY_EXPIRED",
                "API Key 已过期", "API Key 超过有效期，请重新生成"));
        errorCodes.add(buildErrorCode("401", "API_DISABLED",
                "API 已禁用", "该 API 配置已被管理员禁用"));
        errorCodes.add(buildErrorCode("429", "RATE_LIMITED",
                "QPS 超限", "请求频率超过限流配置，请稍后重试"));
        errorCodes.add(buildErrorCode("500", "EXECUTION_FAILED",
                "智能体执行失败", "智能体内部执行异常，请联系管理员"));

        return errorCodes;
    }

    private List<Map<String, Object>> getErrorResponses() {
        List<Map<String, Object>> responses = new ArrayList<>();
        responses.add(buildErrorResponseItem("400", "SCHEMA_VALIDATION_FAILED", "入参 Schema 校验失败"));
        responses.add(buildErrorResponseItem("401", "INVALID_API_KEY", "无效的 API Key"));
        responses.add(buildErrorResponseItem("401", "API_KEY_EXPIRED", "API Key 已过期"));
        responses.add(buildErrorResponseItem("401", "API_DISABLED", "API 已禁用"));
        responses.add(buildErrorResponseItem("429", "RATE_LIMITED", "QPS 超限"));
        responses.add(buildErrorResponseItem("500", "EXECUTION_FAILED", "智能体执行失败"));
        return responses;
    }

    private Map<String, Object> buildErrorCode(String httpStatus, String code,
                                               String message, String description) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("httpStatus", httpStatus);
        error.put("code", code);
        error.put("message", message);
        error.put("description", description);
        return error;
    }

    private Map<String, Object> buildErrorResponseItem(String httpStatus, String code, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("httpStatus", httpStatus);
        item.put("code", code);
        item.put("message", message);
        return item;
    }
}