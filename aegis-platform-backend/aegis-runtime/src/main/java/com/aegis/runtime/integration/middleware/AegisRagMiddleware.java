package com.aegis.runtime.integration.middleware;

import com.aegis.core.common.tenant.TenantContextScope;
import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.KbDocument;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.dto.chat.SessionResourcesRef;
import com.aegis.core.enums.intent.IntentType;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.runtime.service.agent.AssemblyResourceContext;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.intent.IntentRecognitionService;
import com.aegis.runtime.service.intent.QueryRewriteService;
import com.aegis.runtime.service.rag.RagRetrieveService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAG 检索中间件（Phase 2 精简版，order=70）。
 *
 * <p>合并了原 {@code AegisIntentMiddleware} 的意图识别职责（内部化）与原 ContentFilter 的
 * 敏感词检测职责。删除 {@code TaskContextResolver} 与 {@code securityPolicyEngine} 残留依赖，
 * 直接从 {@link RuntimeContext} 读取 {@link AegisTaskContext}。
 *
 * <h3>onAgent 职责</h3>
 * <ol>
 *   <li>提取 userQuery + 最近 5 轮 USER 历史</li>
 *   <li>调用 {@link IntentRecognitionService#recognize} 识别意图，写入 {@code aegis.intent}
 *       （供 {@code AegisSkillRepository} 技能可见性 gate 读取）</li>
 *   <li>闲聊意图且无显式知识库引用时跳过 RAG</li>
 *   <li>QueryRewrite 共指消解 → 知识库绑定查询 → 向量检索（{@link RagRetrieveService#retrieve}）</li>
 *   <li>发出 {@code kb.reference} 事件，并将 RAG 上下文存入 RuntimeContext</li>
 * </ol>
 *
 * <h3>onSystemPrompt 职责</h3>
 * <ul>
 *   <li>注入 RAG 上下文（或基础约束）到系统提示词</li>
 *   <li>敏感词检测（合并 ContentFilter）：命中则追加脱敏约束</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>意图识别 / RAG 检索失败均不阻塞主流程，仅记录日志并透传 next.apply(input)。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisRagMiddleware implements MiddlewareBase {

    /** RuntimeContext 中存储意图识别结果的 key（与 AegisSkillRepository 本地常量同值） */
    public static final String CTX_KEY_INTENT = "aegis.intent";

    /** 基础敏感词集合（合并自原 ContentFilter 职责，最小内联实现） */
    private static final Set<String> SENSITIVE_WORDS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key",
            "身份证", "银行卡", "密码", "密钥");

    private final RagRetrieveService ragRetrieveService;
    private final ResourceQueryService resourceQueryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentRecognitionService intentService;

    /** RAG 中间件开关，默认开启 */
    @Value("${aegis.runtime.rag.enabled:true}")
    private boolean ragEnabled;

    @Override
    public int order() {
        // order=70：位于 Trace(95)/BindingSync(75) 之后，Mask(50)/Audit(30) 之前
        return 70;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        log.debug("AegisRagMiddleware.onAgent 触发");

        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null || taskCtx.getAgentId() == null) {
            log.debug("AegisTaskContext 未注入或 agentId 为空，跳过: agentId={}",
                    agent != null ? agent.getAgentId() : "null");
            return next.apply(input);
        }

        long agentId = taskCtx.getAgentId();
        Long tenantId = taskCtx.getTenantId() != null ? taskCtx.getTenantId() : resolveTenantId(ctx);

        // 关键修复：AgentScope 中间件链在独立线程调用 onAgent，ThreadLocal 租户上下文已丢失。
        // 所有 Service/Mapper 调用前必须绑定租户上下文（MyBatis-Plus 租户插件 fail-closed）。
        // 这里用 TenantContextScope 包裹整个业务逻辑块，确保所有 DB 操作安全。
        if (tenantId == null) {
            log.warn("AegisRagMiddleware tenantId 为空，跳过 RAG 检索: agentId={}", agentId);
            return next.apply(input);
        }

        try (var scope = TenantContextScope.of(tenantId)) {
            return doOnAgentInternal(agent, ctx, input, next, taskCtx, agentId, tenantId);
        }
    }

    /**
     * onAgent 的实际业务逻辑（已在租户上下文内执行）。
     * 抽取为独立方法是为了让租户上下文 try-with-resources 清晰包裹。
     */
    private Flux<AgentEvent> doOnAgentInternal(Agent agent, RuntimeContext ctx, AgentInput input,
                                                Function<AgentInput, Flux<AgentEvent>> next,
                                                AegisTaskContext taskCtx, long agentId, Long tenantId) {
        // 1. 提取用户查询 + 最近 5 轮 USER 历史
        String userQuery = extractUserQuery(input);
        List<String> recentHistory = extractRecentUserHistory(input);

        // 2. 合并意图识别（内部化）：写入 aegis.intent 供技能 gate 读取
        IntentRecognitionService.IntentResult intentResult = null;
        if (userQuery != null && !userQuery.isBlank()) {
            try {
                intentResult = intentService.recognize(tenantId, userQuery, recentHistory);
                ctx.put(CTX_KEY_INTENT, intentResult);
                log.info("意图识别完成: agentId={}, query={}, intent={}, confidence={}, needRag={}, needTools={}",
                        agentId, truncate(userQuery, 60), intentResult.intent(), intentResult.confidence(),
                        intentResult.needRag(), intentResult.needTools());
            } catch (Exception e) {
                log.warn("意图识别失败，降级为不跳过 RAG: agentId={}, error={}", agentId, e.getMessage());
            }
        }

        // 3. 全局开关检查
        if (!ragEnabled) {
            log.debug("RAG 中间件已禁用（aegis.runtime.rag.enabled=false），跳过检索");
            return next.apply(input);
        }

        // 4. 闲聊意图且无显式知识库引用 → 跳过 RAG
        boolean hasExplicitKbRef = hasExplicitKbRef(taskCtx);
        IntentType intent = intentResult != null ? intentResult.intent() : null;
        if (intent == IntentType.CHITCHAT && !hasExplicitKbRef) {
            log.debug("闲聊意图且无显式知识库引用，跳过 RAG 检索: agentId={}", agentId);
            return next.apply(input);
        }

        // 5. 无查询文本 → 跳过 RAG
        if (userQuery == null || userQuery.isBlank()) {
            log.debug("无用户查询文本，跳过 RAG 检索");
            return next.apply(input);
        }

        // 6. QueryRewrite：共指消解
        String effectiveQuery = resolveEffectiveQuery(userQuery, recentHistory, tenantId);

        // 7. 查询知识库绑定（装配期预载优先，回退 DB 直查）
        List<AgentBinding> allBindings = AssemblyResourceContext.enabledBindingsOf(ctx);
        if (allBindings == null) {
            allBindings = resourceQueryService.listEnabledBindings(agentId);
        }
        List<Long> kbIds = collectKbIds(allBindings, ctx, taskCtx, tenantId);
        if (kbIds.isEmpty()) {
            log.debug("智能体未绑定知识库且无会话级资源引用，跳过 RAG 检索: agentId={}", agentId);
            return next.apply(input);
        }

        // 8. 一次批量装载 KB 实体（名称补全复用）
        Map<Long, KnowledgeBase> kbEntityMap = new HashMap<>();
        for (KnowledgeBase kb : resourceQueryService.findKnowledgeBasesByIds(Set.copyOf(kbIds))) {
            kbEntityMap.put(kb.getId(), kb);
        }

        // 9. 执行 RAG 检索（R-7：QueryRewrite 已在本中间件 L172 单次完成，retrieve 不再内层改写）
        List<Map<String, Object>> allRefs = new ArrayList<>();
        Set<Long> pendingDocIds = new LinkedHashSet<>();
        for (Long kbId : kbIds) {
            try {
                List<Map<String, Object>> results = ragRetrieveService.retrieve(
                        tenantId, kbId, effectiveQuery, 0);
                if (results != null && !results.isEmpty()) {
                    KnowledgeBase kb = kbEntityMap.get(kbId);
                    String kbName = kb != null ? kb.getKbName() : null;
                    for (Map<String, Object> ref : results) {
                        ref.put("kbId", kbId);
                        ref.put("kbName", kbName);
                        Object docIdObj = ref.get("docId");
                        if (docIdObj instanceof Number docIdNum) {
                            Long docId = docIdNum.longValue();
                            pendingDocIds.add(docId);
                            ref.put("id", "kb-ref-" + kbId + "-" + docId + "-" + ref.get("chunkIndex"));
                        } else {
                            ref.put("id", "kb-ref-" + kbId + "-" + ref.get("chunkIndex"));
                        }
                    }
                    allRefs.addAll(results);
                }
            } catch (Exception e) {
                log.warn("RAG 检索失败: kbId={}, error={}", kbId, e.getMessage());
            }
        }

        // 10. 批量查询文档名补全引用
        if (!pendingDocIds.isEmpty()) {
            Map<Long, String> docNameMap = new HashMap<>();
            for (KbDocument doc : resourceQueryService.findKbDocumentsByIds(pendingDocIds)) {
                docNameMap.put(doc.getId(), doc.getFileName());
            }
            for (Map<String, Object> ref : allRefs) {
                Object docIdObj = ref.get("docId");
                if (docIdObj instanceof Number docIdNum) {
                    ref.put("docName", docNameMap.get(docIdNum.longValue()));
                }
            }
        }

        if (allRefs.isEmpty()) {
            log.debug("RAG 无检索结果: agentId={}, kbCount={}", agentId, kbIds.size());
            return next.apply(input);
        }

        log.info("RAG 检索完成: agentId={}, kbCount={}, refCount={}", agentId, kbIds.size(), allRefs.size());

        // 11. 发出 kb.reference 事件
        Map<String, Object> eventData = new HashMap<>(4);
        eventData.put("replyId", extractReplyId(ctx));
        eventData.put("refs", allRefs);
        eventData.put("query", effectiveQuery);
        eventData.put("kbCount", kbIds.size());
        AgentEvent kbRefEvent = new CustomEvent("kb.reference", eventData);

        // 12. RAG 上下文存入 RuntimeContext，供 onSystemPrompt 注入
        String ragContext = buildRagContext(allRefs);
        try {
            ctx.put("aegis.ragContext", ragContext);
            ctx.put("aegis.ragRefs", allRefs);
        } catch (Exception e) {
            log.warn("RAG 上下文存储失败: {}", e.getMessage());
        }

        return Flux.just(kbRefEvent).concatWith(next.apply(input));
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(prompt);
        }
        try {
            String ragContext = ctx.get("aegis.ragContext", String.class);
            String base;
            if (ragContext != null && !ragContext.isEmpty()) {
                base = ragContext;
            } else if (prompt.contains("【重要约束】")) {
                base = "";
            } else {
                base = buildBaseConstraint();
            }
            String result = base.isEmpty() ? prompt : prompt + base;

            // 敏感词检测（合并 ContentFilter 职责）：扫描注入后提示词，命中则追加脱敏约束
            String hit = detectSensitive(result);
            if (hit != null) {
                log.info("AegisRagMiddleware 敏感词命中，追加脱敏约束: word={}", hit);
                result = result + "\n\n【敏感信息约束】请勿在回复中直接输出密钥、口令、"
                        + "身份证号、银行卡号等敏感信息，必要时以掩码形式呈现。\n";
            }
            return Mono.just(result);
        } catch (Exception e) {
            log.error("AegisRagMiddleware onSystemPrompt 异常: {}", e.getMessage(), e);
            return Mono.just(prompt);
        }
    }

    // ==================== 私有工具方法 ====================

    private List<Long> collectKbIds(List<AgentBinding> allBindings, RuntimeContext ctx,
                                    AegisTaskContext taskCtx, Long tenantId) {
        List<Long> kbIds = new ArrayList<>();
        for (AgentBinding binding : allBindings) {
            if (binding.getResourceType() == ResourceType.KNOWLEDGE_BASE) {
                kbIds.add(binding.getResourceId());
            }
        }
        // 会话级知识库引用：优先 RuntimeContext 已校验列表，回退原始引用 + 可引用性校验
        List<Long> sessionKbIdsFromCtx = resolveSessionKbIds(ctx);
        List<Long> sessionKbIds;
        SessionResourcesRef sessionResources = taskCtx.getSessionResources();
        if (sessionKbIdsFromCtx != null && !sessionKbIdsFromCtx.isEmpty()) {
            sessionKbIds = sessionKbIdsFromCtx;
        } else if (sessionResources != null && sessionResources.getKbIds() != null
                && !sessionResources.getKbIds().isEmpty()) {
            sessionKbIds = filterReferenceableKbIds(sessionResources.getKbIds(), tenantId, taskCtx.getUserId());
            log.warn("RAG 回退到会话级知识库引用（RuntimeContext 缺失，已按可引用性过滤）: raw={}, valid={}",
                    sessionResources.getKbIds().size(), sessionKbIds.size());
        } else {
            sessionKbIds = List.of();
        }
        for (Long kbId : sessionKbIds) {
            if (kbId != null && !kbIds.contains(kbId)) {
                kbIds.add(kbId);
            }
        }
        return kbIds;
    }

    private String resolveEffectiveQuery(String userQuery, List<String> recentHistory, Long tenantId) {
        if (recentHistory == null || recentHistory.isEmpty()) {
            return userQuery;
        }
        try {
            String rewritten = queryRewriteService.resolveCoreference(userQuery, recentHistory, tenantId);
            if (rewritten != null && !rewritten.isBlank() && !rewritten.equals(userQuery)) {
                log.debug("QueryRewrite: 原始=[{}], 改写=[{}]", userQuery, rewritten);
                return rewritten;
            }
        } catch (Exception e) {
            log.warn("QueryRewrite 失败，降级为原始 query: error={}", e.getMessage());
        }
        return userQuery;
    }

    private boolean hasExplicitKbRef(AegisTaskContext taskCtx) {
        try {
            SessionResourcesRef ref = taskCtx.getSessionResources();
            return ref != null && ref.getKbIds() != null && !ref.getKbIds().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Long> filterReferenceableKbIds(List<Long> rawKbIds, Long tenantId, Long userId) {
        try (var ignore = TenantContextScope.of(tenantId)) {
            Set<Long> ids = rawKbIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
            return resourceQueryService.findReferenceableKnowledgeBasesByIds(ids, userId)
                    .stream().map(KnowledgeBase::getId).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("回退路径知识库可引用性校验失败，忽略全部会话级引用: error={}", e.getMessage());
            return List.of();
        }
    }

    private String extractUserQuery(AgentInput input) {
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return null;
        }
        List<Msg> msgs = input.msgs();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg != null && msg.getRole() == MsgRole.USER) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private List<String> extractRecentUserHistory(AgentInput input) {
        List<String> result = new ArrayList<>();
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return result;
        }
        List<Msg> msgs = input.msgs();
        boolean skippedLatestUser = false;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Msg msg = msgs.get(i);
            if (msg == null || msg.getRole() != MsgRole.USER) {
                continue;
            }
            if (!skippedLatestUser) {
                skippedLatestUser = true;
                continue;
            }
            String text = msg.getTextContent();
            if (text != null && !text.isBlank()) {
                result.add(text);
                if (result.size() >= 5) break;
            }
        }
        Collections.reverse(result);
        return result;
    }

    private Long resolveTenantId(RuntimeContext ctx) {
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

    private String extractReplyId(RuntimeContext ctx) {
        if (ctx == null) return null;
        try {
            return ctx.get("replyId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> resolveSessionKbIds(RuntimeContext ctx) {
        if (ctx == null) return List.of();
        try {
            Object raw = ctx.get("aegis.sessionKbIds");
            if (raw == null) return List.of();
            if (raw instanceof List<?> list) {
                List<Long> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Number n) result.add(n.longValue());
                }
                return result;
            }
            if (raw.getClass().isArray()) {
                Object[] arr = (Object[]) raw;
                List<Long> result = new ArrayList<>();
                for (Object item : arr) {
                    if (item instanceof Number n) result.add(n.longValue());
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("解析会话级知识库ID失败: {}", e.getMessage());
        }
        return List.of();
    }

    private String buildRagContext(List<Map<String, Object>> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【知识库检索结果】\n");
        for (int i = 0; i < refs.size(); i++) {
            Map<String, Object> ref = refs.get(i);
            String content = ref.get("content") != null ? ref.get("content").toString() : "";
            Double score = ref.get("score") instanceof Number n ? n.doubleValue() : null;
            Long kbId = ref.get("kbId") instanceof Number n ? n.longValue() : null;
            // R-1 量纲标签：rerankScore→重排分；rrfScore→融合分；否则相似度
            // （RRF 分 ~0.016 量纲标注为"相似度"会令 LLM 判定完全不相关而忽略检索结果）
            String scoreLabel = ref.containsKey("rerankScore") ? "重排分"
                    : ref.containsKey("rrfScore") ? "融合分"
                    : "相似度";
            // R-2 单条截断 200→500（中文约 250 字，避免关键结论被腰斩）
            sb.append(String.format("%d. [知识库#%s, %s=%.3f] %s\n",
                    i + 1, kbId != null ? kbId : "?", scoreLabel,
                    score != null ? score : 0.0, truncate(content, 500)));
        }
        sb.append("\n【重要约束】\n");
        sb.append("1. 严禁使用文件工具（list_files/glob_files/grep_files/read_file 等）在工作区搜索文档——"
                + "工作区中不存在知识库文档。知识库内容仅通过本检索结果提供。\n");
        sb.append("2. 代码执行必须使用 execute 工具（AgentScope ShellExecuteTool，可执行 Python/Bash/Node），"
                + "该工具走框架 K8s 沙箱 Pod，具备安全隔离和超时保护。\n");
        sb.append("3. 必须参考以上【知识库检索结果】回答用户问题，明确引用检索结果中的观点和数据。"
                + "若检索结果与问题无关，请直接基于自身知识回答，并说明检索未命中。\n");
        return sb.toString();
    }

    private static String buildBaseConstraint() {
        return "\n\n【重要约束】"
                + "1. 严禁使用文件工具（list_files/glob_files/grep_files/read_file 等）在工作区搜索文档——"
                + "工作区中不存在知识库文档。"
                + "2. 代码执行必须使用 execute 工具（AgentScope ShellExecuteTool，可执行 Python/Bash/Node），"
                + "该工具走框架 K8s 沙箱 Pod，具备安全隔离和超时保护。\n";
    }

    private static String detectSensitive(String text) {
        if (text == null || text.isEmpty()) return null;
        String lower = text.toLowerCase();
        for (String w : SENSITIVE_WORDS) {
            if (lower.contains(w.toLowerCase())) return w;
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
