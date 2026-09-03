package com.aegis.runtime.service.document;

/**
 * OCR 识别结果 DTO（共享模型层）。
 *
 * <p>OnnxOcrClient / PaddleOcrClient 共用此结果类型，
 * 消费方（如 {@link VisionDescriptionService}）不关心底层实现。</p>
 *
 * @author wang.zhen
 */
public class OcrResult {

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

    /** OCR 引擎是否成功返回（不含"成功但识别结果为空"的语义） */
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
