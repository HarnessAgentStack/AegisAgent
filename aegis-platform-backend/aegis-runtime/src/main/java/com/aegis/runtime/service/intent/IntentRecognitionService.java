package com.aegis.runtime.service.intent;

import com.aegis.core.enums.intent.IntentType;

import java.util.List;

/**
 * 意图识别服务接口。
 *
 * <p>负责分析用户当前提问 + 对话历史，识别用户意图类型，供中间件层做差异化路由。</p>
 *
 * @author wang.zhen
 * @see IntentType
 */
public interface IntentRecognitionService {

    /**
     * 识别用户意图。
     *
     * @param tenantId       租户 ID
     * @param userQuery      用户当前提问
     * @param recentHistory  最近对话历史（按时间正序，可为 null 或空）
     * @return 意图识别结果（永不返回 null）
     */
    IntentResult recognize(Long tenantId, String userQuery, List<String> recentHistory);

    /**
     * 意图识别结果。
     *
     * @param intent      意图类型
     * @param confidence  置信度 0.0 ~ 1.0
     * @param needRag     是否需要 RAG 检索
     * @param needTools   是否需要工具调用
     * @param rawIntent   原始识别结果（rule / LLM 原始输出 / fallback 标记），用于日志
     */
    record IntentResult(IntentType intent, float confidence,
                        boolean needRag, boolean needTools, String rawIntent) {}
}
