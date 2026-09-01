package com.aegis.runtime.integration.mcp;

import com.aegis.core.domain.resource.McpService;
import com.aegis.core.dto.resource.ToolVO;
import com.aegis.core.enums.api.ApiAuthType;
import com.aegis.core.enums.model.ProviderStatus;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

/**
 * MCP 远程调用器（基于 AgentScope 2.0.2 原生 MCP 客户端）。
 *
 * <p>封装 MCP Server 的远程调用逻辑，供 {@link com.aegis.runtime.integration.tool.SkillExecutor}
 * 执行 MCP 来源工具时使用。底层通过 AS {@link McpClientBuilder} 创建真实 MCP 协议连接，
 * 支持 STDIO / SSE / STREAMABLE_HTTP 三种传输协议。
 *
 * <h3>核心机制</h3>
 * <ul>
 *   <li><b>连接缓存</b>：Caffeine 缓存 {@link McpClientWrapper}，key 为 mcpServiceId，
 *       最多 100 连接，30 分钟空闲过期，驱逐时自动 {@code close()} 释放资源</li>
 *   <li><b>协议映射</b>：{@link McpProtocol} → AS {@link McpClientBuilder} 传输配置</li>
 *   <li><b>鉴权注入</b>：从 {@link McpService#getAuthConfig()} 解析鉴权信息，
 *       支持 API_KEY / BEARER 两种鉴权方式，通过 HTTP header 注入</li>
 * </ul>
 *
 * <h3>调用流程</h3>
 * <pre>{@code
 * McpInvoker invoker = ...;
 * String result = invoker.invoke("123", "get_weather", "{\"city\":\"天津\"}");
 * // → {"success":true,"output":"天津 晴 25℃"}
 * }</pre>
 *
 * @author wang.zhen
 * @see McpClientBuilder
 * @see McpClientWrapper
 */
@Slf4j
@Component
public class McpInvoker {

    private final ResourceQueryService resourceQueryService;

    /**
     * P1-6：带超时配置的 RestTemplate（connect 3s / read 10s），
     * 替代裸 {@code new RestTemplate()}（默认无限等待）。
     * 超时值可通过配置项 {@code aegis.runtime.mcp.http-connect-timeout-ms} /
     * {@code aegis.runtime.mcp.http-read-timeout-ms} 覆盖。
     */
    private final RestTemplate restTemplate;

