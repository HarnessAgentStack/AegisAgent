package com.aegis.runtime.service.document;

import com.aegis.core.enums.model.ModelTier;
import com.aegis.runtime.infrastructure.document.ImageResizeUtil;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.aegis.runtime.integration.model.LlmHttpClient;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * 图片描述服务（OCR 优先 + Vision LLM 降级）。
 *
 * <h3>处理链路</h3>
 * <pre>
 *   ENGINE_PARSE 图片附件
 *     → 1. PaddleOCR 识别文字（扫描件/截图首选）
 *        ├─ 成功且文字有效 → 直接返回 OCR 结果 ✓
 *        └─ 失败/空结果     → 降级到 Step 2
 *     → 2. Vision LLM 生成图片描述（场景照片首选）
 *        ├─ 成功 → 返回描述 ✓
 *        └─ 失败 → 降级到 Step 3
 *     → 3. 元信息标注（永不阻塞）
 * </pre>
 *
 * <h3>降级策略（三层 fail-closed）</h3>
 * <ol>
 *   <li><b>OCR 层</b>：PaddleOCR 容器不可达、图片超 10MB、响应解析失败 → 静默降级到 vision LLM</li>
 *   <li><b>Vision LLM 层</b>：STRONG 档无模型 → 回退 LIGHT 档；LIGHT 也无 → 降级元信息</li>
 *   <li><b>元信息层</b>：永不向上抛出异常，返回 {@code [图片: xxx.png, 大小: 128KB]}</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class VisionDescriptionService {

    private final LlmClientFactory llmClientFactory;
    private final PaddleOcrProperties ocrProperties;

    /**
     * PaddleOCR 客户端（可选注入）。
     *
     * <p>当 {@code aegis.ocr.paddle.enabled=false} 时 bean 不会创建，
     * 此字段为 null，跳过 OCR 直接走 vision LLM 路径。</p>
     */
    @Autowired(required = false)
    private PaddleOcrClient ocrClient;

    /** 视觉描述系统提示词 */
    private static final String VISION_SYSTEM_PROMPT =
            "你是一个图片描述助手。请简洁描述这张图片的内容，包括：主要对象、场景、颜色、文字、图表/表格（如果有）等关键信息。";

    /** 视觉描述默认最大生成 token 数 */
    private static final int VISION_MAX_TOKENS = 512;

    /** 视觉描述默认超时秒数 */
    private static final int VISION_TIMEOUT_SECONDS = 60;

    public VisionDescriptionService(LlmClientFactory llmClientFactory, PaddleOcrProperties ocrProperties) {
        this.llmClientFactory = llmClientFactory;
        this.ocrProperties = ocrProperties;
    }

    /**
     * 生成图片描述（OCR 优先 + Vision LLM 降级）。
     *
     * @param imageBytes 图片字节数组
     * @param filename   文件名（用于推断格式/MIME + 日志）
     * @param tenantId   租户 ID
     * @return 图片描述文本；调用失败或模型不可用时返回元信息标注（永不返回 null）
     */
    public String describe(byte[] imageBytes, String filename, Long tenantId) {
        if (imageBytes == null || imageBytes.length == 0) {
            return fallbackAnnotation(filename, new byte[0]);
        }

        // =============================================================
        // Step 1: OCR 优先识别（扫描件/截图场景的最佳路径）
        // =============================================================
        if (ocrClient != null && ocrProperties.isEnabled()) {
            long ocrStart = System.currentTimeMillis();
            PaddleOcrClient.OcrResult ocrResult = ocrClient.recognize(imageBytes, filename);
            long ocrElapsed = System.currentTimeMillis() - ocrStart;

            if (ocrResult.hasValidText(ocrProperties.getMinTextLengthThreshold())) {
                // OCR 成功且有有效文字 → 直接返回
                log.info("{} OCR 优先命中: filename={}, chars={}, lines={}, elapsed={}ms",
                        ocrProperties.getOcrSuccessTag(), filename,
                        ocrResult.getFullText().length(),
                        ocrResult.getLineCount(), ocrElapsed);
                return ocrResult.getFullText();
            } else {
                // OCR 失败或无有效文字 → 降级 vision LLM
                log.info("{} OCR 未命中（{}），降级 vision LLM: filename={}, elapsed={}ms",
                        ocrProperties.getOcrFallbackTag(),
                        ocrResult.isSuccess()
                                ? "识别结果空/太少（" + ocrResult.getFullText().length() + " chars）"
                                : "服务异常（" + ocrResult.getError() + "）",
                        filename, ocrElapsed);
            }
        } else {
            log.debug("OCR 未启用或客户端未注入，直接走 vision LLM: filename={}, enabled={}, client={}",
                    filename, ocrProperties.isEnabled(), ocrClient != null);
        }

        // =============================================================
        // Step 2: Vision LLM 生成图片描述
        // =============================================================
        return describeViaVisionLlm(imageBytes, filename);
    }

    /**
     * 通过 Vision LLM 生成图片描述（OCR 降级路径）。
     *
     * @return 描述文本；所有异常均被 catch，最终返回元信息标注
     */
    private String describeViaVisionLlm(byte[] imageBytes, String filename) {
        // 1. 图片预处理（缩放）
        byte[] processed = ImageResizeUtil.resizeIfNeeded(imageBytes, filename);

        // 2. 构造 data URL
        String base64 = Base64.getEncoder().encodeToString(processed);
        String mimeType = ImageResizeUtil.guessMimeType(filename);
        String imageUrl = "data:" + mimeType + ";base64," + base64;

        // 3. 创建视觉模型客户端（STRONG → LIGHT 降级链）
        LlmHttpClient client;
        try {
            client = llmClientFactory.create(null, ModelTier.STRONG);
        } catch (Exception e) {
            log.warn("STRONG 档模型客户端创建失败，尝试 LIGHT 档: filename={}, error={}",
                    filename, e.getMessage());
            try {
                client = llmClientFactory.create(null, ModelTier.LIGHT);
            } catch (Exception e2) {
                log.warn("LIGHT 档模型客户端也创建失败，降级为元信息标注: filename={}", filename);
                return fallbackAnnotation(filename, imageBytes);
            }
        }

        // 4. 构造多模态 JSON 请求体
        String jsonBody = buildVisionBody(client.getModelName(), imageUrl);

        try {
            String rawResponse = client.postChatCompletionsBody(jsonBody, VISION_TIMEOUT_SECONDS);
            String description = LlmHttpClient.extractAssistantText(rawResponse);
            if (description != null && !description.isEmpty()) {
                log.info("Vision LLM 描述生成成功: filename={}, descLength={}", filename, description.length());
                return description;
            }
            log.warn("Vision LLM 返回空描述，降级为元信息标注: filename={}", filename);
            return fallbackAnnotation(filename, imageBytes);
        } catch (Exception e) {
            log.warn("Vision LLM 调用失败，降级为元信息标注: filename={}, error={}", filename, e.getMessage());
            return fallbackAnnotation(filename, imageBytes);
        }
    }

    /**
     * 构造多模态 Chat Completions JSON body。
     *
     * <p>使用 fastjson2 程序化构建，避免字符串拼接造成 JSON 转义问题。
     *
     * @param modelName 模型编码
     * @param imageUrl  data URL 格式的图片
     * @return JSON 字符串
     */
    private String buildVisionBody(String modelName, String imageUrl) {
        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("max_tokens", VISION_MAX_TOKENS);

        JSONArray messages = new JSONArray();

        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", VISION_SYSTEM_PROMPT);
        messages.add(sys);

        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        JSONArray userContent = new JSONArray();

        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        JSONObject imageUrlObj = new JSONObject();
        imageUrlObj.put("url", imageUrl);
        imagePart.put("image_url", imageUrlObj);
        userContent.add(imagePart);

        usr.put("content", userContent);
        messages.add(usr);

        body.put("messages", messages);
        return JSON.toJSONString(body);
    }

    /**
     * 降级返回：纯元信息标注文本。
     *
     * @param filename   文件名
     * @param imageBytes 原始图片字节
     * @return 如 {@code [图片: xxx.png, 大小: 128KB, 类型: image/png]}
     */
    private String fallbackAnnotation(String filename, byte[] imageBytes) {
        String name = filename != null ? filename : "(unknown)";
        int sizeKB = imageBytes != null ? Math.max(1, imageBytes.length / 1024) : 0;
        String mimeType = ImageResizeUtil.guessMimeType(filename);
        return "[图片: " + name + ", 大小: " + sizeKB + "KB, 类型: " + mimeType + "]";
    }
}
