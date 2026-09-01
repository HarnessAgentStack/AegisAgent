package com.aegis.runtime.service.intent;

import com.aegis.core.enums.intent.IntentType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Prompt 模板引擎（任务 10）：按意图路由差异化系统提示词片段。
 *
 * <p>提供 3 套硬编码模板（{@link TemplateName#CHITCHAT} / {@link TemplateName#TASK}
 * / {@link TemplateName#RAG_QUERY}），由 {@link com.aegis.runtime.integration.middleware.AegisIntentMiddleware}
 * 在 {@code onSystemPrompt} 中按识别到的 {@link IntentType} 选用并追加。
 *
 * <h3>设计约束（对齐实施计划"不做"项）</h3>
 * <ul>
 *   <li>不引入第三方模板引擎；变量替换用简单 {@code {{key}}} 占位符替换</li>
 *   <li>不做模板版本管理、Few-Shot 动态注入、中间件接口改造</li>
 * </ul>
 *
 * <h3>模板关键差异</h3>
 * <ul>
 *   <li><b>CHITCHAT</b>：仅注入闲聊语气约束，<b>不含</b>"知识库"/"工具调用"等指令词
 *       （满足验收：CHITCHAT prompt 不含知识库/工具调用）</li>
 *   <li><b>TASK</b>：默认完整指令，不额外追加（返回空串，保留智能体基础 prompt）</li>
 *   <li><b>RAG_QUERY</b>：强制知识库引用约束（引导模型优先依据检索结果作答）</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Component
public class PromptTemplateEngine {

    /** 模板名 */
    public enum TemplateName {
        /** 闲聊模式：简洁友好，不含 RAG/工具指令 */
        CHITCHAT,
        /** 任务模式：默认完整指令（不追加额外片段） */
        TASK,
        /** 强制知识库模式：优先依据检索结果作答 */
        RAG_QUERY
    }

    /** CHITCHAT 模板：刻意回避"知识库"/"工具调用"字样，满足验收 #1 */
    private static final String CHITCHAT_TEMPLATE =
            "\n\n【闲聊模式】请以简洁、自然、友好的语气回复用户，直接给出回答，无需展开冗长说明。\n";

    /** TASK 模板：默认不追加（智能体基础 prompt 已是完整任务指令） */
    private static final String TASK_TEMPLATE = "";

    /** RAG_QUERY 模板：强制知识库引用约束 */
    private static final String RAG_QUERY_TEMPLATE =
            "\n\n【强制知识库模式】当前用户在查询知识库内容，"
                    + "请优先参考【知识库检索结果】中的内容回答；"
                    + "若检索结果为空或与问题无关，请明确告知用户未在知识库中找到相关内容。\n";

    /**
     * 按意图选择模板名。
     *
     * <p>CLARIFICATION 不会到达 {@code onSystemPrompt}（已在 {@code onAgent} 返回），
     * SKILL_CREATE 由 {@link com.aegis.runtime.integration.middleware.AegisIntentMiddleware}
     * 单独注入专属片段，均映射到 {@link TemplateName#TASK}（不追加模板片段）。
     */
    public TemplateName selectByIntent(IntentType intent) {
        if (intent == null) {
            return TemplateName.TASK;
        }
        return switch (intent) {
            case CHITCHAT -> TemplateName.CHITCHAT;
            case RAG_QUERY -> TemplateName.RAG_QUERY;
            default -> TemplateName.TASK;
        };
    }

    /**
     * 构建模板片段。
     *
     * @param name     模板名
     * @param variables 变量映射（支持 {@code {{key}}} 占位符替换；可为 null/空）
     * @return 追加到系统提示词末尾的片段（TASK 返回空串）
     */
    public String build(TemplateName name, Map<String, String> variables) {
        String template = switch (name) {
            case CHITCHAT -> CHITCHAT_TEMPLATE;
            case RAG_QUERY -> RAG_QUERY_TEMPLATE;
            case TASK -> TASK_TEMPLATE;
        };
        if (variables == null || variables.isEmpty() || template.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return result;
    }

    /** 便捷重载：无变量 */
    public String build(TemplateName name) {
        return build(name, null);
    }

    /** 幂等标记：用于 onSystemPrompt 判重，避免重复追加 */
    public String idempotencyMarker(TemplateName name) {
        return switch (name) {
            case CHITCHAT -> "【闲聊模式】";
            case RAG_QUERY -> "【强制知识库模式】";
            case TASK -> "";
        };
    }
}
