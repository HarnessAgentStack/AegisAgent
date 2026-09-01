package com.aegis.runtime.service.document;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * PaddleOCR HTTP 客户端。
 *
 * <p>封装与 PaddleOCR FastAPI 服务的通信，提供同步 OCR 识别能力。
 * 所有异常（网络、HTTP 5xx、超时、JSON 解析失败）都会被 catch 并返回
 * {@link OcrResult#failed(String)} 降级结果，调用方无需关心底层错误。</p>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>服务不可达 → OcrResult.failed()</li>
 *   <li>图片超 maxImageBytes → OcrResult.failed()</li>
 *   <li>HTTP 非 2xx → OcrResult.failed()</li>
 *   <li>响应体解析失败 → OcrResult.failed()</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
public class PaddleOcrClient {

    private final RestTemplate restTemplate;
    private final PaddleOcrProperties properties;

    public PaddleOcrClient(RestTemplate restTemplate, PaddleOcrProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 执行 OCR 识别。
     *
     * @param imageBytes 原始图片字节
     * @param filename   文件名（仅用于日志）
     * @return OCR 结果（永不返回 null，失败时返回 OcrResult.failed()）
     */
    public OcrResult recognize(byte[] imageBytes, String filename) {
        if (!properties.isEnabled()) {
            log.debug("PaddleOCR 已禁用，跳过: filename={}", filename);
            return OcrResult.failed("OCR disabled");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return OcrResult.failed("empty image");
        }
        if (imageBytes.length > properties.getMaxImageBytes()) {
            log.warn("图片超过 OCR 服务上限，跳过: filename={}, sizeKB={}, limitKB={}",
                    filename, imageBytes.length / 1024, properties.getMaxImageBytes() / 1024);
            return OcrResult.failed("image too large");
        }

        // 1. Base64 编码
        String base64;
        try {
            base64 = Base64.getEncoder().encodeToString(imageBytes);
        } catch (Exception e) {
            log.warn("Base64 编码失败: filename={}, error={}", filename, e.getMessage());
            return OcrResult.failed("base64 encode error: " + e.getMessage());
        }

        // 2. 构造请求体
        JSONObject body = new JSONObject();
        body.put("image_base64", base64);
        body.put("detect_table", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity = new HttpEntity<>(body.toJSONString(), headers);

        // 3. 发起 HTTP 调用
        String url = properties.getEndpoint().replaceAll("/+$", "") + "/ocr";
        long start = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            long elapsed = System.currentTimeMillis() - start;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseResponse(response.getBody(), filename, elapsed);
            } else {
                String bodySample = response.getBody() != null ? response.getBody().substring(0, Math.min(500, response.getBody().length())) : "(empty)";
                log.warn("PaddleOCR HTTP 非 2xx: filename={}, status={}, body={}, elapsed={}ms",
                        filename, response.getStatusCode(), bodySample, elapsed);
                return OcrResult.failed("HTTP " + response.getStatusCode().value());
            }
        } catch (ResourceAccessException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("PaddleOCR 连接超时或服务不可达: filename={}, url={}, error={}, elapsed={}ms",
                    filename, url, e.getMessage(), elapsed);
            return OcrResult.failed("connection timeout/unreachable: " + e.getMessage());
        } catch (RestClientException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("PaddleOCR 调用异常: filename={}, url={}, error={}, elapsed={}ms",
                    filename, url, e.getMessage(), elapsed);
            return OcrResult.failed("rest error: " + e.getMessage());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("PaddleOCR 未知异常: filename={}, error={}, elapsed={}ms",
                    filename, e.getMessage(), elapsed, e);
            return OcrResult.failed("unknown error: " + e.getMessage());
        }
    }

    /**
     * 检查 OCR 服务健康状态。
     *
     * @return true 表示 /health 返回 200
     */
    public boolean healthCheck() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    properties.getEndpoint().replaceAll("/+$", "") + "/health",
                    String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("PaddleOCR health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 OCR JSON 响应体。
     */
    private OcrResult parseResponse(String json, String filename, long elapsedMs) {
        try {
            JSONObject root = JSON.parseObject(json);
            boolean success = root.getBooleanValue("success");
            String fullText = root.getString("full_text");
            int lineCount = 0;
            JSONArray linesArr = root.getJSONArray("text_lines");
            if (linesArr != null) {
                lineCount = linesArr.size();
            }

            if (!success) {
                log.warn("PaddleOCR 返回 success=false: filename={}, fullText={}, elapsed={}ms",
                        filename, fullText != null ? fullText.substring(0, Math.min(200, fullText.length())) : "", elapsedMs);
                return OcrResult.failed(fullText != null ? fullText : "OCR success=false");
            }

            log.info("PaddleOCR 识别成功: filename={}, lines={}, chars={}, elapsed={}ms",
                    filename, lineCount, fullText != null ? fullText.length() : 0, elapsedMs);
            return OcrResult.success(fullText != null ? fullText : "", lineCount, elapsedMs);
        } catch (Exception e) {
            log.warn("PaddleOCR 响应解析失败: filename={}, bodyLen={}, error={}",
                    filename, json.length(), e.getMessage());
            return OcrResult.failed("parse error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // OcrResult —— OCR 结果 DTO
    // -----------------------------------------------------------------------

    /**
     * OCR 识别结果。
     *
     * <p>通过 {@link #success(String, int, long)} 或 {@link #failed(String)} 创建。
     */
    public static class OcrResult {
        private final boolean success;
        private final String fullText;
        private final int lineCount;
        private final long elapsedMs;
        private final String error;

        private OcrResult(boolean success, String fullText, int lineCount, long elapsedMs, String error) {
            this.success = success;
            this.fullText = fullText;
            this.lineCount = lineCount;
            this.elapsedMs = elapsedMs;
            this.error = error;
        }

        public static OcrResult success(String fullText, int lineCount, long elapsedMs) {
            return new OcrResult(true, fullText, lineCount, elapsedMs, null);
        }

        public static OcrResult failed(String error) {
            return new OcrResult(false, "", 0, 0, error);
        }

        /** OCR 服务是否成功返回（不含"成功但识别结果为空"的语义） */
        public boolean isSuccess() { return success; }

        /** 成功提取到的文字（换行拼接），失败时为空串 */
        public String getFullText() { return fullText != null ? fullText : ""; }

        /** 识别行数，失败时为 0 */
        public int getLineCount() { return lineCount; }

        /** 耗时毫秒，失败时为 0 */
        public long getElapsedMs() { return elapsedMs; }

        /** 失败原因，成功时为 null */
        public String getError() { return error; }

        /**
         * OCR 是否有效（成功且提取到足够的文字）。
         *
         * <p>用于判断是否应该跳过后续的 vision LLM 调用。
         * 判定标准：fullText 非空且字符数 ≥ minTextLengthThreshold。</p>
         *
         * @param minChars 最少有效字符阈值
         */
        public boolean hasValidText(int minChars) {
            return success && fullText != null && fullText.trim().length() >= minChars;
        }
    }
}
