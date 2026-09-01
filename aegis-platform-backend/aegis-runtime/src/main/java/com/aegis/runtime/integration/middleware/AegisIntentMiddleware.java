package com.aegis.runtime.integration.middleware;

import com.aegis.core.enums.intent.IntentType;
import com.aegis.core.enums.model.ModelTier;
import com.aegis.runtime.integration.model.LlmClientFactory;
import com.aegis.runtime.integration.model.LlmHttpClient;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.intent.IntentRecognitionService;
import com.aegis.runtime.service.intent.IntentRecognitionService.IntentResult;
import com.aegis.runtime.service.intent.PromptTemplateEngine;
import com.aegis.runtime.service.sandbox.SandboxReadinessGate;
import com.aegis.runtime.service.sandbox.SandboxReadinessRequest;
import com.aegis.runtime.service.sandbox.SlotKeyParser;
import com.aegis.runtime.integration.skill.AegisSkillRepository;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 意图识别中间件：在 Agent 执行前识别用户意图，驱动差异化路由。
 *
 * <p>作为 AgentScope {@link OrderedMiddleware} 的实现，在
 * {@code onAgent} 钩子中：</p>
 * <ol>
 *   <li>提取 userQuery + 最近 5 轮 user 历史消息</li>
 *   <li>从 {@link AegisTaskContext} 获取 tenantId</li>
 *   <li>调用 {@link IntentRecognitionService#recognize} 识别意图</li>
 *   <li>写入 {@code RuntimeContext} 的 {@code aegis.intent} 键供下游中间件读取</li>
 *   <li>CLARIFICATION：直接返回澄清问题事件，不进入主执行链路</li>
 * </ol>
 *
 * <h3>下游路由效果</h3>
 * <ul>
 *   <li>CHITCHAT → {@link AegisRagMiddleware} 跳过 RAG；onSystemPrompt 简化 prompt</li>
 *   <li>RAG_QUERY → {@link AegisRagMiddleware} 强制检索；onSystemPrompt 注入 RAG 约束</li>
 *   <li>TASK → 正常执行；按需 RAG + 工具</li>
 *   <li>SKILL_CREATE → 写入标记，供工具加载层路由到 SkillCreator</li>
 *   <li>CLARIFICATION → 生成澄清问题返回，不进主执行</li>
 * </ul>
 *
 * <h3>执行顺序</h3>
 * <p>order=67，位于：
 * <ul>
 *   <li>租户隔离(80) 之后 — 确保 tenantId 已注入</li>
 *   <li>RAG 检索(65) 之前 — RAG 中间件依赖本中间件设置的意图标记</li>
 * </ul>
 *
 * <h3>CLARIFICATION 澄清问题生成</h3>
 * <p>CLARIFICATION 意图下，使用 LIGHT 档 LLM 生成 1~2 个追问方向，
 * 包装为 {@code intent.clarification} 事件直接返回给前端，不调用 next.apply()。</p>
 *
 * @author wang.zhen
 * @see IntentRecognitionService
 * @see AegisRagMiddleware
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisIntentMiddleware implements MiddlewareBase, OrderedMiddleware {

    /** RuntimeContext 中存储意图识别结果的 key */
    public static final String CTX_KEY_INTENT = "aegis.intent";

    /** CLARIFICATION 澄清问题生成 prompt */
    private static final String CLARIFICATION_SYSTEM =
            "你是一个追问助手。用户当前提问意图模糊，请根据用户问题生成 1~2 个澄清问题，帮助明确用户真正想做什么。"
                    + "每个澄清问题独立成行，以纯文本输出，不要编号、不要解释、不要 markdown 格式。";

    /** SKILL_CREATE 路由标记 key */
    public static final String CTX_KEY_SKILL_CREATE = "aegis.skillCreate";

    private final IntentRecognitionService intentService;

    /** LLM 客户端工厂（用于 CLARIFICATION 澄清问题生成） */
    private final LlmClientFactory llmClientFactory;

    /** Prompt 模板引擎（任务 10：按意图路由差异化 prompt 片段） */
    private final PromptTemplateEngine promptTemplateEngine;

    /** T1：沙箱就绪门控（TASK/SKILL_CREATE 意图触发异步预取，藏分配时延到首 Token 之间） */
    private final SandboxReadinessGate sandboxReadinessGate;

    @Override
    public int order() {
        // AgentScope 降序执行，67 介于租户隔离(80) 与 RAG(65) 之间
        return 67;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {

        // 1. 提取 userQuery + recentHistory（最近 5 条 USER 消息）
        String userQuery = extractUserQuery(input);
        List<String> recentHistory = extractRecentUserHistory(input);

        // 无查询：直接透传
        if (userQuery == null || userQuery.isBlank()) {
            log.debug("无用户查询文本，跳过意图识别");
            return next.apply(input);
        }

        // 2. 获取 tenantId（优先从 AegisTaskContext）
        Long tenantId = null;
        AegisTaskContext taskCtx = null;
        try {
            taskCtx = ctx.get(AegisTaskContext.class);
        } catch (Exception e) {
            log.debug("从 RuntimeContext 获取 AegisTaskContext 失败: {}", e.getMessage());
        }
        if (taskCtx != null) {
            tenantId = taskCtx.getTenantId();
        }
        if (tenantId == null) {
            tenantId = resolveTenantId(ctx);
        }

        // 3. 意图识别
        IntentResult intentResult = intentService.recognize(tenantId, userQuery, recentHistory);
        log.info("意图识别完成: query={}, intent={}, confidence={}, needRag={}, needTools={}",
                truncate(userQuery, 60), intentResult.intent(), intentResult.confidence(),
                intentResult.needRag(), intentResult.needTools());

        // 4. 写入 RuntimeContext，供下游中间件读取
        try {
            ctx.put(CTX_KEY_INTENT, intentResult);
        } catch (Exception e) {
            log.warn("写入 aegis.intent 到 RuntimeContext 失败: {}", e.getMessage());
        }

        // 5. SKILL_CREATE 路由标记（供工具加载层使用）
        if (intentResult.intent() == IntentType.SKILL_CREATE) {
            try {
                ctx.put(CTX_KEY_SKILL_CREATE, true);
            } catch (Exception ignored) { /* no-op */ }
        }

        // 6. CLARIFICATION：生成澄清问题返回，不进主执行
        if (intentResult.intent() == IntentType.CLARIFICATION) {
            return handleClarification(tenantId, userQuery, recentHistory);
        }

        // 7. T1 意图预取：TASK/SKILL_CREATE 异步预取沙箱（非阻塞），CHITCHAT/RAG_QUERY 不预取
        //    预取失败由 SandboxReadinessGate 自动降级同步兜底，对主链路透明（§4.3.2）
        if (taskCtx != null && (intentResult.intent() == IntentType.TASK
                || intentResult.intent() == IntentType.SKILL_CREATE)) {
            prefetchSandboxForIntent(taskCtx, ctx, tenantId);
        }

        // 8. 其他意图继续执行
        return next.apply(input);
    }

    /**
     * T1 意图预取：构建 SandboxReadinessRequest 并发起非阻塞预取。
     *
     * <p>从 AegisTaskContext 提取 sessionId/agentId/userId，从 RuntimeContext 读取
     * agentType（AegisSkillRepository.CTX_AGENT_TYPE），按 SlotKeyParser 构建与框架
     * 一致的 slotKey（UNIVERSAL→USER / 其他→AGENT），调用
     * {@link SandboxReadinessGate#prefetchAsync} 异步分配。异常隔离在 gate 内，
     * 本方法不向外抛。
     */
    private void prefetchSandboxForIntent(AegisTaskContext taskCtx, RuntimeContext ctx, Long tenantId) {
        try {
            String sessionId = taskCtx.getSessionId();
            Long agentId = taskCtx.getAgentId();
            Long userId = taskCtx.getUserId();
            if (sessionId == null || agentId == null || tenantId == null) {
                log.debug("prefetchSandboxForIntent 跳过(上下文不完整): sessionId={}, agentId={}, tenantId={}",
                        sessionId, agentId, tenantId);
                return;
            }
            String agentType = "UNIVERSAL";
            try {
                Object at = ctx.get(AegisSkillRepository.CTX_AGENT_TYPE);
                if (at instanceof String s && !s.isBlank()) {
                    agentType = s;
                }
            } catch (Exception ignored) { /* 默认 UNIVERSAL */ }
            io.agentscope.harness.agent.IsolationScope scope = switch (agentType) {
                case "UNIVERSAL" -> io.agentscope.harness.agent.IsolationScope.USER;
                case "SYSTEM" -> io.agentscope.harness.agent.IsolationScope.AGENT;
                default -> io.agentscope.harness.agent.IsolationScope.AGENT;
            };
            String slotKey = SlotKeyParser.build(scope, tenantId, userId, agentId);
            SandboxReadinessRequest req = SandboxReadinessRequest.of(
                    sessionId, slotKey, scope, tenantId, userId, agentId, agentType);
            sandboxReadinessGate.prefetchAsync(req);
            log.info("T1 意图预取已发起: sessionId={}, slotKey={}, agentType={}", sessionId, slotKey, agentType);
        } catch (Exception e) {
            log.warn("prefetchSandboxForIntent 发起异常(已隔离): {}", e.getMessage());
        }
    }

    /**
     * onSystemPrompt：根据意图注入差异化 prompt 片段（任务 10 模板路由）。
     *
     * <ul>
     *   <li>CHITCHAT → 追加闲聊模式约束（简洁友好），<b>不含</b>"知识库"/"工具调用"指令词</li>
     *   <li>RAG_QUERY → 追加强制知识库约束（优先参考检索结果）</li>
     *   <li>SKILL_CREATE → 追加技能创建引导片段</li>
     *   <li>TASK → 默认完整指令，不额外注入</li>
     *   <li>CLARIFICATION → 不会到这里（已在 onAgent 返回）</li>
     * </ul>
     *
     * <p>CHITCHAT / RAG_QUERY 片段由 {@link PromptTemplateEngine} 产出，幂等标记判重避免重复追加。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(prompt);
        }
        IntentType intent = readIntentType(ctx);
        if (intent == null) {
            return Mono.just(prompt);
        }

        switch (intent) {
            case CHITCHAT -> {
                PromptTemplateEngine.TemplateName name = PromptTemplateEngine.TemplateName.CHITCHAT;
                String marker = promptTemplateEngine.idempotencyMarker(name);
                if (!marker.isEmpty() && prompt.contains(marker)) {
                    return Mono.just(prompt);
                }
                return Mono.just(prompt + promptTemplateEngine.build(name));
            }
            case RAG_QUERY -> {
                PromptTemplateEngine.TemplateName name = PromptTemplateEngine.TemplateName.RAG_QUERY;
                String marker = promptTemplateEngine.idempotencyMarker(name);
                if (!marker.isEmpty() && prompt.contains(marker)) {
                    return Mono.just(prompt);
                }
                return Mono.just(prompt + promptTemplateEngine.build(name));
            }
            case SKILL_CREATE -> {
                if (prompt.contains("【技能创建模式】")) {
                    return Mono.just(prompt);
                }
                String skillBlock = "\n\n【技能创建模式】当前用户希望创建或修改技能，"
                        + "请引导用户描述技能的名称、用途、触发条件和参数，协助用户完成技能定义。\n";
                return Mono.just(prompt + skillBlock);
            }
            default -> {
                // TASK / CLARIFICATION(不会到这里) → 不额外注入
                return Mono.just(prompt);
            }
        }
    }

    // ===== 辅助方法 =====

    /**
     * 处理 CLARIFICATION 意图：用 LIGHT 档 LLM 生成澄清问题，包装为事件直接返回。
     *
     * <p>异常时降级：返回一条兜底澄清问题，确保用户不会无响应。</p>
     */
    private Flux<AgentEvent> handleClarification(Long tenantId, String userQuery,
                                                  List<String> recentHistory) {
        String clarificationText;
        try {
            LlmHttpClient client = llmClientFactory.create(tenantId, ModelTier.LIGHT);
            StringBuilder userPrompt = new StringBuilder();
            if (recentHistory != null && !recentHistory.isEmpty()) {
                int size = Math.min(recentHistory.size(), 5);
                userPrompt.append("对话历史：\n");
                int start = Math.max(0, recentHistory.size() - size);
                for (int i = start; i < recentHistory.size(); i++) {
                    userPrompt.append("- ").append(recentHistory.get(i)).append('\n');
                }
                userPrompt.append('\n');
            }
            userPrompt.append("当前提问：").append(userQuery);

            clarificationText = client.chat(CLARIFICATION_SYSTEM, userPrompt.toString(), 0.3f, 256, 5);
            if (clarificationText == null || clarificationText.isBlank()) {
                clarificationText = fallbackClarification();
            }
        } catch (Exception e) {
            log.warn("CLARIFICATION 澄清问题生成 LLM 失败，使用兜底: error={}", e.getMessage());
            clarificationText = fallbackClarification();
        }

        Map<String, Object> eventData = new HashMap<>(4);
        eventData.put("query", userQuery);
        eventData.put("clarification", clarificationText);
        AgentEvent clarificationEvent = new CustomEvent("intent.clarification", eventData);

        log.info("CLARIFICATION 已生成澄清问题，不进主执行: text={}", truncate(clarificationText, 80));
        return Flux.just(clarificationEvent);
    }

    /**
     * CLARIFICATION 兜底澄清问题。
     */
    private static String fallbackClarification() {
        return "你的问题我不太确定，能再具体描述一下吗？比如你想实现什么功能，或者查询哪方面的信息？";
    }

    /**
     * 提取最后一条 USER 消息的文本内容。
     */
    private static String extractUserQuery(AgentInput input) {
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return null;
        }
        List<Msg> msgs = input.msgs();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER) {
                String textContent = msg.getTextContent();
                if (textContent != null && !textContent.isBlank()) {
                    return textContent;
                }
            }
        }
        return null;
    }

    /**
     * 提取最近 5 条 USER 消息（不包含最新那一条，最新那条是当前 query）。
     * 按时间正序返回。
     */
    private static List<String> extractRecentUserHistory(AgentInput input) {
        List<String> result = new ArrayList<>();
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return result;
        }
        List<Msg> msgs = input.msgs();
        // 倒序遍历，跳过最后一条 USER（当前 query），收集前面的 USER
        boolean skippedLatestUser = false;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg == null || msg.getRole() != MsgRole.USER) {
                continue;
            }
            if (!skippedLatestUser) {
                skippedLatestUser = true;
                continue; // 跳过最新 USER 消息（已作为 userQuery 提取）
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                result.add(text);
                if (result.size() >= 5) break; // 最多收集 5 条
            }
        }
        // 反转回正序
        java.util.Collections.reverse(result);
        return result;
    }

    /**
     * 从 RuntimeContext 解析 tenantId。
     */
    private static Long resolveTenantId(RuntimeContext ctx) {
        if (ctx == null) return null;
        try {
            String tenantIdStr = ctx.get("tenantId", String.class);
            if (tenantIdStr != null && !tenantIdStr.isBlank()) {
                return Long.parseLong(tenantIdStr);
            }
        } catch (Exception e) {
            log.debug("从 RuntimeContext 获取 tenantId 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 RuntimeContext 读取 IntentType（兼容 record IntentResult 的两种存储方式）。
     */
    private static IntentType readIntentType(RuntimeContext ctx) {
        if (ctx == null) return null;
        try {
            Object raw = ctx.get(CTX_KEY_INTENT);
            if (raw instanceof IntentResult ir) {
                return ir.intent();
            }
            if (raw instanceof IntentType it) {
                return it;
            }
        } catch (Exception ignored) { /* no-op */ }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
