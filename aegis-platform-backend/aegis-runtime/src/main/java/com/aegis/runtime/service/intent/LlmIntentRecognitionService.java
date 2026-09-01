package com.aegis.runtime.service.intent;

import com.aegis.core.enums.intent.IntentType;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.aegis.runtime.integration.model.LlmHttpClient;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 基于 LLM 的意图识别服务实现。
 *
 * <p>采用 "规则兜底 → LLM 分类" 的两级策略：</p>
 * <ol>
 *   <li>规则兜底：关键词快速匹配，高置信度（≥ 0.9）时直接返回，避免 LLM 调用开销</li>
 *   <li>LLM 分类：规则未命中或置信度不足时，使用 {@link LlmClientFactory} 的 LIGHT 档模型
 *       做 JSON 结构化分类，异常时降级为 TASK</li>
 * </ol>
 *
 * <h3>降级策略</h3>
 * <ul>
 *   <li>LlmHttpClient 不可用 → 降级为 TASK（needRag=true, needTools=true）</li>
 *   <li>JSON 解析失败 → 降级为 TASK</li>
 *   <li>IntentType 非法值 → 降级为 TASK</li>
 * </ul>
 *
 * @author wang.zhen
 * @see IntentRecognitionService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmIntentRecognitionService implements IntentRecognitionService {

    private final LlmClientFactory llmClientFactory;

    /** 意图识别 prompt（LIGHT 档模型） */
    private static final String SYSTEM_PROMPT = """
            你是一个意图分类助手。分析用户当前提问和对话历史，判断用户意图类型。
            可选意图：CHITCHAT(闲聊问候)、TASK(任务执行/需工具调用)、RAG_QUERY(查询知识库/文档)、SKILL_CREATE(创建/修改技能)、CLARIFICATION(用户意图模糊需追问)。
            返回严格 JSON 格式：{"intent": "...", "confidence": 0.0-1.0, "needRag": true/false, "needTools": true/false}。只输出 JSON 不要其他文字。
            """;

    /** 规则兜底置信度阈值：高于此值则跳过 LLM 调用 */
    private static final float RULE_CONFIDENCE_THRESHOLD = 0.9f;

    @Override
    public IntentResult recognize(Long tenantId, String userQuery, List<String> recentHistory) {
        // 1. 规则兜底（关键词命中），快速路径
        IntentResult ruleResult = ruleClassify(userQuery);
        if (ruleResult != null && ruleResult.confidence() >= RULE_CONFIDENCE_THRESHOLD) {
            log.debug("意图识别（规则命中）: query={}, intent={}, confidence={}",
                    userQuery, ruleResult.intent(), ruleResult.confidence());
            return ruleResult;
        }

        // 2. LLM 分类（LIGHT 档）
        try {
            LlmHttpClient client = llmClientFactory.create(tenantId, ModelTier.LIGHT);
            String userPrompt = buildUserPrompt(userQuery, recentHistory);
            String result = client.chat(SYSTEM_PROMPT, userPrompt, 0.1f, 256, 5);

            // 解析 JSON（去除可能的 ```json 包裹）
            String jsonText = result.trim();
            if (jsonText.startsWith("```")) {
                int start = jsonText.indexOf('{');
                int end = jsonText.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    jsonText = jsonText.substring(start, end + 1);
                }
            }
            JSONObject json = JSON.parseObject(jsonText);
            IntentType intent = IntentType.valueOf(json.getString("intent"));
            float confidence = json.getFloatValue("confidence");
            boolean needRag = json.getBooleanValue("needRag");
            boolean needTools = json.getBooleanValue("needTools");

            log.debug("意图识别（LLM）: query={}, intent={}, confidence={}, needRag={}, needTools={}",
                    userQuery, intent, confidence, needRag, needTools);
            return new IntentResult(intent, confidence, needRag, needTools, result);
        } catch (IllegalArgumentException e) {
            // IntentType 枚举值非法，降级
            log.warn("意图识别 LLM 返回非法 intent 值，降级为 TASK: query={}, error={}, raw={}",
                    userQuery, e.getMessage(), e.getMessage());
            return fallback(userQuery, "fallback-invalid-intent");
        } catch (Exception e) {
            log.warn("意图识别 LLM 失败，降级为 TASK: query={}, error={}", userQuery, e.getMessage());
            return fallback(userQuery, "fallback");
        }
    }

    /**
     * 降级返回 TASK 意图（needRag + needTools 都开，尽量不漏处理）。
     */
    private IntentResult fallback(String query, String reason) {
        return new IntentResult(IntentType.TASK, 0.5f, true, true, reason);
    }

    /**
     * 规则兜底：关键词匹配。
     *
     * <p>仅对高置信度场景（闲聊 / 技能创建 / 明显知识查询）生效；
     * 其余场景返回 null，交由 LLM 分类。</p>
     */
    private IntentResult ruleClassify(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String q = query.toLowerCase().trim();

        // 闲聊问候（精确匹配开头，排除 "你好帮我..." 这种前缀+任务场景）
        if (q.matches("^(你好|hi|hello|嗨|早[上好]|晚安|谢谢|再见|拜拜)[^\\u4e00-\\u9fa5a-zA-Z0-9]*$")) {
            return new IntentResult(IntentType.CHITCHAT, 0.95f, false, false, "rule");
        }

        // 技能创建（强关键词，置信度 0.9 刚好等于阈值会跳过 LLM）
        if (q.contains("创建技能") || q.contains("写技能") || q.contains("做一个技能")
                || q.contains("调试技能") || q.contains("改一下技能")) {
            return new IntentResult(IntentType.SKILL_CREATE, 0.9f, false, true, "rule");
        }

        // 明显知识查询（中等置信度 0.7，会进入 LLM 二次确认）
        if (q.matches("(什么是|是什么|介绍下|介绍一下|.*是什么意思).*(公司|产品|政策|方案|规范|流程|系统|平台|服务)")
                || q.contains("文档") || q.contains("手册")) {
            return new IntentResult(IntentType.RAG_QUERY, 0.7f, true, false, "rule");
        }

        return null;
    }

    /**
     * 构建 LLM 分类的 user prompt：最近 5 轮历史 + 当前提问。
     */
    private String buildUserPrompt(String userQuery, List<String> recentHistory) {
        StringBuilder sb = new StringBuilder();
        if (recentHistory != null && !recentHistory.isEmpty()) {
            int historySize = Math.min(recentHistory.size(), 5);
            sb.append("对话历史（最近").append(historySize).append("轮）：\n");
            int start = Math.max(0, recentHistory.size() - historySize);
            for (int i = start; i < recentHistory.size(); i++) {
                sb.append(i - start + 1).append(". ").append(recentHistory.get(i)).append('\n');
            }
        }
        sb.append("\n当前提问：").append(userQuery);
        return sb.toString();
    }
}