    public McpInvoker(ResourceQueryService resourceQueryService,
                      @org.springframework.beans.factory.annotation.Value("${aegis.runtime.mcp.http-connect-timeout-ms:3000}") int connectTimeoutMs,
                      @org.springframework.beans.factory.annotation.Value("${aegis.runtime.mcp.http-read-timeout-ms:10000}") int readTimeoutMs) {
        this.resourceQueryService = resourceQueryService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /** MCP 客户端连接缓存：mcpServiceId → McpClientWrapper，驱逐时自动 close() */
    private final Cache<Long, McpClientWrapper> clientCache = Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterAccess(Duration.ofMinutes(30))
            .removalListener((Long key, McpClientWrapper client, RemovalCause cause) -> {
                if (client != null) {
                    try {
                        client.close();
                        log.debug("MCP 客户端已关闭: mcpServiceId={}, cause={}", key, cause);
                    } catch (Exception e) {
                        log.warn("MCP 客户端关闭失败: mcpServiceId={}", key, e);
                    }
                }
            })
            .build();

    /** 单次 MCP 工具调用超时时间 */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    /** HTTP 连接超时（秒） */
    private static final int HTTP_TIMEOUT_SECONDS = 30;

    /**
     * 调用 MCP Server 工具（唯一入口，T5 收敛）。
     *
     * <p>原 invoke（deprecated，无状态校验）与 invokeWithSubscription（含
     * PUBLISHED/ACTIVE 校验，租户参数仅打日志且零调用方）合并为本方法：
     * 校验逻辑并入，消除双实现漂移风险。
     *
     * <p>v3.0 优先使用 HTTP REST 端点调用，确保在 Spring Boot 4.0 + Spring AI 2.0
     * 兼容性问题下仍能正常工作。AgentScope MCP 客户端作为可选补充，
     * 三级回退链：REST → AgentScope 客户端 → JSON-RPC。
     *
     * @param mcpServiceId MCP 服务ID（字符串形式，内部解析为 Long）
     * @param toolName     工具名称
     * @param arguments    调用参数（JSON 字符串）
     * @return 执行结果（JSON 字符串，含 success / output 字段）
     */
    public String invoke(String mcpServiceId, String toolName, String arguments) {
        Long serviceId;
        try {
            serviceId = Long.parseLong(mcpServiceId);
        } catch (NumberFormatException e) {
            return errorJson("MCP 服务ID无效: " + mcpServiceId);
        }

        McpService service = resourceQueryService.findMcpServiceById(serviceId);
        if (service == null) {
            return errorJson("MCP 服务不存在: serviceId=" + serviceId);
        }
        if (service.getLifeStatus() != com.aegis.core.enums.agent.AgentLifeStatus.PUBLISHED) {
            return errorJson("MCP 服务尚未发布: " + service.getLifeStatus());
        }
        if (service.getStatus() != ProviderStatus.ACTIVE) {
            return errorJson("MCP 服务未激活: " + service.getStatus());
        }

        log.info("MCP invoke: serviceId={}, tool={}", serviceId, toolName);

        // 优先使用 HTTP REST 端点（稳定可靠）
        try {
            String result = invokeViaRestFallback(service, toolName, arguments);
            if (result != null && !result.contains("\"success\":false")) {
                log.info("MCP REST 调用成功: serviceId={}, tool={}", serviceId, toolName);
                return result;
            }
            log.warn("MCP REST 调用失败，尝试 AgentScope: serviceId={}, error={}", serviceId, result);
        } catch (Exception e) {
            log.warn("MCP REST 调用异常，尝试 AgentScope: serviceId={}, error={}", serviceId, e.getMessage());
        }

        // 回退到 AgentScope MCP 客户端
        McpClientWrapper wrapper;
        try {
            wrapper = getOrCreateClient(serviceId);
        } catch (Exception e) {
            log.warn("MCP AgentScope 客户端创建失败，尝试 JSON-RPC 回退: serviceId={}", serviceId, e);
            return invokeViaHttp(service, toolName, arguments);
        }
        if (wrapper == null) {
            log.warn("MCP AgentScope 客户端为 null，尝试 JSON-RPC 回退: serviceId={}", serviceId);
            return invokeViaHttp(service, toolName, arguments);
        }

        try {
            return doInvoke(wrapper, serviceId, toolName, arguments);
        } catch (Exception e) {
            log.warn("MCP AgentScope 调用失败，尝试 JSON-RPC 回退: serviceId={}, tool={}", serviceId, toolName, e);
            clientCache.invalidate(serviceId);
            return invokeViaHttp(service, toolName, arguments);
        }
    }

    /**
     * 内部方法：执行实际的 MCP 工具调用。
     *
     * <p>v2.0 改为抛出异常而非返回错误 JSON，使外层 invoke() 能够正确回退到 HTTP 通道。
     */
    private String doInvoke(McpClientWrapper client, Long serviceId, String toolName, String arguments) {
        log.info("MCP doInvoke: serviceId={}, tool={}, args={}", serviceId, toolName,
                arguments != null && arguments.length() > 200
                        ? arguments.substring(0, 200) + "..." : arguments);

        Map<String, Object> argsMap = parseArguments(arguments);
        McpSchema.CallToolResult result = client.callTool(toolName, argsMap)
                .block(CALL_TIMEOUT);
        return formatResult(result);
    }

    /**
     * 列出 MCP Server 暴露的全部工具 schema（P0-C 接通对话循环）。
     *
     * <p>v3.0 优先使用 HTTP REST 端点获取工具列表，确保在 Spring Boot 4.0 + Spring AI 2.0
     * 兼容性问题下仍能正常工作。AgentScope MCP 客户端作为可选补充。
     *
     * <p>由 {@link com.aegis.runtime.integration.agent.AegisToolBridge#resolveTools}
     * 在 agent 装配阶段调用：当 res_tool.sourceType == MCP 时，拉取该 MCP 服务真实暴露的工具列表，
     * 由 AegisToolBridge 包装为 AegisMcpTool 注册到 AS Toolkit，使 LLM 可调用 MCP 工具。
     *
     * <p>该方法为阻塞式（block 一次 listTools RPC），调用方需在装配阶段而非流式对话循环内调用。
     * 单次 RPC 超时同 {@link #CALL_TIMEOUT}。
     *
     * @param mcpServiceId MCP 服务ID（字符串形式，内部解析为 Long）
     * @return MCP 服务暴露的工具列表；服务不可用或拉取失败时返回空 List（不抛异常）
     */
    public List<McpSchema.Tool> listTools(String mcpServiceId) {
        Long serviceId;
        try {
            serviceId = Long.parseLong(mcpServiceId);
        } catch (NumberFormatException e) {
            log.warn("listTools: MCP 服务ID无效: {}", mcpServiceId);
            return Collections.emptyList();
        }

        McpService service = resourceQueryService.findMcpServiceById(serviceId);
        return listTools(service);
    }

    /**
     * 列出 MCP Server 暴露的全部工具 schema（P2-2①：接受预加载 McpService，消除同行 3 读）。
     *
     * <p>与 {@link #listTools(String)} 功能等价，但跳过内部 findMcpServiceById 查询，
     * 由调用方传入已加载的 McpService 实体。同时 cache miss 时传入预加载实体给
     * {@link #createClient(Long, McpService)}，彻底消除同一 res_mcp_service 行的 3 次查询。
     *
     * @param service 预加载的 MCP 服务实体（null 时返回空列表）
     * @return MCP 服务暴露的工具列表
     */
    public List<McpSchema.Tool> listTools(McpService service) {
        if (service == null || service.getId() == null) {
            return Collections.emptyList();
        }
        Long serviceId = service.getId();
        if (service.getStatus() != ProviderStatus.ACTIVE) {
            log.warn("listTools: MCP 服务未激活: serviceId={}, status={}", serviceId, service.getStatus());
            return Collections.emptyList();
        }

        log.info("listTools: 开始获取MCP工具列表, serviceId={}, mcpCode={}, protocol={}, endpoint={}",
                serviceId, service.getMcpCode(), service.getProtocol(), service.getEndpoint());

        // 优先使用 HTTP REST 端点（稳定可靠）
        List<ToolVO> toolVOs = listToolsViaHttp(service);
        if (toolVOs != null && !toolVOs.isEmpty()) {
            log.info("listTools: REST成功获取 {} 个工具, serviceId={}", toolVOs.size(), serviceId);
            return convertToolVOsToMcpTools(toolVOs);
        }
        log.warn("listTools: REST返回空列表, serviceId={}, 尝试 AgentScope", serviceId);

        // 回退到 AgentScope MCP 客户端（传入预加载 service 消除 createClient 重查）
        try {
            McpClientWrapper client = getOrCreateClient(serviceId, service);
            if (client != null) {
                log.info("listTools: AgentScope客户端创建成功, serviceId={}", serviceId);
                List<McpSchema.Tool> tools = client.listTools().block(CALL_TIMEOUT);
                if (tools != null && !tools.isEmpty()) {
                    log.info("listTools: AgentScope成功获取 {} 个工具, serviceId={}", tools.size(), serviceId);
                    return new ArrayList<>(tools);
                }
                log.warn("listTools: AgentScope返回空列表, serviceId={}, 协议={}", serviceId, service.getProtocol());
            } else {
                log.warn("listTools: AgentScope客户端为null, serviceId={}", serviceId);
            }
        } catch (Exception e) {
            log.warn("listTools: AgentScope调用失败, serviceId={}, error={}", serviceId, e.getMessage());
            clientCache.invalidate(serviceId);
        }

        log.warn("listTools: 所有路径均失败, 返回空列表, serviceId={}", serviceId);
        return Collections.emptyList();
    }

    /**
     * 将 ToolVO 列表转换为 McpSchema.Tool 列表。
     */
    private List<McpSchema.Tool> convertToolVOsToMcpTools(List<ToolVO> toolVOs) {
        List<McpSchema.Tool> result = new ArrayList<>(toolVOs.size());
        for (ToolVO vo : toolVOs) {
            McpSchema.Tool tool = new McpSchema.Tool(
                    vo.getToolCode(),
                    vo.getDescription() != null ? vo.getDescription() : "",
                    null,
                    null,
                    null,
                    null,
                    null
            );
            result.add(tool);
        }
        return result;
    }

    // ============ 内部方法 ============

    /**
     * 从缓存获取或创建 MCP 客户端。
     *
     * <p>缓存未命中时查询 {@link McpService} 表，根据协议配置通过
     * {@link McpClientBuilder} 创建真实 MCP 连接。
     */
    private McpClientWrapper getOrCreateClient(Long serviceId) {
        return clientCache.get(serviceId, this::createClient);
    }

    /**
     * 从缓存获取或创建 MCP 客户端（P2-2①：接受预加载的 McpService，消除 cache miss 时的同行重查）。
     */
    private McpClientWrapper getOrCreateClient(Long serviceId, McpService preloadedService) {
        if (preloadedService == null) {
            return getOrCreateClient(serviceId);
        }
        return clientCache.get(serviceId, id -> createClient(id, preloadedService));
    }

    /**
     * 根据 McpService 配置创建 MCP 客户端。
     *
     * @param serviceId MCP 服务ID
     * @return MCP 客户端包装器，服务不存在或未激活时返回 null
     */
    private McpClientWrapper createClient(Long serviceId) {
        McpService service = resourceQueryService.findMcpServiceById(serviceId);
        return createClient(serviceId, service);
    }

    /**
     * 根据 McpService 配置创建 MCP 客户端（P2-2①：接受预加载实体，消除同行重查）。
     */
    private McpClientWrapper createClient(Long serviceId, McpService service) {
        if (service == null) {
            log.warn("MCP 服务不存在: serviceId={}", serviceId);
            return null;
        }
        if (service.getStatus() != ProviderStatus.ACTIVE) {
            log.warn("MCP 服务未激活: serviceId={}, status={}", serviceId, service.getStatus());
            return null;
        }

        String clientName = service.getMcpCode() != null
                ? service.getMcpCode()
                : "mcp-" + serviceId;
        McpClientBuilder builder = McpClientBuilder.create(clientName);

        log.info("createClient: 配置MCP客户端, serviceId={}, protocol={}, endpoint={}, authType={}",
                serviceId, service.getProtocol(), service.getEndpoint(), service.getAuthType());

        configureTransport(builder, service.getProtocol(), service.getEndpoint());
        applyAuth(builder, service.getAuthType(), service.getAuthConfig());
        builder.timeout(CALL_TIMEOUT);

        McpClientWrapper client;
        try {
            client = builder.buildAsync().block();
            log.info("createClient: MCP客户端创建成功, serviceId={}, clientName={}", serviceId, clientName);
        } catch (Exception e) {
            log.error("createClient: MCP客户端创建失败, serviceId={}, clientName={}, error={}",
                    serviceId, clientName, e.getMessage(), e);
            throw e;
        }
        return client;
    }

    /**
     * 根据协议类型配置传输层。
     *
     * @param builder  MCP 客户端构建器
     * @param protocol 传输协议
     * @param endpoint 接入端点（URL 或 STDIO 命令行）
     */
    private void configureTransport(McpClientBuilder builder, McpProtocol protocol, String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint 为空: protocol=" + protocol);
        }
        switch (protocol) {
            case SSE -> builder.sseTransport(endpoint);
            case STREAMABLE_HTTP -> builder.streamableHttpTransport(endpoint);
            case STDIO -> {
                String[] parts = endpoint.trim().split("\\s+");
                if (parts.length == 0) {
                    throw new IllegalArgumentException("STDIO endpoint 无效: " + endpoint);
                }
                if (parts.length == 1) {
                    builder.stdioTransport(parts[0]);
                } else {
                    builder.stdioTransport(parts[0], Arrays.copyOfRange(parts, 1, parts.length));
                }
            }
            default -> throw new IllegalArgumentException("不支持的 MCP 协议: " + protocol);
        }
    }

