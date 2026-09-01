package com.aegis.core.infrastructure.embedding;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * ARK 嵌入模型客户端（无 Spring 依赖，可在任意模块使用）。
 *
 * <p>通过 ARK OpenAI 兼容协议调用 doubao-embedding-vision 模型，
 * 支持批量文本向量化。使用 JDK 11+ HttpClient，不依赖 WebClient。
 */
@Slf4j
public class ArkEmbeddingClient {

    private static final int MAX_BATCH_SIZE = 8;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 2000;

    private final String endpoint;
    private final String apiKey;
    private final String modelCode;
    private final HttpClient httpClient;
    private volatile int cachedDimension = 0;

    public ArkEmbeddingClient(String endpoint, String apiKey, String modelCode) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.apiKey = apiKey;
        this.modelCode = modelCode;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text))[0];
    }

    public float[][] embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }

        List<float[]> results = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            float[][] batchResults = callEmbeddingApi(batch);
            results.addAll(Arrays.asList(batchResults));
        }

        return results.toArray(new float[0][]);
    }

    public int getDimension() {
        if (cachedDimension > 0) {
            return cachedDimension;
        }
        float[] vec = embed("dimension probe");
        return cachedDimension;
    }

    private float[][] callEmbeddingApi(List<String> texts) {
        Map<String, Object> requestBody = Map.of(
                "model", modelCode,
                "input", texts,
                "encoding_format", "float"
        );

        int lastStatus = 0;
        String lastBody = "";

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint + "/embeddings"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    return parseEmbeddingResponse(response.body());
                }

                lastStatus = statusCode;
                lastBody = response.body();

                if (statusCode == 429 || statusCode == 503) {
                    if (attempt < MAX_RETRIES - 1) {
                        long delay = RETRY_DELAY_MS * (long) Math.pow(2, attempt);
                        log.warn("ARK Embedding API 限流 (status={}), 等待 {}ms 后重试 ({}/{})",
                                statusCode, delay, attempt + 1, MAX_RETRIES);
                        Thread.sleep(delay);
                        continue;
                    }
                }

                log.error("ARK Embedding API 调用失败: status={}, body={}", statusCode, response.body());
                throw new RuntimeException("嵌入模型调用失败: HTTP " + statusCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("嵌入模型调用被中断", e);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                log.error("ARK Embedding API 异常: {}", e.getMessage(), e);
                throw new RuntimeException("嵌入模型调用异常: " + e.getMessage(), e);
            }
        }

        log.error("ARK Embedding API 重试耗尽: lastStatus={}, lastBody={}", lastStatus, lastBody);
        throw new RuntimeException("嵌入模型调用失败，重试 " + MAX_RETRIES + " 次后仍失败: HTTP " + lastStatus);
    }

    private float[][] parseEmbeddingResponse(String json) {
        JSONObject root = JSON.parseObject(json);
        JSONArray data = root.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("嵌入响应为空: " + json);
        }

        data.sort(Comparator.comparingInt(o -> ((JSONObject) o).getIntValue("index")));

        float[][] result = new float[data.size()][];
        for (int i = 0; i < data.size(); i++) {
            JSONArray embedding = data.getJSONObject(i).getJSONArray("embedding");
            result[i] = new float[embedding.size()];
            for (int j = 0; j < embedding.size(); j++) {
                result[i][j] = embedding.getFloatValue(j);
            }
        }

        if (cachedDimension == 0 && result.length > 0) {
            cachedDimension = result[0].length;
            log.info("嵌入模型维度检测: model={}, dimension={}", modelCode, "(cached)");
        }

        return result;
    }
}
