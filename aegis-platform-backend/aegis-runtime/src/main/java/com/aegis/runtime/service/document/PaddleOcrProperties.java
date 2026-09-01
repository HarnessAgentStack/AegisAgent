package com.aegis.runtime.service.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PaddleOCR 服务连接配置。
 *
 * <p>通过 {@code aegis.ocr.paddle} 前缀绑定 application.yml 配置。
 * 所有字段都有安全默认值，即使 yml 中未显式配置也可工作。
 *
 * @author wang.zhen
 */
@Data
@ConfigurationProperties(prefix = "aegis.ocr.paddle")
public class PaddleOcrProperties {

    /** 是否启用 PaddleOCR（设为 false 则跳过 OCR 直接走 vision LLM） */
    private boolean enabled = true;

    /** PaddleOCR HTTP 服务地址（FastAPI /ocr 端点） */
    private String endpoint = "http://localhost:8098";

    /** HTTP 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** HTTP 读取超时（毫秒）—— OCR 推理可能耗时较长，建议 ≥ 15s */
    private int readTimeoutMs = 30000;

    /** 单次请求图片最大字节数（默认 10MB，防止超大图撑爆 OCR 服务内存） */
    private int maxImageBytes = 10 * 1024 * 1024;

    /** OCR 返回的 full_text 字符数阈值（≥ 此值视为 OCR 成功提取到有效文字） */
    private int minTextLengthThreshold = 3;

    /** OCR 成功时的标签（用于日志） */
    private String ocrSuccessTag = "[OCR]";

    /** OCR 降级时的标签（用于日志） */
    private String ocrFallbackTag = "[VISION_FALLBACK]";
}
