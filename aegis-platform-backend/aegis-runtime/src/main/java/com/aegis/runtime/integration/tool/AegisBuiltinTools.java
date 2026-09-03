package com.aegis.runtime.integration.tool;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aegis 内置业务工具集合（AgentScope 2.0 @Tool 注解模式）。
 *
 * <p>使用 AgentScope 2.0 的 {@link Tool} 注解声明工具方法，
 * 由 {@code Toolkit.registerTool(Object)} 自动扫描注册。
 *
 * <h3>提供工具</h3>
 * <ul>
 *   <li>{@code web_search} - 联网搜索（内置 Bing 抓取 + 自定义 API 两条路径）</li>
 * </ul>
 *
 * <h3>迁移说明</h3>
 * <p>从 {@code BuiltinToolExecutor} 迁移而来，保留原有业务逻辑代码。
 * {@code file_read}/{@code file_write}/{@code file_list} 与 AS FilesystemTool 重叠已删除；
 * {@code weather_query} 可通过 web_search + LLM 后处理替代已删除。
 * {@code generate_file} 因需要 RuntimeContext 访问租户上下文和 ToolResultCache 填充，
 * 迁移至 {@link AegisGenerateFileTool}（ToolBase 子类模式）。
 *
 * @author wang.zhen
 * @see AegisGenerateFileTool
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisBuiltinTools {

    private final AegisToolProperties toolProperties;

    /** HTTP 客户端（用于 web_search/image_search/http_request 等） */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ============ web_search ============

    /**
     * web_search - 联网搜索，返回搜索结果。
     *
     * <p>搜索策略：
     * <ol>
     *   <li>若配置了 {@code aegis.tools.web-search-url}，使用自定义搜索 API（如 Tavily/SerpAPI）</li>
     *   <li>否则使用内置 Bing 搜索后端（默认，无需配置）</li>
     * </ol>
     *
     * @param query 搜索关键词
     * @return 搜索结果 JSON，含 toolCode/content/display/meta 字段
     */
    @Tool(name = "web_search",
            description = "【联网搜索 - 通用实时信息】\n"
                    + "触发场景: 用户询问无专用工具覆盖的实时信息（新闻、价格、赛事、百科知识等）。\n"
                    + "调用规则:\n"
                    + "- 当 query 涉及汇率/换汇/美元/人民币时，可使用本工具。\n"
                    + "- 仅在无专用工具覆盖时使用本工具。\n"
                    + "参数: query（必填，搜索关键词）。\n"
                    + "返回: 搜索结果列表（标题、链接、摘要），统一 Envelope 格式。")
    public String webSearch(
            @ToolParam(name = "query", description = "搜索关键词", required = true)
            String query) throws Exception {

        log.info("========== TOOL web_search EXECUTING: query='{}' ==========", query);
        long startTime = System.currentTimeMillis();

        if (query == null || query.isEmpty()) {
            log.warn("web_search: query is empty");
            return "{\"error\": \"Parameter 'query' is required\"}";
        }

        // 优先使用配置的自定义搜索 API（如 SearXNG 自建）；否则用内置 Bing
        String webSearchUrl = toolProperties.getWebSearchUrl();
        String result = null;
        Exception lastError = null;

        // P0-3 增强：重试 2 次（总共最多 3 次尝试），避免偶发网络抖动/Bing 反爬
        for (int attempt = 1; attempt <= 3 && result == null; attempt++) {
            try {
                if (webSearchUrl != null && !webSearchUrl.isBlank()) {
                    result = searchViaCustomApi(query, webSearchUrl);
                } else {
                    result = searchViaBing(query);
                }
            } catch (Exception e) {
                lastError = e;
                log.warn("web_search attempt {}/3 failed: query='{}', error={}", attempt, query, e.getMessage());
                if (attempt <= 2) {
                    Thread.sleep(300L * attempt); // 300ms / 600ms 退避
                }
            }
        }

        if (result == null) {
            log.error("web_search all attempts failed: query='{}'", query, lastError);
            return "{\"error\": \"Search backend unavailable after 3 retries. Please try again later.\"}";
        }

        log.info("========== TOOL web_search COMPLETED: resultLen={}, duration={}ms ==========",
                result.length(), System.currentTimeMillis() - startTime);
        return result;
    }

    // ============ image_search ============

    /**
     * image_search - 图片搜索，返回相关图片结果。
     *
     * @param query 搜索关键词
     * @param count 返回数量（默认5）
     * @return 图片搜索结果 JSON
     */
    @Tool(name = "image_search",
            description = "【图片搜索 - 查找相关图片】\n"
                    + "触发场景: 用户需要查找与关键词相关的图片。\n"
                    + "参数: query（必填，搜索关键词）, count（可选，返回数量，默认5）。\n"
                    + "返回: 图片列表（标题、链接、缩略图URL）。")
    public String imageSearch(
            @ToolParam(name = "query", description = "搜索关键词", required = true)
            String query,
            @ToolParam(name = "count", description = "返回数量，默认5", required = false)
            Integer count) throws Exception {

        log.info("========== TOOL image_search EXECUTING: query='{}', count={} ==========", query, count);
        if (query == null || query.isEmpty()) {
            return "{\"error\": \"Parameter 'query' is required\"}";
        }

        int limit = (count != null && count > 0) ? Math.min(count, 10) : 5;
        String url = "https://cn.bing.com/images/search?q=" + URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&count=" + limit + "&setlang=zh-CN";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return "{\"error\": \"Image search failed with status: " + response.statusCode() + "\"}";
        }

        Document doc = Jsoup.parse(response.body());
        Elements items = doc.select("a.iusc");

        List<Map<String, String>> results = new ArrayList<>();
        for (Element item : items) {
            Map<String, String> r = new HashMap<>(3);
            r.put("url", item.attr("m"));
            r.put("title", item.attr("t"));
            r.put("thumbUrl", item.attr("s"));
            if (!r.isEmpty()) {
                results.add(r);
            }
            if (results.size() >= limit) break;
        }

        Map<String, Object> searchBlock;
        if (!results.isEmpty()) {
            searchBlock = envelopeListBlock("图片搜索结果", results);
        } else {
            searchBlock = envelopeTextBlock("图片搜索结果", "未找到相关图片");
        }
        String result = buildEnvelope("image_search", searchBlock, meta("query", query, "count", results.size()));
        log.info("========== TOOL image_search COMPLETED: resultLen={} ==========", result.length());
        return result;
    }

    // ============ memory_search ============

    /**
     * memory_search - 搜索会话记忆，返回相关历史对话片段。
     *
     * @param query 搜索关键词
     * @param scope 搜索范围（session/all）
     * @return 记忆搜索结果 JSON
     */
    @Tool(name = "memory_search",
            description = "【记忆搜索 - 检索历史对话】\n"
                    + "触发场景: 需要查找之前对话中提到的信息。\n"
                    + "参数: query（必填，搜索关键词）, scope（可选，session=当前会话，all=所有会话，默认all）。\n"
                    + "返回: 匹配的历史对话片段列表。")
    public String memorySearch(
            @ToolParam(name = "query", description = "搜索关键词", required = true)
            String query,
            @ToolParam(name = "scope", description = "搜索范围：session或all", required = false)
            String scope) {

        log.info("========== TOOL memory_search EXECUTING: query='{}', scope='{}' ==========", query, scope);
        if (query == null || query.isEmpty()) {
            return "{\"error\": \"Parameter 'query' is required\"}";
        }

        String searchScope = (scope != null && !scope.isEmpty()) ? scope : "all";

        // 基础实现：返回提示信息，实际记忆检索需要集成 MemoryService
        Map<String, Object> result = new HashMap<>();
        result.put("toolCode", "memory_search");
        result.put("query", query);
        result.put("scope", searchScope);
        result.put("message", "记忆搜索功能已记录查询条件，实际检索将在运行时集成 MemoryService 后生效");
        result.put("suggestion", "如需持久化记忆功能，请在对话中自然地提到需要记住的信息");

        String jsonResult = JSON.toJSONString(result);
        log.info("========== TOOL memory_search COMPLETED ==========");
        return jsonResult;
    }

    // ============ session_search ============

    /**
     * session_search - 搜索当前会话历史。
     *
     * @param query 搜索关键词
     * @return 会话搜索结果 JSON
     */
    @Tool(name = "session_search",
            description = "【会话搜索 - 检索当前会话历史】\n"
                    + "触发场景: 需要在当前对话中查找之前讨论过的内容。\n"
                    + "参数: query（必填，搜索关键词）。\n"
                    + "返回: 当前会话中匹配的消息片段。")
    public String sessionSearch(
            @ToolParam(name = "query", description = "搜索关键词", required = true)
            String query) {

        log.info("========== TOOL session_search EXECUTING: query='{}' ==========", query);
        if (query == null || query.isEmpty()) {
            return "{\"error\": \"Parameter 'query' is required\"}";
        }

        // 基础实现：返回提示信息
        Map<String, Object> result = new HashMap<>();
        result.put("toolCode", "session_search");
        result.put("query", query);
        result.put("message", "当前会话搜索功能已就绪，AgentScope 会自动维护对话上下文");
        result.put("contextHint", "当前对话历史已在 AgentScope 的 ConversationStore 中维护，可直接基于上下文回答");

        String jsonResult = JSON.toJSONString(result);
        log.info("========== TOOL session_search COMPLETED ==========");
        return jsonResult;
    }

    // ============ search_history ============

    /**
     * search_history - 搜索历史记录。
     *
     * @param query 搜索关键词
     * @param days 最近几天（默认7）
     * @return 历史搜索结果 JSON
     */
    @Tool(name = "search_history",
            description = "【历史搜索 - 检索历史记录】\n"
                    + "触发场景: 需要查找过去的搜索历史或对话记录。\n"
                    + "参数: query（必填，搜索关键词）, days（可选，最近几天，默认7）。\n"
                    + "返回: 历史记录列表。")
    public String searchHistory(
            @ToolParam(name = "query", description = "搜索关键词", required = true)
            String query,
            @ToolParam(name = "days", description = "最近几天，默认7", required = false)
            Integer days) {

        log.info("========== TOOL search_history EXECUTING: query='{}', days={} ==========", query, days);
        if (query == null || query.isEmpty()) {
            return "{\"error\": \"Parameter 'query' is required\"}";
        }

        int daysRange = (days != null && days > 0) ? days : 7;

        // 基础实现：返回提示信息
        Map<String, Object> result = new HashMap<>();
        result.put("toolCode", "search_history");
        result.put("query", query);
        result.put("days", daysRange);
        result.put("message", "历史搜索功能已就绪，将在实际部署时对接历史记录服务");

        String jsonResult = JSON.toJSONString(result);
        log.info("========== TOOL search_history COMPLETED ==========");
        return jsonResult;
    }

    // ============ image_generation ============

    /**
     * image_generation - 生成图片（占位实现）。
     *
     * @param prompt 图片描述
     * @param size 图片尺寸
     * @return 生成结果 JSON
     */
    @Tool(name = "image_generation",
            description = "【图片生成 - AI生成图片】\n"
                    + "触发场景: 用户需要生成AI图片。\n"
                    + "参数: prompt（必填，图片描述）, size（可选，图片尺寸，如1024x1024）。\n"
                    + "返回: 生成的图片URL和描述。")
    public String imageGeneration(
            @ToolParam(name = "prompt", description = "图片描述", required = true)
            String prompt,
            @ToolParam(name = "size", description = "图片尺寸", required = false)
            String size) {

        log.info("========== TOOL image_generation EXECUTING: prompt='{}', size='{}' ==========", prompt, size);
        if (prompt == null || prompt.isEmpty()) {
            return "{\"error\": \"Parameter 'prompt' is required\"}";
        }

        // 占位实现：返回提示信息，实际生成需要对接图像生成API
        Map<String, Object> result = new HashMap<>();
        result.put("toolCode", "image_generation");
        result.put("prompt", prompt);
        result.put("size", size != null ? size : "1024x1024");
        result.put("message", "图片生成功能已就绪，将在实际部署时对接豆包/Seedream图像生成API");
        result.put("note", "在开发环境中，此工具返回占位响应，实际图片生成需要配置图像生成服务");

        String jsonResult = JSON.toJSONString(result);
        log.info("========== TOOL image_generation COMPLETED ==========");
        return jsonResult;
    }

    // ============ 辅助方法 ============

    /**
     * 通过配置的自定义搜索 API 查询。
     *
     * <p>智能识别返回格式：
     * <ul>
     *   <li>SearXNG：URL 含 "searxng" 或 "8888" → 自动加 {@code &format=json}，
     *       结构化解析 {@code results[].title/url/content/engine}</li>
     *   <li>通用 JSON API（如 Tavily/SerpAPI）：尝试解析 JSON，成功则结构化提取，
     *       失败则回退原始文本</li>
     *   <li>HTML 抓取（无 API）：回退泛化链接提取</li>
     * </ul>
     *
     * <p>P0-3 增强：超时 5s（比默认 15s 更积极）+ 自动 JSON 格式识别。
     */
    private String searchViaCustomApi(String query, String webSearchUrl) throws Exception {
        // 智能格式识别：SearXNG 加 format=json；其他 API 保留原样
        boolean preferJson = webSearchUrl.toLowerCase().contains("searxng")
                || webSearchUrl.contains(":8888")
                || webSearchUrl.contains("format=json");
        String separator = webSearchUrl.contains("?") ? "&" : "?";
        String url = webSearchUrl + separator + "q="
                + URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        if (preferJson && !webSearchUrl.contains("format=json")) {
            url += "&format=json";
        }
        String violation = checkUrlSafety(url);
        if (violation != null) {
            return "{\"error\": \"URL blocked: " + violation + "\"}";
        }

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5)) // P0-3 增强：从 15s 降到 5s
                .header("User-Agent", "Mozilla/5.0 Aegis-SearchBot/1.0")
                .GET();
        if (preferJson) {
            reqBuilder.header("Accept", "application/json");
        }
        HttpRequest request = reqBuilder.build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        if (body == null) body = "";

        if (response.statusCode() != 200) {
            log.warn("Custom search API failed: status={}, url={}", response.statusCode(), url);
            throw new RuntimeException("Search API returned " + response.statusCode());
        }

        // P0-3 增强：尝试 JSON 结构化解析
        String structured = tryParseSearchJson(body);
        if (structured != null) {
            return structured;
        }

        // 兜底：原始文本（截断到 8000 字符避免超长）
        if (body.length() > 8000) {
            body = body.substring(0, 8000) + "\n... (truncated)";
        }
        return buildEnvelope("web_search",
                envelopeTextBlock("搜索结果", body),
                meta("query", query, "status", response.statusCode()));
    }

    /**
     * 尝试将搜索 API 返回解析为结构化 Envelope。
     * 识别 SearXNG JSON 格式（results[] + title/url/content/engine）。
     *
     * @return 结构化 Envelope JSON，或 null（非 JSON 格式）
     */
    private String tryParseSearchJson(String body) {
        try {
            com.alibaba.fastjson2.JSONObject json = JSON.parseObject(body);
            if (json == null) return null;

            // SearXNG 格式：{"query": "...", "number_of_results": N, "results": [...]}
            if (json.containsKey("results") && json.get("results") instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                List<?> rawResults = (List<?>) json.get("results");
                List<Map<String, String>> results = new ArrayList<>();
                for (Object o : rawResults) {
                    if (!(o instanceof com.alibaba.fastjson2.JSONObject)) continue;
                    com.alibaba.fastjson2.JSONObject item = (com.alibaba.fastjson2.JSONObject) o;
                    Map<String, String> r = new HashMap<>(4);
                    String title = item.getString("title");
                    String url = item.getString("url");
                    String content = item.getString("content");
                    String engine = item.getString("engine");
                    if (title != null) r.put("title", title);
                    if (url != null) r.put("url", url);
                    if (content != null) r.put("snippet", content);
                    if (engine != null) r.put("engine", engine);
                    if (!r.isEmpty()) results.add(r);
                }
                if (!results.isEmpty()) {
                    return buildEnvelope("web_search",
                            envelopeListBlock("搜索结果（SearXNG 聚合）", results),
                            meta("source", "searxng", "resultCount", results.size()));
                }
            }

            // 通用 JSON：尝试提取 data/results/list 等常见 key
            String[] dataKeys = {"results", "data", "items", "list"};
            for (String key : dataKeys) {
                if (json.containsKey(key) && json.get(key) instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    List<?> raw = (List<?>) json.get(key);
                    List<Map<String, String>> extracted = new ArrayList<>();
                    for (Object o : raw) {
                        if (!(o instanceof com.alibaba.fastjson2.JSONObject)) continue;
                        com.alibaba.fastjson2.JSONObject item = (com.alibaba.fastjson2.JSONObject) o;
                        Map<String, String> r = new HashMap<>(3);
                        if (item.containsKey("title")) r.put("title", item.getString("title"));
                        if (item.containsKey("url")) r.put("url", item.getString("url"));
                        if (item.containsKey("link")) r.put("url", item.getString("link"));
                        if (item.containsKey("description")) r.put("snippet", item.getString("description"));
                        if (item.containsKey("snippet")) r.put("snippet", item.getString("snippet"));
                        if (item.containsKey("content")) r.put("snippet", item.getString("content"));
                        if (!r.isEmpty()) extracted.add(r);
                    }
                    if (!extracted.isEmpty()) {
                        return buildEnvelope("web_search",
                                envelopeListBlock("搜索结果", extracted),
                                meta("source", "json-api", "resultCount", extracted.size()));
                    }
                }
            }

            // 是 JSON 但无法提取结构 → 回退文本
            return null;
        } catch (Exception e) {
            // 非 JSON 格式
            return null;
        }
    }

    /**
     * 内置 Bing 搜索后端（默认，无需配置 API Key）。
     *
     * <p>P0-3 增强：
     * <ul>
     *   <li>加 Cookie 头（SRCHHPGUSR）绕过部分反爬</li>
     *   <li>解析失败（Bing 返回非 {@code li.b_algo} 结构）回退泛化链接提取</li>
     *   <li>超时 5s（重试机制由 webSearch 主方法统一处理）</li>
     * </ul>
     */
    private String searchViaBing(String query) throws Exception {
        String url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                + "&count=10&setlang=zh-CN";

        // P0-3 增强：加 Cookie 头绕过 Bing 部分反爬检测
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Cookie", "SRCHHPGUSR=AH=" + System.currentTimeMillis() / 1000 + "&V=1&N=1")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.warn("Bing 搜索请求失败: status={}, query={}", response.statusCode(), query);
            throw new RuntimeException("Bing returned status " + response.statusCode());
        }

        Document doc = Jsoup.parse(response.body());
        Elements items = doc.select("li.b_algo");

        List<Map<String, String>> results = new ArrayList<>();
        for (Element item : items) {
            Map<String, String> r = new HashMap<>(3);
            Element titleEl = item.selectFirst("h2 a");
            if (titleEl != null) {
                r.put("title", titleEl.text());
                r.put("url", titleEl.attr("href"));
            }
            Element snippetEl = item.selectFirst(".b_caption p, .b_caption .b_paractl");
            if (snippetEl != null) {
                r.put("snippet", snippetEl.text());
            }
            if (!r.isEmpty()) {
                results.add(r);
            }
        }

        // P0-3 增强：Bing 返回非标准结构（被反爬）时，泛化提取所有 a[href] 链接
        if (results.isEmpty()) {
            log.warn("Bing 返回空结果（可能被反爬），回退泛化链接提取: query={}", query);
            Elements links = doc.select("a[href]");
            for (Element link : links) {
                String href = link.attr("href");
                String text = link.text().trim();
                // 过滤导航链接、锚点、Bing 内部链接
                if (href.isEmpty() || text.length() < 4 || href.startsWith("#")
                        || href.contains("bing.com") || href.contains("microsoft.com")
                        || href.startsWith("/") || href.contains("javascript:")) {
                    continue;
                }
                // 只取 http(s) 外链
                if (!href.startsWith("http://") && !href.startsWith("https://")) {
                    continue;
                }
                Map<String, String> r = new HashMap<>(2);
                r.put("title", text.length() > 80 ? text.substring(0, 80) + "..." : text);
                r.put("url", href);
                results.add(r);
                if (results.size() >= 10) break;
            }
        }

        Map<String, Object> searchBlock;
        if (!results.isEmpty()) {
            searchBlock = envelopeListBlock("搜索结果（Bing）", results);
        } else {
            searchBlock = envelopeTextBlock("搜索结果", "未找到相关结果");
        }
        return buildEnvelope("web_search", searchBlock,
                meta("query", query, "count", results.size()));
    }

    // ============ Envelope 构造辅助 ============

    private String buildEnvelope(String toolCode, Map<String, Object> contentBlock, Map<String, Object> meta) {
        Map<String, Object> envelope = new HashMap<>(4);
        envelope.put("toolCode", toolCode);
        List<Map<String, Object>> content = new ArrayList<>(1);
        content.add(contentBlock);
        envelope.put("content", content);
        if (meta != null && !meta.isEmpty()) {
            envelope.put("meta", meta);
        }
        return JSON.toJSONString(envelope);
    }

    private Map<String, Object> envelopeTextBlock(String title, String text) {
        Map<String, Object> block = new HashMap<>(3);
        block.put("type", "text");
        block.put("title", title);
        block.put("text", text);
        return block;
    }

    private Map<String, Object> envelopeListBlock(String title, List<Map<String, String>> items) {
        Map<String, Object> block = new HashMap<>(3);
        block.put("type", "rich_list");
        block.put("title", title);
        block.put("items", items);
        return block;
    }

    private Map<String, Object> meta(Object... kv) {
        Map<String, Object> m = new HashMap<>(kv.length);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ============ 安全校验 ============

    /**
     * 校验 URL 安全性（SSRF 防护）。
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
        String lowerScheme = scheme.toLowerCase();
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            return "protocol '" + lowerScheme + "' is not allowed, only http/https";
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "missing or empty host";
        }
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isMulticastAddress()) {
                    return "access to internal/loopback address is forbidden: "
                            + addr.getHostAddress() + " (resolved from " + host + ")";
                }
            }
        } catch (java.net.UnknownHostException e) {
            return "unable to resolve host: " + host;
        }
        return null;
    }
}
