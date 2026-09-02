package com.aegis.runtime.integration.tool;

import com.alibaba.fastjson2.JSON;
import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.dto.security.SecurityPolicyContext;
import com.aegis.runtime.service.policy.AegisSecurityPolicyEngine;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Aegis HTTP 请求工具（AgentScope 2.0 ToolBase 子类模式）。
 *
 * <p>继承 {@link ToolBase} 实现 {@code http_request} 工具，支持 GET/POST 方法，
 * 可自定义请求头和请求体。内置 SSRF 防护，禁止访问内网地址和危险协议。
 *
 * <h3>SSRF 防护</h3>
 * <ul>
 *   <li>{@link #checkPermissions(Map, PermissionContextState)} 在调用前拦截：
 *       仅允许 http/https 协议，DNS 解析后用 InetAddress 校验所有解析 IP
 *       不在回环/私有段/链路本地/任意本地/多播范围内</li>
 *   <li>{@link #callAsync(ToolCallParam)} 执行时再次校验（双保险）</li>
 * </ul>
 *
 * <h3>迁移说明</h3>
 * <p>从 {@code BuiltinToolExecutor.httpRequest} 方法迁移而来，保留原有业务逻辑。
 * 与 {@link AegisBuiltinTools}（@Tool 注解模式）不同，本类因需覆写权限检查方法，
 * 采用 ToolBase 子类模式，由 {@code Toolkit.registerAgentTool(AgentTool)} 注册。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class AegisHttpTool extends ToolBase {

    /** v4.0 新增：统一安全策略引擎（出站策略联动） */
    @Autowired
    private AegisSecurityPolicyEngine securityPolicyEngine;

    /** http_request 的 inputSchema（JSON Schema） */
    private static final Map<String, Object> INPUT_SCHEMA = JSON.parseObject(
            "{\"type\":\"object\",\"properties\":{"
                    + "\"url\":{\"type\":\"string\",\"description\":\"请求 URL\"},"
                    + "\"method\":{\"type\":\"string\",\"enum\":[\"GET\",\"HEAD\",\"OPTIONS\"],\"description\":\"HTTP 方法（默认 GET，仅支持只读方法）\"},"
                    + "\"headers\":{\"type\":\"object\",\"description\":\"自定义请求头\"}"
                    + "},\"required\":[\"url\"],\"additionalProperties\":false}");

    /** HTTP 客户端 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 构造函数：通过 {@link ToolBase#builder()} 描述工具元数据。
     *
     * <p>注意：{@code ToolBase.Builder} 没有 {@code build()} 方法，
     * Builder 实例直接传给 {@code super(ToolBase.Builder)} 构造函数（参见父类保护构造器）。
     */
    public AegisHttpTool() {
        super(ToolBase.builder()
                .name("http_request")
                .description("【通用 HTTP 请求 - 只读工具】\n"
                        + "触发场景: 需要调用特定 REST API 且无专用工具覆盖时。\n"
                        + "调用规则:\n"
                        + "- 严禁用本工具查询天气（可用 web_search + LLM 后处理）。\n"
                        + "- 严禁用本工具做联网搜索（有 web_search）。\n"
                        + "- 仅在专用工具和 web_search 都不适用时使用，需在 thinking 中说明原因。\n"
                        + "- 仅支持只读方法（GET/HEAD/OPTIONS），写操作请使用专用工具。\n"
                        + "- 内置 SSRF 防护：禁止访问内网地址和 file/ftp 协议。\n"
                        + "参数: url（必填）、method（可选，默认 GET）、headers（可选）。\n"
                        + "返回: {status, url, result}。")
                .inputSchema(INPUT_SCHEMA));
    }

    /**
     * 带自定义名称的构造函数，用于创建别名工具（如 network_request）。
     *
     * @param toolName 工具名称
     */
    public AegisHttpTool(String toolName) {
        super(ToolBase.builder()
                .name(toolName)
                .description("【网络请求 - 发送HTTP请求】\n"
                        + "触发场景: 需要向外部服务发送HTTP请求。\n"
                        + "安全限制: 禁止访问内网地址（127.0.0.1, localhost, 10.x.x.x等）。\n"
                        + "参数: url（必填）, method（可选，默认GET）, headers（可选）, body（可选）。\n"
                        + "返回: HTTP响应（状态码、响应头、响应体）。")
                .inputSchema(INPUT_SCHEMA));
    }

    /**
     * 权限检查：在工具执行前拦截 SSRF 风险请求。
     *
     * <p>校验逻辑：
     * <ul>
     *   <li>file:// / ftp:// 协议直接拒绝</li>
     *   <li>URL 含 BLOCKED_HOSTS 中的内网地址直接拒绝</li>
     *   <li>其他情况放行（passthrough，交由后续规则或 callAsync 内二次校验）</li>
     * </ul>
     *
     * @param input 工具入参（含 url 字段）
     * @param ctx   权限上下文（当前未使用，保留供未来扩展）
     * @return {@link PermissionDecision}：DENY 表示拒绝，PASSTHROUGH 表示放行
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState ctx) {
        if (input == null) {
            return Mono.just(PermissionDecision.passthrough("no input"));
        }
        Object urlObj = input.get("url");
        if (urlObj == null) {
            return Mono.just(PermissionDecision.passthrough("no url in input"));
        }
        String url = String.valueOf(urlObj);

        // 1. SSRF 基础防护
        String violation = checkUrlSafety(url);
        if (violation != null) {
            log.warn("http_request SSRF 拦截: url={}, reason={}", url, violation);
            return Mono.just(PermissionDecision.deny("URL blocked: " + violation));
        }

        // 2. v4.0 新增：出站策略联动（白名单域名/黑名单 IP/CIDR）
        if (securityPolicyEngine != null) {
            try {
                PolicyDecision decision = securityPolicyEngine.evaluateOutboundPolicy(
                        SecurityPolicyContext.builder()
                                .action(SecurityPolicyContext.Action.NETWORK_ACCESS)
                                .content(url)
                                .build());
                if (decision.isReject()) {
                    log.warn("http_request 出站策略拦截: url={}, reason={}", url, decision.getReason());
                    return Mono.just(PermissionDecision.deny("出站策略拒绝: " + decision.getReason()));
                }
                if (decision.isAuditOnly()) {
                    log.debug("http_request 出站策略审计放行: url={}", url);
                }
            } catch (Exception e) {
                log.error("http_request 出站策略评估异常，透传: url={}", url, e);
            }
        }

        return Mono.just(PermissionDecision.passthrough("url passed all checks"));
    }

    /**
     * 异步执行 HTTP 请求。
     *
     * <p>提取 url/method/body/headers 入参，执行请求，返回 {@link ToolResultBlock}。
     * 执行前再次进行 SSRF 校验（双保险）。
     *
     * @param param 工具调用参数
     * @return HTTP 响应结果块
     */
    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param.getInput();
        String toolCallId = extractToolCallId(param);
        log.info("========== TOOL http_request EXECUTING: toolCallId={}, input={} ==========", toolCallId, input);

        String url = input != null ? getString(input, "url") : null;
        String method = input != null ? getString(input, "method") : null;

        // 参数校验
        if (url == null || url.isEmpty()) {
            log.warn("http_request: url is empty");
            return Mono.just(errorResult(toolCallId, "Parameter 'url' is required"));
        }
        if (method == null || method.isEmpty()) {
            method = "GET";
        }
        final String httpMethod = method.toUpperCase();

        // HTTP 方法风险校验：仅允许只读方法（GET/HEAD/OPTIONS）
        // 写操作（POST/PUT/DELETE/PATCH）需使用专用工具，不在此工具执行
        if (!"GET".equals(httpMethod) && !"HEAD".equals(httpMethod) && !"OPTIONS".equals(httpMethod)) {
            log.warn("http_request 写操作被拦截: method={}, url={}", httpMethod, url);
            return Mono.just(errorResult(toolCallId,
                    "http_request 工具仅支持只读方法（GET/HEAD/OPTIONS）。"
                    + "检测到写操作方法: " + httpMethod + "。"
                    + "请使用 GET 方法查询信息，或使用专用工具执行写操作。"));
        }

        // 二次 SSRF 校验（双保险）
        String violation = checkUrlSafety(url);
        if (violation != null) {
            log.warn("http_request callAsync SSRF 拦截: url={}, reason={}", url, violation);
            return Mono.just(errorResult(toolCallId, "URL blocked: " + violation));
        }

        return Mono.fromCallable(() -> {
            log.info("http_request: start HTTP call: method={}, url={}", httpMethod, url);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15));

            // 添加自定义请求头
            if (input != null) {
                Object headersObj = input.get("headers");
                if (headersObj instanceof Map) {
                    Map<?, ?> headers = (Map<?, ?>) headersObj;
                    for (Map.Entry<?, ?> entry : headers.entrySet()) {
                        builder.header(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }

            if ("GET".equals(httpMethod)) {
                builder.GET();
            } else if ("HEAD".equals(httpMethod)) {
                builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            } else if ("OPTIONS".equals(httpMethod)) {
                builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            } else {
                return errorResult(toolCallId,
                        "Unsupported method: " + httpMethod + ". Only GET/HEAD/OPTIONS are supported.");
            }

            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String respBody = response.body();
            if (respBody != null && respBody.length() > 8000) {
                respBody = respBody.substring(0, 8000) + "\n... (truncated)";
            }

            Map<String, Object> result = new HashMap<>(3);
            result.put("status", response.statusCode());
            result.put("url", url);
            result.put("result", respBody);
            String json = JSON.toJSONString(result);
            log.info("========== TOOL http_request COMPLETED: statusCode={}, resultLen={} ==========", response.statusCode(), json.length());
            return successResult(toolCallId, json);
        }).onErrorResume(e -> {
            log.error("http_request 执行失败: url={}", url, e);
            String rawMsg = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "unknown";
            return Mono.just(errorResult(toolCallId,
                    "HTTP request failed (" + rawMsg + "). "
                            + "Please inform the user that this operation could not be completed."));
        });
    }

    // ============ 辅助方法 ============

    /**
     * 从 Map 中安全提取 String 值。
     */
    private String getString(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * 从 ToolCallParam 中提取工具调用 ID（用于与 ToolUseBlock 配对）。
     */
    private String extractToolCallId(ToolCallParam param) {
        if (param == null || param.getToolUseBlock() == null) {
            return null;
        }
        return param.getToolUseBlock().getId();
    }

    /**
     * 构造成功结果块。
     */
    private ToolResultBlock successResult(String toolCallId, String text) {
        return new ToolResultBlock(
                toolCallId,
                "http_request",
                java.util.List.of(TextBlock.builder().text(text != null ? text : "").build()),
                Map.of(),
                ToolResultState.SUCCESS);
    }

    /**
     * 构造错误结果块。
     */
    private ToolResultBlock errorResult(String toolCallId, String errorMessage) {
        return new ToolResultBlock(
                toolCallId,
                "http_request",
                java.util.List.of(TextBlock.builder().text("Error: " + errorMessage).build()),
                Map.of(),
                ToolResultState.ERROR);
    }

    /**
     * 校验 URL 安全性（SSRF 防护）。
     *
     * <p>P0 CMD-03/SEC-03 修复：废弃 contains 字符串匹配，改用 DNS 解析 + InetAddress
     * 精确判定，防止十进制/十六进制/八进制 IP、IPv6 本地、0.0.0.0、DNS rebinding 绕过。
     *
     * @param url 待校验 URL
     * @return 违规原因，{@code null} 表示安全
     */
    private String checkUrlSafety(String url) {
        if (url == null || url.isEmpty()) {
            return "empty url";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            return "invalid url format";
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return "missing url scheme";
        }
        // P0 SEC-03 修复：仅允许 http/https，拒绝 file/ftp/gopher 等
        String lowerScheme = scheme.toLowerCase();
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            return "protocol '" + lowerScheme + "' is not allowed, only http/https";
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            // P0 SEC-03 修复：host 为空（如 file:///etc/passwd）直接拒绝
            return "missing or empty host";
        }
        // P0 CMD-03 修复：DNS 解析 host 为 IP，逐个校验是否为内网/回环地址
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : addresses) {
                if (isForbiddenAddress(addr)) {
                    return "access to internal/loopback address is forbidden: "
                            + addr.getHostAddress() + " (resolved from " + host + ")";
                }
            }
        } catch (java.net.UnknownHostException e) {
            return "unable to resolve host: " + host;
        }
        return null;
    }

    /**
     * 判断 IP 地址是否为禁止访问的内网/回环地址。
     *
     * <p>覆盖：回环、私有段（10/172.16-31/192.168）、链路本地、任意本地、多播、IPv6 回环。
     */
    private boolean isForbiddenAddress(java.net.InetAddress addr) {
        return addr.isLoopbackAddress()      // 127.x.x.x, ::1
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isSiteLocalAddress()  // 10.x, 172.16-31.x, 192.168.x
                || addr.isLinkLocalAddress()  // 169.254.x.x, fe80::
                || addr.isMulticastAddress(); // 224.0.0.0/4, ff00::
    }
}
