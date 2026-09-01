package com.aegis.admin.service.resource;

import com.aegis.core.dto.resource.ToolVO;
import com.aegis.core.enums.resource.McpProtocol;
import com.aegis.core.enums.resource.ToolSourceType;
import com.aegis.core.enums.resource.ToolType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端服务 — 查询 MCP Server 工具列表。
 *
 * <p>支持多种协议的工具查询，按优先级依次尝试：
 * <ol>
 *   <li>REST API：GET {endpoint}/tools — 自定义 REST 端点（最稳定）</li>
 *   <li>Streamable HTTP：POST JSON-RPC tools/list</li>
 *   <li>SSE 协议：GET /sse → initialize → tools/list</li>
 * </ol>
 *
 * <p>所有路径均失败时返回空列表，由上层业务降级读 DB 缓存。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class McpClientService {

    private final RestTemplate restTemplate;

    public McpClientService(
            @Value("${aegis.mcp.client.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${aegis.mcp.client.read-timeout-ms:15000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
        log.info("McpClientService RestTemplate 超时配置: connect={}ms, read={}ms", connectTimeoutMs, readTimeoutMs);
    }

    private final ConcurrentHashMap<String, CacheEntry> toolCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000;

    private record CacheEntry(List<ToolVO> tools, long expireAt) {
        boolean isExpired() { return System.currentTimeMillis() > expireAt; }
    }

    /**
     * 查询 MCP Server 暴露的工具列表。
     */
    public List<ToolVO> queryTools(String endpoint, McpProtocol protocol) {
        if (endpoint == null || endpoint.isBlank()) {
            log.warn("[MCP-Client] endpoint 为空, 跳过工具查询");
            return List.of();
        }

        String cacheKey = endpoint + "|" + (protocol != null ? protocol.name() : "AUTO");
        CacheEntry cached = toolCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("[MCP-Client] 使用缓存, endpoint={}, count={}", endpoint, cached.tools().size());
            return cached.tools();
        }
        if (cached != null) {
            toolCache.remove(cacheKey, cached);
        }

        log.info("[MCP-Client] 开始查询工具列表, endpoint={}, protocol={}", endpoint, protocol);

        List<ToolVO> result = queryToolsViaRestApi(endpoint);
        
        if (result.isEmpty()) {
            result = queryToolsViaHttp(endpoint);
        }
        
        if (result.isEmpty()) {
            result = queryToolsViaSse(endpoint);
        }

        if (!result.isEmpty()) {
            toolCache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis() + CACHE_TTL_MS));
            log.info("[MCP-Client] 工具查询成功, endpoint={}, toolCount={}", endpoint, result.size());
        } else {
            log.warn("[MCP-Client] 所有协议均查询失败, endpoint={}, protocol={}", endpoint, protocol);
        }
        return result;
    }

    /**
     * 清除指定端点的缓存。
     */
    public void invalidateCache(String endpoint) {
        if (endpoint != null) {
            toolCache.remove(endpoint + "|" + McpProtocol.SSE);
            toolCache.remove(endpoint + "|" + McpProtocol.STREAMABLE_HTTP);
            toolCache.remove(endpoint + "|AUTO");
        }
    }

    /**
     * 通过 REST API 查询工具列表（最稳定方式）。
     * GET {baseUrl}/api/mcp/tools
     */
    private List<ToolVO> queryToolsViaRestApi(String endpoint) {
        try {
            String baseUrl = extractBaseUrl(endpoint);
            String url = baseUrl + "/api/mcp/tools";
            log.info("[MCP-Client] 尝试 REST API 查询, url={}", url);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<ToolVO> tools = parseRestApiResponse(response.getBody());
                if (!tools.isEmpty()) {
                    log.info("[MCP-Client] REST API 查询成功, count={}", tools.size());
                    return tools;
                }
            }
        } catch (Exception e) {
            log.debug("[MCP-Client] REST API 查询失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 通过 HTTP POST JSON-RPC 查询工具（Streamable HTTP 协议）。
     */
    private List<ToolVO> queryToolsViaHttp(String endpoint) {
        try {
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/list",
                    "params", Map.of(),
                    "id", 1
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                List<ToolVO> tools = parseTools(response.getBody());
                log.info("[MCP-Client] HTTP 工具查询成功, count={}", tools.size());
                return tools;
            }
        } catch (Exception e) {
            log.debug("[MCP-Client] HTTP 工具查询失败: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * 通过 SSE 协议查询工具（完整 SSE 协议流程）。
     * GET /sse → 获取 endpoint 事件 → POST JSON-RPC。
     */
    private List<ToolVO> queryToolsViaSse(String sseEndpoint) {
        HttpURLConnection conn = null;
        try {
            log.info("[MCP-Client] SSE: 建立连接, endpoint={}", sseEndpoint);
            conn = (HttpURLConnection) new URI(sseEndpoint).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "text/event-stream");

            int status = conn.getResponseCode();
            if (status != 200) {
                log.info("[MCP-Client] SSE 连接失败, status={}", status);
                return List.of();
            }
            log.info("[MCP-Client] SSE 连接成功, 读取 endpoint 事件...");

            String endpointPath = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                long start = System.currentTimeMillis();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (System.currentTimeMillis() - start > 5000) break;
                    if (line.startsWith("data:")) {
                        endpointPath = line.substring("data:".length()).trim();
                        if (!endpointPath.isEmpty()) break;
                    }
                }
            }

            if (endpointPath == null) {
                log.info("[MCP-Client] SSE 未获取到 endpoint 事件");
                return List.of();
            }
            log.info("[MCP-Client] SSE 获取到 endpoint: {}", endpointPath);

            java.net.URL sseUrl = new URI(sseEndpoint).toURL();
            String baseUrl = sseUrl.getProtocol() + "://" + sseUrl.getHost()
                    + (sseUrl.getPort() != -1 ? ":" + sseUrl.getPort() : "");
            String messageUrl = baseUrl + endpointPath;

            Map<String, Object> initRequest = Map.of(
                    "jsonrpc", "2.0",
                    "method", "initialize",
                    "params", Map.of(
                            "protocolVersion", "2024-11-05",
                            "capabilities", Map.of(),
                            "clientInfo", Map.of("name", "aegis-admin", "version", "1.0.0")
                    ),
                    "id", 1
            );

            String initResp = postJsonRpc(messageUrl, initRequest);
            if (initResp == null || !initResp.contains("\"result\"")) {
                log.info("[MCP-Client] SSE initialize 失败");
                return List.of();
            }

            postJsonRpc(messageUrl, Map.of(
                    "jsonrpc", "2.0",
                    "method", "notifications/initialized"
            ));

            String toolsResp = postJsonRpc(messageUrl, Map.of(
                    "jsonrpc", "2.0",
                    "method", "tools/list",
                    "id", 2
            ));

            if (toolsResp == null) return List.of();
            log.info("[MCP-Client] SSE tools/list 成功");
            return parseTools(toolsResp);
        } catch (Exception e) {
            log.info("[MCP-Client] SSE 工具查询异常: {}", e.getMessage());
            return List.of();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String postJsonRpc(String url, Map<String, Object> request) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
            return resp.getBody();
        } catch (Exception e) {
            log.debug("[MCP-Client] JSON-RPC 请求失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 endpoint 中提取基础 URL（协议 + 主机 + 端口）。
     */
    private String extractBaseUrl(String endpoint) {
        try {
            java.net.URL url = new URI(endpoint).toURL();
            return url.getProtocol() + "://" + url.getHost()
                    + (url.getPort() != -1 ? ":" + url.getPort() : "");
        } catch (Exception e) {
            String path = endpoint.contains("?") ? endpoint.substring(0, endpoint.indexOf('?')) : endpoint;
            if (path.endsWith("/sse")) {
                path = path.substring(0, path.length() - 4);
            } else if (path.endsWith("/mcp")) {
                path = path.substring(0, path.length() - 4);
            }
            return path;
        }
    }

    /**
     * 解析 REST API 响应为 ToolVO 列表。
     * 响应格式: {"code":200,"data":[{"toolCode":"add","description":"...","inputSchema":"{...}"}]}
     */
    private List<ToolVO> parseRestApiResponse(String responseJson) {
        List<ToolVO> tools = new ArrayList<>();
        try {
            com.alibaba.fastjson2.JSONObject response = com.alibaba.fastjson2.JSON.parseObject(responseJson);
            
            com.alibaba.fastjson2.JSONArray data = response.getJSONArray("data");
            if (data == null) return tools;

            for (int i = 0; i < data.size(); i++) {
                com.alibaba.fastjson2.JSONObject toolJson = data.getJSONObject(i);
                ToolVO tool = new ToolVO();
                tool.setToolCode(toolJson.getString("toolCode"));
                tool.setToolName(toolJson.getString("toolName"));
                tool.setDescription(toolJson.getString("description"));
                String toolType = toolJson.getString("toolType");
                tool.setToolType(toolType != null ? ToolType.valueOf(toolType) : ToolType.READONLY);
                tool.setSourceType(ToolSourceType.MCP);
                Boolean readOnly = toolJson.getBoolean("readOnly");
                tool.setReadOnly(readOnly != null && readOnly);
                tool.setInputSchema(toolJson.getString("inputSchema"));
                tool.setOutputSchema(toolJson.getString("outputSchema"));
                tools.add(tool);
            }
        } catch (Exception e) {
            log.error("[MCP-Client] REST API 响应解析失败: {}", e.getMessage());
        }
        return tools;
    }

    /**
     * 解析 MCP tools/list 响应为 ToolVO 列表。
     */
    private List<ToolVO> parseTools(String responseJson) {
        List<ToolVO> tools = new ArrayList<>();
        try {
            com.alibaba.fastjson2.JSONObject response = com.alibaba.fastjson2.JSON.parseObject(responseJson);
            com.alibaba.fastjson2.JSONObject result = response.getJSONObject("result");
            if (result == null) {
                com.alibaba.fastjson2.JSONObject error = response.getJSONObject("error");
                if (error != null) {
                    log.warn("[MCP-Client] MCP 响应错误: code={}, message={}",
                            error.getInteger("code"), error.getString("message"));
                }
                return tools;
            }

            com.alibaba.fastjson2.JSONArray toolsArray = result.getJSONArray("tools");
            if (toolsArray == null) return tools;

            for (int i = 0; i < toolsArray.size(); i++) {
                com.alibaba.fastjson2.JSONObject toolJson = toolsArray.getJSONObject(i);
                ToolVO tool = new ToolVO();
                tool.setToolCode(toolJson.getString("name"));
                tool.setToolName(toolJson.getString("name"));
                tool.setDescription(toolJson.getString("description"));
                tool.setToolType(ToolType.READONLY);
                tool.setSourceType(ToolSourceType.MCP);
                tool.setReadOnly(true);
                com.alibaba.fastjson2.JSONObject inputSchema = toolJson.getJSONObject("inputSchema");
                if (inputSchema != null) {
                    tool.setInputSchema(com.alibaba.fastjson2.JSON.toJSONString(inputSchema));
                }
                tools.add(tool);
            }
        } catch (Exception e) {
            log.error("[MCP-Client] 工具解析失败, error={}, response={}", e.getMessage(), responseJson);
        }
        return tools;
    }
}
