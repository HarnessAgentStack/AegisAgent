package com.aegis.runtime.integration.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 轻量 LLM HTTP 客户端。
 *
 * <p>封装 OpenAI Chat Completions 协议的同步 HTTP 调用，供查询改写、意图识别、
 * 摘要生成、标题生成、视觉描述等辅助 LLM 任务使用。采用 Spring WebClient 发起
 * 阻塞式（block()）请求，避免引入独立 HTTP 客户端依赖。</p>
 *
 * <h3>Endpoint 兼容</h3>
 * <p>构造函数接收的 endpoint 可以是 {@code https://ark.cn-beijing.volces.com/api/v3}
 * （不含路径），也可以是 {@code .../chat/completions}（完整路径）。客户端内部会
 * 归一化处理：若已以 {@code /chat/completions} 结尾则直接使用，否则自动拼接。</p>
 *
 * <h3>异常策略</h3>
 * <p>HTTP 非 2xx 响应、JSON 解析失败、调用超时均抛出 {@link RuntimeException}
 * 子类，由调用方按业务场景降级（返回原始 query / 返回空列表 / 返回原顺序）。</p>
 *
 * @author wang.zhen
 */
@Slf4j
public class LlmHttpClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final String endpoint;
    private final String apiKey;
    private final String modelName;
    private final WebClient webClient;

    /**
     * 构造轻量 LLM 客户端。
     *
     * @param endpoint  模型服务 baseUrl，如 {@code https://ark.cn-beijing.volces.com/api/v3}；
     *                  可带或不带 {@code /chat/completions} 后缀
     * @param apiKey    API 密钥
     * @param modelName 模型编码，如 {@code doubao-seed-2.0-lite}
     */
    public LlmHttpClient(String endpoint, String apiKey, String modelName) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.webClient = WebClient.builder()
                .baseUrl(this.endpoint)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    /**
     * 同步调用 Chat Completions，返回 assistant 文本。
     *
     * @param systemPrompt  系统提示词（可为 null）
     * @param userPrompt    用户提示词
     * @param temperature   采样温度，0.0 ~ 2.0
     * @param maxTokens     最大生成 token 数
     * @param timeoutSeconds 超时秒数
     * @return LLM 原始文本回复
     * @throws LlmHttpException HTTP 非 2xx 或解析失败
     * @throws RuntimeException  底层调用异常（如连接超时）
     */
    public String chat(String systemPrompt, String userPrompt, float temperature,
                       int maxTokens, int timeoutSeconds) {
        JSONObject requestBody = buildRequestBody(systemPrompt, userPrompt, temperature, maxTokens);
        String bodyJson = JSON.toJSONString(requestBody);
        log.debug("LlmHttpClient chat: endpoint={}, model={}, bodyLen={}",
                endpoint, modelName, bodyJson.length());

        try {
            String responseBody = webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(bodyJson)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(errBody -> Mono.error(new LlmHttpException(
                                            clientResponse.statusCode().value(),
                                            "LLM HTTP error: " + clientResponse.statusCode()
                                                    + ", body=" + truncate(errBody, 500)))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                throw new LlmHttpException(200, "LLM 返回空响应体");
            }

            return extractAssistantText(responseBody);
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmHttpException(0, "LLM 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 归一化 endpoint：确保以 /chat/completions 结尾。
     */
    private static String normalizeEndpoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return CHAT_COMPLETIONS_PATH;
        }
        String trimmed = raw.trim();
        if (trimmed.endsWith(CHAT_COMPLETIONS_PATH)) {
            return trimmed;
        }
        // 去掉末尾的 / 再拼接
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + CHAT_COMPLETIONS_PATH;
    }

    /**
     * 构建 Chat Completions 请求体。
     */
    private JSONObject buildRequestBody(String systemPrompt, String userPrompt,
                                        float temperature, int maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", userPrompt != null ? userPrompt : "");
        messages.add(usr);
        body.put("messages", messages);
        return body;
    }

    /**
     * 获取当前客户端绑定的模型编码。
     *
     * @return 模型编码，如 {@code doubao-seed-2.0-lite}
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 以原始 JSON body 同步调用 Chat Completions，返回原始响应体字符串。
     *
     * <p>本方法直接透传调用方构造好的 JSON body，不做字段级封装，
     * 供多模态等复杂消息结构（含 image_url 等嵌套对象）使用。
     * 返回值是完整的 HTTP 响应体，由调用方自行解析 {@code choices[0].message.content}。
     *
     * @param jsonBody     完整 Chat Completions 请求体 JSON
     * @param timeoutSeconds 超时秒数
     * @return LLM 原始响应体 JSON 字符串
     * @throws LlmHttpException HTTP 非 2xx 或网络/解析层异常
     */
    public String postChatCompletionsBody(String jsonBody, int timeoutSeconds) {
        log.debug("LlmHttpClient postChatCompletionsBody: endpoint={}, model={}, bodyLen={}",
                endpoint, modelName, jsonBody != null ? jsonBody.length() : 0);

        try {
            String responseBody = webClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(jsonBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, clientResponse ->
                            clientResponse.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(errBody -> Mono.error(new LlmHttpException(
                                            clientResponse.statusCode().value(),
                                            "LLM HTTP error: " + clientResponse.statusCode()
                                                    + ", body=" + truncate(errBody, 500)))))
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            if (responseBody == null || responseBody.isBlank()) {
                throw new LlmHttpException(200, "LLM 返回空响应体");
            }
            return responseBody;
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmHttpException(0, "LLM 调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 以原始 JSON body 同步调用 Chat Completions，默认 60s 超时。
     *
     * @param jsonBody 完整 Chat Completions 请求体 JSON
     * @return LLM 原始响应体 JSON 字符串
     */
    public String postChatCompletionsBody(String jsonBody) {
        return postChatCompletionsBody(jsonBody, 60);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /**
     * 从 Chat Completions JSON 响应中提取 assistant 文本。
     *
     * @param responseBody 原始响应体 JSON 字符串
     * @return assistant content 文本
     */
    public static String extractAssistantText(String responseBody) {
        try {
            JSONObject root = JSON.parseObject(responseBody);
            JSONArray choices = root.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmHttpException(200, "LLM 响应无 choices: " + truncate(responseBody, 300));
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) {
                throw new LlmHttpException(200, "LLM 响应无 message: " + truncate(responseBody, 300));
            }
            String content = message.getString("content");
            if (content == null) {
                throw new LlmHttpException(200, "LLM 响应无 content: " + truncate(responseBody, 300));
            }
            return content.trim();
        } catch (LlmHttpException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmHttpException(200, "LLM 响应 JSON 解析失败: " + truncate(responseBody, 300), e);
        }
    }

    /**
     * LLM HTTP 调用异常。携带 HTTP 状态码（0 表示网络/解析层异常）。
     *
     * @author wang.zhen
     */
    public static class LlmHttpException extends RuntimeException {
        private final int statusCode;

        public LlmHttpException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public LlmHttpException(int statusCode, String message, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
