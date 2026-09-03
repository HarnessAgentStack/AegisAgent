package com.aegis.runtime.service.document;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

/**
 * PaddleOCR HTTP 客户端（已降级为 fallback，优先使用 {@link OnnxOcrClient}）。
 *
 * <p>封装与 PaddleOCR FastAPI 服务的通信。所有异常被 catch 并返回
 * {@link OcrResult#failed(String)} 降级结果。</p>
 *
 * @deprecated 请优先使用 {@link OnnxOcrClient}（ONNX Runtime Java 进程内推理，零外部服务依赖）。
 *             本类保留用于向后兼容及 ONNX 模型不可用时的 fallback。
 */
@Slf4j
@Deprecated
public class PaddleOcrClient {

    private final RestTemplate restTemplate;
    private final PaddleOcrProperties properties;

    public PaddleOcrClient(RestTemplate restTemplate, PaddleOcrProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /**
     * 执行 OCR 识别（HTTP 调用 PaddleOCR FastAPI 服务）。
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
                log.warn("PaddleOCR HTTP 非 2xx: filename={}, status={}, elapsed={}ms",
                        filename, response.getStatusCode(), elapsed);
                return OcrResult.failed("HTTP " + response.getStatusCode().value());
            }
        } catch (ResourceAccessException e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("PaddleOCR 连接超时或不可达: filename={}, url={}, elapsed={}ms",
                    filename, url, elapsed);
            return OcrResult.failed("connection timeout: " + e.getMessage());
        } catch (RestClientException e) {
            return OcrResult.failed("rest error: " + e.getMessage());
        } catch (Exception e) {
            log.warn("PaddleOCR 未知异常: filename={}, error={}", filename, e.getMessage(), e);
            return OcrResult.failed("unknown error: " + e.getMessage());
        }
    }

    public boolean healthCheck() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    properties.getEndpoint().replaceAll("/+$", "") + "/health", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.debug("PaddleOCR health check failed: {}", e.getMessage());
            return false;
        }
    }

    private OcrResult parseResponse(String json, String filename, long elapsedMs) {
        try {
            JSONObject root = JSON.parseObject(json);
            boolean success = root.getBooleanValue("success");
            String fullText = root.getString("full_text");
            int lineCount = 0;
            JSONArray linesArr = root.getJSONArray("text_lines");
            if (linesArr != null) lineCount = linesArr.size();

            if (!success) {
                log.warn("PaddleOCR success=false: filename={}", filename);
                return OcrResult.failed(fullText != null ? fullText : "OCR success=false");
            }
            log.info("PaddleOCR[fallback] 识别成功: filename={}, lines={}, elapsed={}ms",
                    filename, lineCount, elapsedMs);
            return OcrResult.success(fullText != null ? fullText : "", lineCount, elapsedMs);
        } catch (Exception e) {
            log.warn("PaddleOCR 响应解析失败: filename={}, error={}", filename, e.getMessage());
            return OcrResult.failed("parse error: " + e.getMessage());
        }
    }
}
