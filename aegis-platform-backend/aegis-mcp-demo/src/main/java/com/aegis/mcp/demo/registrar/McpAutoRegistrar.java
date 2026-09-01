package com.aegis.mcp.demo.registrar;

import com.aegis.mcp.demo.client.AdminMcpClient;
import com.aegis.mcp.demo.collector.McpToolCollector;
import com.aegis.mcp.demo.config.AegisMcpDemoProperties;
import com.aegis.mcp.demo.dto.McpServiceRegisterRequest;
import com.aegis.mcp.demo.dto.McpToolDefinition;
import com.aegis.mcp.demo.dto.Result;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP Demo 启动后自动向 aegis-admin 注册的 Runner。
 *
 * <p>注册流程：
 * <ol>
 *   <li>通过 {@link McpToolCollector} 扫描所有 {@code @McpTool} 注解方法，生成工具定义列表</li>
 *   <li>构造 {@link McpServiceRegisterRequest}，包含 MCP 服务元信息 + 工具列表</li>
 *   <li>调用 admin 的 Service-to-Service 自注册端点，完成服务创建 + 工具入库</li>
 * </ol>
 *
 * <p>调用路径：{@code POST /api/admin/resource/mcp/services/register}
 * （Service-to-Service 端点，由 admin SecurityConfig 放行）。
 *
 * @see McpToolCollector
 * @see AdminMcpClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpAutoRegistrar implements ApplicationRunner {

    private final AdminMcpClient adminMcpClient;
    private final AegisMcpDemoProperties properties;
    private final McpToolCollector toolCollector;

    @Value("${aegis.mcp.demo.server-key:}")
    private String serverKey;

    @Value("${server.port:8083}")
    private int serverPort;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.isAutoRegister()) {
            log.info("Aegis MCP Demo auto-registration is disabled, skip register.");
            return;
        }

        updateEndpointIfNeeded();

        List<McpToolDefinition> tools = toolCollector.collect();
        log.info("Collected {} MCP tools for registration", tools.size());

        McpServiceRegisterRequest request = buildRequest(tools);

        log.info("Starting auto-registration to aegis-admin: code={}, endpoint={}, tools={}, adminUrl={}",
                request.getMcpCode(), request.getEndpoint(), tools.size(), properties.getAdminBaseUrl());

        int maxRetries = properties.getMaxRetries();
        long intervalMs = properties.getRetryIntervalMs();
        boolean success = false;
        Long registeredId = null;
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Result<Long> result = adminMcpClient.registerService(request, serverKey);
                if (result != null && result.isSuccess() && result.getData() != null) {
                    registeredId = result.getData();
                    success = true;
                    break;
                }
                log.warn("Register attempt {} failed: response={}", attempt, result);
            } catch (FeignException e) {
                lastError = e;
                if (e.status() == 409) {
                    log.info("MCP service already registered (409 Conflict). Treating as success for code={}", request.getMcpCode());
                    log.info("Auto-registration succeeded (already registered)! code={}, tools={}",
                            request.getMcpCode(), tools.size());
                    return;
                }
                log.warn("Register attempt {} threw FeignException: status={}, body={}",
                        attempt, e.status(), e.responseBody());
            } catch (Exception e) {
                lastError = e;
                log.warn("Register attempt {} failed: {}", attempt, e.getMessage());
            }

            if (attempt < maxRetries) {
                log.info("Retry after {} ms (attempt {}/{})", intervalMs, attempt, maxRetries);
                Thread.sleep(intervalMs);
            }
        }

        if (success) {
            log.info("Auto-registration succeeded! MCP service id={}, code={}, tools={}",
                    registeredId, request.getMcpCode(), tools.size());
        } else {
            log.error("Auto-registration failed after {} attempts. Last error: {}", maxRetries,
                    lastError != null ? lastError.getMessage() : "unknown");
            log.warn("Please ensure aegis-admin is running at {} and MCP registration endpoint is reachable.",
                    properties.getAdminBaseUrl());
        }
    }

    private void updateEndpointIfNeeded() {
        String endpoint = properties.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            String protocol = properties.getProtocol();
            String path = "SSE".equalsIgnoreCase(protocol) ? "/sse" : "/mcp";
            endpoint = "http://127.0.0.1:" + serverPort + path;
            properties.setEndpoint(endpoint);
            log.info("MCP endpoint set from protocol {}: {}", protocol, endpoint);
        }
    }

    private McpServiceRegisterRequest buildRequest(List<McpToolDefinition> tools) {
        return McpServiceRegisterRequest.builder()
                .mcpCode(properties.getMcpCode())
                .mcpName(properties.getMcpName())
                .icon(properties.getIcon())
                .provider(properties.getProvider())
                .description(properties.getDescription())
                .version(properties.getVersion())
                .endpoint(properties.getEndpoint())
                .protocol(properties.getProtocol())
                .authType(properties.getAuthType())
                .authConfig(properties.getAuthConfig())
                .securityLevel(properties.getSecurityLevel())
                .status(properties.getStatus())
                .tools(tools)
                .build();
    }
}