    /**
     * 注入鉴权 header。
     *
     * @param builder    MCP 客户端构建器
     * @param authType   鉴权类型
     * @param authConfig 鉴权配置（JSON 字符串）
     */
    private void applyAuth(McpClientBuilder builder, ApiAuthType authType, String authConfig) {
        if (authType == null || authType == ApiAuthType.NONE) {
            return;
        }
        if (authConfig == null || authConfig.isBlank()) {
            log.warn("鉴权类型为 {} 但 authConfig 为空，跳过鉴权", authType);
            return;
        }
        JSONObject config = JSON.parseObject(authConfig);
        switch (authType) {
            case API_KEY -> {
                String apiKey = config.getString("apiKey");
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.header("X-API-Key", apiKey);
                }
            }
            case BEARER -> {
                String token = config.getString("token");
                if (token != null && !token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                }
            }
            case OAUTH2 -> log.warn("OAUTH2 鉴权暂不支持，跳过");
            default -> log.warn("未知鉴权类型，跳过: {}", authType);
        }
    }

    /**
     * 解析参数 JSON 字符串为 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.parseObject(arguments, Map.class);
        } catch (Exception e) {
            log.warn("参数 JSON 解析失败，使用原始字符串: {}", arguments, e);
            return Map.of("input", arguments);
        }
    }

    /**
     * 格式化 MCP 调用结果为 JSON 字符串。
     *
     * @param result MCP 调用结果
     * @return JSON 字符串（含 success / output 字段）
     */
    private String formatResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return errorJson("MCP 调用返回 null");
        }
        StringBuilder sb = new StringBuilder();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent tc) {
                    sb.append(tc.text());
                }
            }
        }
        boolean isError = Boolean.TRUE.equals(result.isError());
        Map<String, Object> response = new HashMap<>(2);
        response.put("success", !isError);
        response.put("output", sb.toString());
        if (isError) {
            response.put("error", sb.toString());
        }
        return JSON.toJSONString(response);
    }

    /**
     * 构造错误响应 JSON。
     */
    private String errorJson(String message) {
        Map<String, Object> error = new HashMap<>(2);
        error.put("success", false);
        error.put("error", message != null ? message.replace("\"", "'") : "unknown error");
        return JSON.toJSONString(error);
    }

    // ============ HTTP 回退方法 ============

    /**
     * 通过 HTTP 获取 MCP 工具列表（优先 REST 端点）。
     *
     * <p>v3.0 优先使用 REST 端点（/api/mcp/tools）获取工具列表，
     * 仅对 STREAMABLE_HTTP 协议尝试 JSON-RPC POST 请求。
     *
     * @param service MCP 服务配置
     * @return 工具 VO 列表
     */
    private List<ToolVO> listToolsViaHttp(McpService service) {
        String endpoint = service.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("listToolsViaHttp: MCP endpoint 为空");
            return Collections.emptyList();
        }

        log.info("listToolsViaHttp: 协议={}, endpoint={}", service.getProtocol(), endpoint);

        // 优先使用 REST 端点（稳定可靠）
        List<ToolVO> restResult = listToolsViaRestFallback(service);
        if (restResult != null && !restResult.isEmpty()) {
            log.info("listToolsViaHttp: REST成功获取 {} 个工具", restResult.size());
            return restResult;
        }
        log.warn("listToolsViaHttp: REST返回空列表");

        // 仅对 STREAMABLE_HTTP 协议尝试 JSON-RPC POST
        if (service.getProtocol() == McpProtocol.STREAMABLE_HTTP) {
            try {
                Map<String, Object> request = Map.of(
                        "jsonrpc", "2.0",
                        "method", "tools/list",
                        "id", System.currentTimeMillis()
                );

                String json = JSON.toJSONString(request);
                log.info("listToolsViaHttp: JSON-RPC POST {} body={}", endpoint, json);

                ResponseEntity<String> response = restTemplate.postForEntity(
                        endpoint,
                        new HttpEntity<>(json, buildHttpHeaders(service)),
                        String.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    log.info("listToolsViaHttp: JSON-RPC 成功, 响应长度={}", response.getBody().length());
                    List<ToolVO> tools = parseToolsListResponseAsVO(response.getBody());
                    if (!tools.isEmpty()) {
                        return tools;
                    }
                }
            } catch (Exception e) {
                log.warn("listToolsViaHttp: JSON-RPC 失败: endpoint={}, error={}", endpoint, e.getMessage());
            }
        } else {
            log.info("listToolsViaHttp: 协议={}, 跳过 JSON-RPC", service.getProtocol());
        }

        return Collections.emptyList();
    }

    /**
     * 通过 REST 端点获取 MCP 工具列表（回退方案）。
     *
     * <p>将 SSE/HTTP 端点转换为 REST 端点格式，获取工具列表。
     * 例如: http://127.0.0.1:8083/sse → http://127.0.0.1:8083/api/mcp/tools
     */
    private List<ToolVO> listToolsViaRestFallback(McpService service) {
        String endpoint = service.getEndpoint();
        String restEndpoint = buildRestEndpoint(endpoint);
        if (restEndpoint == null) {
            return Collections.emptyList();
        }

        log.info("listToolsViaRestFallback: 尝试 REST 端点={}", restEndpoint);

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    restEndpoint,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("listToolsViaRestFallback: REST 成功, 响应长度={}", response.getBody().length());
                return parseRestToolsResponseAsVO(response.getBody());
            }

            log.warn("listToolsViaRestFallback: REST HTTP {} for {}", response.getStatusCode(), restEndpoint);
        } catch (Exception e) {
            log.warn("listToolsViaRestFallback 失败: endpoint={}, error={}", restEndpoint, e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * 将 MCP 端点转换为 REST 端点。
     *
     * <p>支持 SSE 端点（/sse）和 STREAMABLE_HTTP 端点的转换。
     */
    private String buildRestEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }

        try {
            // SSE 端点: http://host:port/sse → http://host:port/api/mcp/tools
            if (endpoint.endsWith("/sse")) {
                return endpoint.replace("/sse", "/api/mcp/tools");
            }
            // 处理 SSE 端点带参数的情况: http://host:port/sse?param=value
            int sseIdx = endpoint.indexOf("/sse");
            if (sseIdx > 0) {
                String base = endpoint.substring(0, sseIdx);
                return base + "/api/mcp/tools";
            }
            // STREAMABLE_HTTP: http://host:port/mcp → http://host:port/api/mcp/tools
            int mcpIdx = endpoint.indexOf("/mcp");
            if (mcpIdx > 0 && !endpoint.contains("/api/mcp")) {
                return endpoint.substring(0, mcpIdx) + "/api/mcp/tools";
            }
            // 已经是 REST 端点
            if (endpoint.contains("/api/mcp/tools")) {
                return endpoint;
            }
            // 默认: 在端点路径后追加 /api/mcp/tools
            String base = endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf("?")) : endpoint;
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/api/mcp/tools";
        } catch (Exception e) {
            log.warn("buildRestEndpoint 失败: endpoint={}, error={}", endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 REST 端点响应为 ToolVO 列表。
     *
     * <p>REST 响应格式: {"code": 0, "message": "success", "data": [...]}
     */
    private List<ToolVO> parseRestToolsResponseAsVO(String responseBody) {
        try {
            JSONObject response = JSON.parseObject(responseBody);
            Integer code = response.getInteger("code");
            // 兼容 0 和 200 两种成功码
            if (code == null || (code != 0 && code != 200)) {
                log.warn("parseRestToolsResponseAsVO: REST 返回错误, code={}, message={}",
                        code, response.getString("message"));
                return Collections.emptyList();
            }

            JSONArray data = response.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                log.info("parseRestToolsResponseAsVO: REST 返回空数据");
                return Collections.emptyList();
            }

            List<ToolVO> tools = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject toolJson = data.getJSONObject(i);
                String toolCode = toolJson.getString("toolCode");
                String toolName = toolJson.getString("toolName");
                String description = toolJson.getString("description");
                String toolType = toolJson.getString("toolType");

                ToolVO tool = ToolVO.builder()
                        .toolCode(toolCode != null ? toolCode : toolName)
                        .toolName(toolName != null ? toolName : toolCode)
                        .description(description != null ? description : "")
                        .sourceType(ToolSourceType.MCP)
                        .toolType(toolType != null ? ToolType.valueOf(toolType) : ToolType.READONLY)
                        .readOnly(true)
                        .build();

                tools.add(tool);
            }

            log.info("parseRestToolsResponseAsVO: 成功获取 {} 个工具", tools.size());
            return tools;
        } catch (Exception e) {
            log.error("parseRestToolsResponseAsVO 解析失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 将 McpSchema.Tool 列表转换为 ToolVO 列表。
     */
    private List<ToolVO> convertToToolVOs(List<McpSchema.Tool> mcpTools) {
        List<ToolVO> result = new ArrayList<>(mcpTools.size());
        for (McpSchema.Tool mcpTool : mcpTools) {
            result.add(ToolVO.builder()
                    .toolCode(mcpTool.name())
                    .toolName(mcpTool.name())
                    .description(mcpTool.description())
                    .sourceType(ToolSourceType.MCP)
                    .toolType(ToolType.READONLY)
                    .readOnly(true)
                    .build());
        }
        return result;
    }

    /**
     * 通过 HTTP 调用 MCP 工具（优先 REST 端点）。
     *
     * <p>v3.0 优先使用 REST 端点（/api/mcp/tools/{code}/invoke）调用工具，
     * 仅对 STREAMABLE_HTTP 协议尝试 JSON-RPC POST 请求。
     */
    private String invokeViaHttp(McpService service, String toolName, String arguments) {
        String endpoint = service.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return errorJson("MCP endpoint 为空");
        }

        // 优先使用 REST 端点（稳定可靠）
        String restResult = invokeViaRestFallback(service, toolName, arguments);
        if (restResult != null && !restResult.contains("\"success\":false")) {
            log.info("invokeViaHttp: REST 调用成功, tool={}", toolName);
            return restResult;
        }
        log.warn("invokeViaHttp: REST 调用失败, 尝试 JSON-RPC, tool={}, result={}", toolName, restResult);

        // 仅对 STREAMABLE_HTTP 协议尝试 JSON-RPC POST
        if (service.getProtocol() == McpProtocol.STREAMABLE_HTTP) {
            try {
                Map<String, Object> params = new HashMap<>();
                params.put("name", toolName);
                params.put("arguments", parseArguments(arguments));

                Map<String, Object> request = new HashMap<>();
                request.put("jsonrpc", "2.0");
                request.put("method", "tools/call");
                request.put("id", System.currentTimeMillis());
                request.put("params", params);

                String json = JSON.toJSONString(request);
                log.info("invokeViaHttp: JSON-RPC POST {} tool={}", endpoint, toolName);

                ResponseEntity<String> response = restTemplate.postForEntity(
                        endpoint,
                        new HttpEntity<>(json, buildHttpHeaders(service)),
                        String.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    log.info("invokeViaHttp: JSON-RPC 调用成功");
                    return formatHttpInvokeResult(response.getBody());
                }
            } catch (Exception e) {
                log.warn("invokeViaHttp: JSON-RPC 失败: endpoint={}, tool={}, error={}",
                        endpoint, toolName, e.getMessage());
            }
        } else {
            log.info("invokeViaHttp: 协议={}, 跳过 JSON-RPC", service.getProtocol());
        }

        return restResult;
    }

    /**
     * 通过 REST 端点调用 MCP 工具（回退方案）。
     *
     * <p>将 SSE/HTTP 端点转换为 REST 端点格式，调用工具。
     * 例如: http://127.0.0.1:8083/sse → http://127.0.0.1:8083/api/mcp/tools/{code}/invoke
     */
    private String invokeViaRestFallback(McpService service, String toolName, String arguments) {
        String endpoint = service.getEndpoint();
        String restEndpoint = buildRestEndpoint(endpoint);
        if (restEndpoint == null) {
            return errorJson("MCP REST 端点构造失败");
        }
        // 将 /api/mcp/tools 替换为 /api/mcp/tools/{toolName}/invoke
        restEndpoint = restEndpoint.replace("/api/mcp/tools", "/api/mcp/tools/" + toolName + "/invoke");

        log.info("invokeViaRestFallback: 尝试 REST 端点={}, tool={}", restEndpoint, toolName);

        try {
            Map<String, Object> params = parseArguments(arguments);

            ResponseEntity<String> response = restTemplate.postForEntity(
                    restEndpoint,
                    new HttpEntity<>(params, buildHttpHeaders(service)),
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("invokeViaRestFallback: REST 调用成功");
                return formatRestInvokeResult(response.getBody());
            }

            return errorJson("MCP REST 调用失败: HTTP " + response.getStatusCode());
        } catch (Exception e) {
            log.warn("invokeViaRestFallback 失败: endpoint={}, tool={}, error={}", restEndpoint, toolName, e.getMessage());
            return errorJson("MCP REST 调用异常: " + e.getMessage());
        }
    }

    /**
     * 解析 REST 端点调用响应为 JSON 结果。
     *
     * <p>REST 响应格式: {"code": 0, "message": "success", "data": {...}}
     */
    private String formatRestInvokeResult(String responseBody) {
        try {
            JSONObject response = JSON.parseObject(responseBody);
            Integer code = response.getInteger("code");
            // 兼容 0 和 200 两种成功码
            if (code == null || (code != 0 && code != 200)) {
                return errorJson("MCP REST 错误: " + response.getString("message"));
            }

            Object data = response.get("data");
            Map<String, Object> output = new HashMap<>(2);
            output.put("success", true);
            output.put("output", data != null ? JSON.toJSONString(data) : "null");
            return JSON.toJSONString(output);
        } catch (Exception e) {
            log.error("formatRestInvokeResult 解析失败", e);
            return errorJson("MCP REST 响应解析失败: " + e.getMessage());
        }
    }

    /**
     * 构建 HTTP 请求头。
     */
    private HttpHeaders buildHttpHeaders(McpService service) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json, text/event-stream");

        if (service.getAuthType() != null && service.getAuthType() != ApiAuthType.NONE
                && service.getAuthConfig() != null && !service.getAuthConfig().isBlank()) {
            try {
                JSONObject config = JSON.parseObject(service.getAuthConfig());
                switch (service.getAuthType()) {
                    case API_KEY -> {
                        String apiKey = config.getString("apiKey");
                        if (apiKey != null && !apiKey.isBlank()) {
                            headers.set("X-API-Key", apiKey);
                        }
                    }
                    case BEARER -> {
                        String token = config.getString("token");
                        if (token != null && !token.isBlank()) {
                            headers.set("Authorization", "Bearer " + token);
                        }
                    }
                    default -> {}
                }
            } catch (Exception e) {
                log.warn("解析鉴权配置失败", e);
            }
        }
        return headers;
    }

    /**
     * 解析 tools/list 响应为 ToolVO 列表。
     */
    private List<ToolVO> parseToolsListResponseAsVO(String responseBody) {
        try {
            JSONObject response = JSON.parseObject(responseBody);
            JSONObject result = response.getJSONObject("result");
            if (result == null) {
                log.warn("listToolsViaHttp: 响应无 result 字段");
                return Collections.emptyList();
            }

            JSONArray toolsArray = result.getJSONArray("tools");
            if (toolsArray == null || toolsArray.isEmpty()) {
                log.info("listToolsViaHttp: MCP 服务无工具");
                return Collections.emptyList();
            }

            List<ToolVO> tools = new ArrayList<>();
            for (int i = 0; i < toolsArray.size(); i++) {
                JSONObject toolJson = toolsArray.getJSONObject(i);
                String name = toolJson.getString("name");
                String description = toolJson.getString("description");
                String inputSchemaJson = null;
                JSONObject inputSchema = toolJson.getJSONObject("inputSchema");
                if (inputSchema != null) {
                    inputSchemaJson = JSON.toJSONString(inputSchema);
                }

                ToolVO tool = ToolVO.builder()
                        .toolCode(name)
                        .toolName(name)
                        .description(description != null ? description : "")
                        .sourceType(ToolSourceType.MCP)
                        .toolType(ToolType.READONLY)
                        .readOnly(true)
                        .inputSchema(inputSchemaJson)
                        .build();

                tools.add(tool);
            }

            log.info("listToolsViaHttp: 成功获取 {} 个工具", tools.size());
            return tools;
        } catch (Exception e) {
            log.error("parseToolsListResponseAsVO 解析失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 解析 tools/call 响应为 JSON 结果。
     */
    private String formatHttpInvokeResult(String responseBody) {
        try {
            JSONObject response = JSON.parseObject(responseBody);
            JSONObject result = response.getJSONObject("result");
            if (result == null) {
                JSONObject error = response.getJSONObject("error");
                if (error != null) {
                    return errorJson("MCP 错误: " + error.getString("message"));
                }
                return errorJson("MCP 响应无 result");
            }

            JSONArray content = result.getJSONArray("content");
            StringBuilder sb = new StringBuilder();
            if (content != null) {
                for (int i = 0; i < content.size(); i++) {
                    JSONObject item = content.getJSONObject(i);
                    String text = item.getString("text");
                    if (text != null) {
                        sb.append(text);
                    }
                }
            }

            boolean isError = result.getBooleanValue("isError");
            Map<String, Object> output = new HashMap<>(3);
            output.put("success", !isError);
            output.put("output", sb.toString());
            if (isError) {
                output.put("error", sb.toString());
            }
            return JSON.toJSONString(output);
        } catch (Exception e) {
            log.error("formatHttpInvokeResult 解析失败", e);
            return errorJson("MCP 响应解析失败: " + e.getMessage());
        }
    }
}
