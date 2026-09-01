package com.aegis.runtime.integration.middleware;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.KbDocument;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.enums.intent.IntentType;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.runtime.integration.middleware.AegisIntentMiddleware;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.intent.IntentRecognitionService.IntentResult;
import com.aegis.runtime.service.intent.QueryRewriteService;
import com.aegis.runtime.service.rag.RagRetrieveService;
import com.aegis.runtime.service.agent.AssemblyResourceContext;
import com.aegis.runtime.service.agent.ResourceQueryService;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.dto.security.SecurityPolicyContext;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.dto.chat.SessionResourcesRef;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.context.TenantContext;

/**
 * RAG 检索中间件：在 Agent 执行前触发知识库检索，注入上下文并发出 kb.reference 事件。
 *
 * <p>作为 AgentScope {@link OrderedMiddleware} 的实现，注入 HarnessAgent 的中间件链，
 * 在 onAgent 钩子中：
 * <ol>
 *   <li>从 input.msgs() 提取用户查询文本</li>
 *   <li>查询 agent_binding 中 KNOWLEDGE_BASE 类型的绑定</li>
 *   <li>调用 {@link RagRetrieveService#retrieve} 执行向量检索</li>
 *   <li>发出 {@code kb.reference} 事件，携带检索结果</li>
 *   <li>将检索结果注入系统提示词，供 LLM 生成时参考</li>
 * </ol>
 *
 * <h3>执行时机</h3>
 * <p>order=65，在 AgentScope 降序排列中位于：
 * <ul>
 *   <li>租户隔离(80) 之后执行 — 确保 tenantId 已注入上下文</li>
 *   <li>配额检查(70) 之后执行 — 确保资源配额已校验</li>
 *   <li>内容过滤(60) 之前执行 — 在系统提示词被内容安全变换前完成 RAG 上下文注入</li>
 * </ul>
 *
 * <h3>降级策略</h3>
 * <p>RAG 检索失败不阻塞对话流程，仅记录警告日志并透传 next.apply(input)。
 * 确保检索异常不会影响主链路稳定性。单库检索失败不影响其他库的检索。</p>
 *
 * <h3>配置开关</h3>
 * <p>通过 {@code aegis.runtime.rag.enabled=false} 全局禁用 RAG 检索，默认开启。</p>
 *
 * @author wang.zhen
 * @see RagRetrieveService
 * @see ResourceQueryService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisRagMiddleware implements MiddlewareBase, OrderedMiddleware {

    @PostConstruct
    public void init() {
        log.info("AegisRagMiddleware 已初始化: ragEnabled={}, order={}", ragEnabled, order());
    }

    private final RagRetrieveService ragRetrieveService;
    private final ResourceQueryService resourceQueryService;
    private final QueryRewriteService queryRewriteService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.aegis.runtime.service.policy.AegisSecurityPolicyEngine securityPolicyEngine;

    /** RAG 中间件开关，默认开启。设为 false 可全局禁用 RAG 检索 */
    @Value("${aegis.runtime.rag.enabled:true}")
    private boolean ragEnabled;

    @Override
    public int order() {
        return 65;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        log.info("=== AegisRagMiddleware.onAgent 触发 ===");
        
        // 0. 全局开关检查
        if (!ragEnabled) {
            log.info("RAG 中间件已禁用（aegis.runtime.rag.enabled=false），跳过检索");
            return next.apply(input);
        }

        // 0.1 意图检查：CHITCHAT 跳过 RAG，但**显式带知识库时强制走 RAG**
        // 用户主动勾选知识库 = 明确意图要检索，哪怕 query 是闲聊也要搜
        boolean hasExplicitKbRef = false;
        try {
            AegisTaskContext quickCtx = ctx.get(AegisTaskContext.class);
            if (quickCtx != null && quickCtx.getSessionResources() != null
                    && quickCtx.getSessionResources().getKbIds() != null
                    && !quickCtx.getSessionResources().getKbIds().isEmpty()) {
                hasExplicitKbRef = true;
            }
        } catch (Exception ignored) {
        }
        IntentType intent = readIntentType(ctx);
        if (intent == IntentType.CHITCHAT && !hasExplicitKbRef) {
            log.debug("AegisRagMiddleware: 闲聊意图且无显式知识库引用，跳过 RAG 检索");
            return next.apply(input);
        }
        if (intent == IntentType.CHITCHAT && hasExplicitKbRef) {
            log.info("AegisRagMiddleware: 闲聊意图但用户显式选择了知识库，仍执行 RAG 检索");
        }

        // 1. 提取用户查询 + 最近 5 轮 USER 历史（供 QueryRewrite 共指消解使用）
        String userQuery = extractUserQuery(input);
        if (userQuery == null || userQuery.isBlank()) {
            log.debug("无用户查询文本，跳过 RAG 检索");
            return next.apply(input);
        }
        List<String> recentHistory = extractRecentUserHistory(input);

        // 2. 从 RuntimeContext 直接获取 AegisTaskContext（优先），回退到 TaskContextResolver
        AegisTaskContext taskCtx = null;
        try {
            taskCtx = ctx.get(AegisTaskContext.class);
        } catch (Exception e) {
            log.debug("从 RuntimeContext 直接获取 AegisTaskContext 失败: {}", e.getMessage());
        }
        if (taskCtx == null) {
            taskCtx = TaskContextResolver.resolve(agent);
        }
        if (taskCtx == null || taskCtx.getAgentId() == null) {
            log.debug("AegisTaskContext 未获取到或 agentId 为空，跳过 RAG 检索: agentId={}",
                    agent != null ? agent.getAgentId() : "null");
            return next.apply(input);
        }

        long agentId = taskCtx.getAgentId();

        // 3. 获取 tenantId（优先从 AegisTaskContext，其次从 RuntimeContext）
        Long tenantId = taskCtx.getTenantId();
        if (tenantId == null) {
            tenantId = resolveTenantId(ctx);
        }

        // 3.1 QueryRewrite：共指消解，得到独立检索 query
        // 无论知识库是否配置 enableQueryRewrite，中间件层先做统一改写入口；
        // 后续 RagRetrieveService.retrieve() 内部会根据 kb.enableQueryRewrite
        // 决定是否做二次改写（此时 query 已是独立文本，二次改写安全无副作用）
        String effectiveQuery = userQuery;
        if (recentHistory != null && !recentHistory.isEmpty()) {
            try {
                String rewritten = queryRewriteService.resolveCoreference(userQuery, recentHistory, tenantId);
                if (rewritten != null && !rewritten.isBlank() && !rewritten.equals(userQuery)) {
                    effectiveQuery = rewritten;
                    log.debug("AegisRagMiddleware QueryRewrite: 原始=[{}], 改写=[{}]", userQuery, effectiveQuery);
                }
            } catch (Exception e) {
                // LLM 异常降级为原始 query
                log.warn("AegisRagMiddleware QueryRewrite 失败，降级为原始 query: error={}", e.getMessage());
            }
        }

        // 4. 查询知识库绑定（agent_binding + 会话级资源引用）
        // T3 收敛：优先复用装配期 AssemblyResourceContext 预载的 enabled 绑定，
        // 上下文缺失（构建失败/非装配链路）时降级 DB 直查
        List<AgentBinding> allBindings = AssemblyResourceContext.enabledBindingsOf(ctx);
        if (allBindings == null) {
            allBindings = resourceQueryService.listEnabledBindings(agentId);
        }
        List<Long> kbIds = new ArrayList<>();
        
        // 4.1 从 agent_binding 获取绑定的知识库
        for (AgentBinding binding : allBindings) {
            if (binding.getResourceType() == ResourceType.KNOWLEDGE_BASE) {
                kbIds.add(binding.getResourceId());
            }
        }
        
        // 4.2 会话级知识库引用：优先使用 RuntimeContext 中已校验的列表
        // （AgentAssemblyService.buildRuntimeContext 已通过 filterValidKbIds 过滤
        //   "不存在/未发布"的知识库；原始请求 kbIds 中的无效 ID 不应进入检索链路，
        //   否则会产生"知识库不存在"告警并浪费一路检索）
        List<Long> sessionKbIdsFromCtx = resolveSessionKbIds(ctx);
        SessionResourcesRef sessionResources = taskCtx.getSessionResources();
        List<Long> sessionKbIds;
        if (sessionKbIdsFromCtx != null && !sessionKbIdsFromCtx.isEmpty()) {
            sessionKbIds = sessionKbIdsFromCtx;
            log.debug("RAG 使用已校验的会话级知识库（RuntimeContext）: agentId={}, sessionKbCount={}",
                    agentId, sessionKbIds.size());
        } else if (sessionResources != null && sessionResources.getKbIds() != null
                && !sessionResources.getKbIds().isEmpty()) {
            // 兼容路径：RuntimeContext 未注入时回退到原始引用，但必须补做可引用性校验
            // （草稿/审核中知识库仅创建者本人可引用，防止同租户用户越权检索未发布库）
            sessionKbIds = filterReferenceableKbIds(sessionResources.getKbIds(), tenantId, taskCtx.getUserId());
            log.warn("RAG 回退到会话级知识库引用（RuntimeContext 缺失，已按可引用性过滤）: agentId={}, raw={}, valid={}",
                    agentId, sessionResources.getKbIds().size(), sessionKbIds.size());
        } else {
            sessionKbIds = List.of();
        }
        for (Long kbId : sessionKbIds) {
            if (kbId != null && !kbIds.contains(kbId)) {
                kbIds.add(kbId);
            }
        }

        if (kbIds.isEmpty()) {
            log.debug("智能体未绑定知识库且无会话级资源引用，跳过 RAG 检索: agentId={}", agentId);
            return next.apply(input);
        }

        // v4.2: KB 安全等级 gate（覆盖绑定与会话引用的全部知识库）
        // 安全等级按最终待检索 kbIds 全集查询——会话级引用与绑定库执行同一门控，
        // 防止通过会话引用绕过安全等级校验
        Map<Long, SecurityLevel> kbLevels = new HashMap<>();
        if (securityPolicyEngine != null) {
            for (Long kbId : kbIds) {
                try {
                    com.aegis.core.domain.resource.KnowledgeBase kb = resourceQueryService.getKnowledgeBase(kbId);
                    if (kb != null && kb.getSecurityLevel() != null) {
                        kbLevels.put(kbId, kb.getSecurityLevel());
                    }
                } catch (Exception e) {
                    log.debug("获取知识库安全等级失败: kbId={}", kbId);
                }
            }
        }
        // 过滤掉安全等级超标/需审批未决的知识库，并记录跳过原因（前端可见）
        List<Map<String, Object>> skippedKbs = new ArrayList<>();
        Iterator<Long> kbIterator = kbIds.iterator();
        while (kbIterator.hasNext()) {
            Long kbId = kbIterator.next();
            SecurityLevel kbLevel = kbLevels.get(kbId);
            if (kbLevel != null && securityPolicyEngine != null) {
                SecurityPolicyContext gateCtx = SecurityPolicyContext.builder()
                        .tenantId(tenantId)
                        .agentId(agentId)
                        .resourceType(ResourceType.KNOWLEDGE_BASE)
                        .resourceLevel(kbLevel)
                        .action(SecurityPolicyContext.Action.KB_RETRIEVE)
                        .build();
                PolicyDecision decision = securityPolicyEngine.evaluateKbRetrievePolicy(gateCtx);
                if (decision.isReject()) {
                    log.info("RAG KB gate REJECT: kbId={}, reason={}", kbId, decision.getReason());
                    kbIterator.remove();
                    skippedKbs.add(Map.of(
                            "kbId", kbId,
                            "action", "REJECT",
                            "reason", decision.getReason() != null ? decision.getReason() : "绝密知识库(L4)禁止访问"));
                } else if (decision.isAsk()) {
                    // v4.3：L3 机密知识库检索前需审批。知识库检索没有工具级 HITL 审批流，
                    // 未审批前检索等同绕过治理——跳过检索并通知前端
                    log.info("RAG KB gate ASK(跳过检索): kbId={}, reason={}", kbId, decision.getReason());
                    kbIterator.remove();
                    skippedKbs.add(Map.of(
                            "kbId", kbId,
                            "action", "ASK",
                            "reason", decision.getReason() != null ? decision.getReason() : "机密知识库(L3)检索需审批，请联系管理员开通"));
                }
            }
        }

        // 5. 执行 RAG 检索（使用改写后的 effectiveQuery + recentHistory 传递给新签名）
        List<Map<String, Object>> allRefs = new ArrayList<>();
        Map<Long, String> kbNameCache = new HashMap<>();
        Map<Long, String> docNameCache = new HashMap<>();
        List<String> historyForRetrieve = recentHistory != null ? recentHistory : Collections.emptyList();
        for (Long kbId : kbIds) {
            try {
                // B4: topK=0 让 RagRetrieveService 使用知识库自身的 topK 配置
                // （原硬编码 5 会忽略知识库配的 top_k，导致检索条数不符合用户预期）
                // 新签名：额外传入 recentHistory 供 RagRetrieveService 内部二次 QueryRewrite
                List<Map<String, Object>> results = ragRetrieveService.retrieve(
                        tenantId, kbId, effectiveQuery, 0, historyForRetrieve);
                if (results != null && !results.isEmpty()) {
                    // 标注来源知识库，并补充文档名/知识库名
                    KnowledgeBase kb = resourceQueryService.getKnowledgeBase(kbId);
                    String kbName = kb != null ? kb.getKbName() : null;
                    kbNameCache.put(kbId, kbName);
                    
                    for (Map<String, Object> ref : results) {
                        ref.put("kbId", kbId);
                        ref.put("kbName", kbName);
                        
                        Object docIdObj = ref.get("docId");
                        if (docIdObj instanceof Number docIdNum) {
                            Long docId = docIdNum.longValue();
                            if (!docNameCache.containsKey(docId)) {
                                KbDocument doc = resourceQueryService.getKbDocument(docId);
                                docNameCache.put(docId, doc != null ? doc.getFileName() : null);
                            }
                            ref.put("docName", docNameCache.get(docId));
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

        if (allRefs.isEmpty()) {
            // 无命中但有被门控跳过的库：仍发事件，让前端明确告知用户"引用的库未参与检索"
            if (!skippedKbs.isEmpty()) {
                Map<String, Object> skipEventData = new HashMap<>(4);
                skipEventData.put("replyId", extractReplyId(ctx));
                skipEventData.put("refs", List.of());
                skipEventData.put("query", effectiveQuery);
                skipEventData.put("kbCount", 0);
                skipEventData.put("skippedKbs", skippedKbs);
                log.info("RAG 检索被门控跳过: agentId={}, skipped={}", agentId, skippedKbs.size());
                AgentEvent skipEvent = new CustomEvent("kb.reference", skipEventData);
                return Flux.just(skipEvent)
                        .concatWith(next.apply(input));
            }
            log.debug("RAG 无检索结果: agentId={}, kbCount={}", agentId, kbIds.size());
            return next.apply(input);
        }

        log.info("RAG 检索完成: agentId={}, kbCount={}, refCount={}, skipped={}",
                agentId, kbIds.size(), allRefs.size(), skippedKbs.size());

        // 6. 构建 kb.reference 事件数据
        Map<String, Object> eventData = new HashMap<>(8);
        eventData.put("replyId", extractReplyId(ctx));
        eventData.put("refs", allRefs);
        eventData.put("query", effectiveQuery);
        eventData.put("kbCount", kbIds.size());
        if (!skippedKbs.isEmpty()) {
            eventData.put("skippedKbs", skippedKbs);
        }

        AgentEvent kbRefEvent = new CustomEvent("kb.reference", eventData);

        // 7. 将 RAG 上下文存储到 RuntimeContext，供后续 Prompt 构建器读取
        String ragContext = buildRagContext(allRefs);
        try {
            ctx.put("aegis.ragContext", ragContext);
            ctx.put("aegis.ragRefs", allRefs);
            log.debug("RAG 上下文已存储到 RuntimeContext: contextLen={}", ragContext.length());
        } catch (Exception e) {
            log.warn("RAG 上下文存储失败: {}", e.getMessage());
        }

        // 8. 返回：先发出 kb.reference 事件，再透传原始输入（由 onSystemPrompt 从 RuntimeContext 注入系统提示词）
        return Flux.just(kbRefEvent)
                .concatWith(next.apply(input));
    }

    /**
     * onSystemPrompt 触发点：从 RuntimeContext 读取 RAG 上下文并注入系统提示词。
     *
     * <p>在系统提示词构建时，读取由 onAgent 存储到 RuntimeContext 的
     * {@code aegis.ragContext}，追加到系统提示词末尾，供 LLM 参考。
     * 同时添加基础约束：禁止使用文件工具搜索知识库文档。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(prompt);
        }
        try {
            String ragContext = ctx.get("aegis.ragContext", String.class);
            if (ragContext == null || ragContext.isEmpty()) {
                // 无知识库上下文：只加基础约束（如果还没加的话）
                if (prompt.contains("【重要约束】")) {
                    return Mono.just(prompt);
                }
                String baseConstraint = "\n\n【重要约束】"
                        + "1. 严禁使用文件工具（list_files/glob_files/grep_files/read_file 等）在工作区搜索文档——"
                        + "工作区中不存在知识库文档。"
                        + "2. 代码执行必须使用 aegis_execute 工具（描述为\"Aegis 代码执行 - Python 计算与数据处理\"），"
                        + "该工具使用 Aegis 后台沙箱池，具备安全隔离和超时保护。\n";
                return Mono.just(prompt + baseConstraint);
            }
            // 有知识库上下文：直接追加 ragContext
            // ragContext 本身已包含完整的 "【知识库检索结果】" 标记 + 检索内容 + 结尾约束
            // 注意：不要检查 prompt.contains("【知识库检索结果】") —— IntentMiddleware 的 RAG_QUERY 模板里
            // 有 "请优先参考【知识库检索结果】中的内容回答" 提示文本，会导致误判
            String finalPrompt = prompt + ragContext;
            log.info(">>> RAG onSystemPrompt EXIT: appended ragContext, finalPromptLen={}", finalPrompt.length());
            return Mono.just(finalPrompt);
        } catch (Exception e) {
            log.error(">>> RAG onSystemPrompt 异常: {}", e.getMessage(), e);
            return Mono.just(prompt);
        }
    }

    /**
     * 从 AgentInput 中提取用户查询文本。
     *
     * <p>取最后一条 user 角色消息的文本内容作为查询。
     */
    private String extractUserQuery(AgentInput input) {
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return null;
        }
        List<Msg> msgs = input.msgs();
        // 取最后一条用户消息
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
     * 从 AgentInput 中提取最近 5 条 USER 历史消息（不含最新那条，最新那条由 extractUserQuery 返回）。
     *
     * <p>按时间正序返回，供 QueryRewrite 共指消解使用。</p>
     */
    private List<String> extractRecentUserHistory(AgentInput input) {
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
     * 从 RuntimeContext 读取意图识别结果中的 IntentType。
     *
     * <p>兼容两种存储方式：{@link IntentResult} record 或直接 {@link IntentType}。</p>
     *
     * @return IntentType，未识别或读取失败时返回 null
     */
    private static IntentType readIntentType(RuntimeContext ctx) {
        if (ctx == null) return null;
        try {
            Object raw = ctx.get(AegisIntentMiddleware.CTX_KEY_INTENT);
            if (raw instanceof IntentResult ir) {
                return ir.intent();
            }
            if (raw instanceof IntentType it) {
                return it;
            }
        } catch (Exception e) {
            log.debug("读取 aegis.intent 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 回退路径的可引用性校验：过滤"不存在 / 未发布且非创建者"的知识库。
     *
     * <p>正常路径由 AgentAssemblyService.filterValidKbIds 在装配期完成校验并注入
     * RuntimeContext；本方法仅在 RuntimeContext 缺失的兼容路径上补做同一套规则，
     * 确保任何检索请求都不会绕过创建者校验。校验异常时按安全优先原则整体忽略。
     */
    private List<Long> filterReferenceableKbIds(List<Long> rawKbIds, Long tenantId, Long userId) {
        try {
            // knowledge_base 受多租户行级过滤，回退路径可能运行在未绑定租户的线程上
            if (tenantId != null) {
                TenantContextHolder.set(TenantContext.builder().tenantId(tenantId).build());
            }
            Set<Long> ids = rawKbIds.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return resourceQueryService.findReferenceableKnowledgeBasesByIds(ids, userId)
                    .stream()
                    .map(KnowledgeBase::getId)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("回退路径知识库可引用性校验失败，忽略全部会话级引用: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 RuntimeContext 解析 tenantId。
     */
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

    /**
     * 从 RuntimeContext 提取 replyId。
     */
    private String extractReplyId(RuntimeContext ctx) {
        if (ctx == null) return null;
        try {
            return ctx.get("replyId", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建 RAG 上下文文本，用于注入系统提示词。
     *
     * <p>包含完整约束：禁止使用文件工具、代码执行工具要求、引用知识库回答要求。
     */
    private String buildRagContext(List<Map<String, Object>> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【知识库检索结果】\n");
        for (int i = 0; i < refs.size(); i++) {
            Map<String, Object> ref = refs.get(i);
            String content = ref.get("content") != null ? ref.get("content").toString() : "";
            Double score = ref.get("score") instanceof Number n ? n.doubleValue() : null;
            Long kbId = ref.get("kbId") instanceof Number n ? n.longValue() : null;
            sb.append(String.format("%d. [知识库#%s, 相似度=%.3f] %s\n",
                    i + 1, kbId != null ? kbId : "?",
                    score != null ? score : 0.0,
                    truncate(content, 200)));
        }
        sb.append("\n【重要约束】\n");
        sb.append("1. 严禁使用文件工具（list_files/glob_files/grep_files/read_file 等）在工作区搜索文档——"
                + "工作区中不存在知识库文档。知识库内容仅通过本检索结果提供。\n");
        sb.append("2. 代码执行必须使用 aegis_execute 工具（描述为\"Aegis 代码执行 - Python 计算与数据处理\"），"
                + "该工具使用 Aegis 后台沙箱池，具备安全隔离和超时保护。\n");
        sb.append("3. 必须参考以上【知识库检索结果】回答用户问题，明确引用检索结果中的观点和数据。"
                + "若检索结果与问题无关，请直接基于自身知识回答，并说明检索未命中。\n");
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * 从 RuntimeContext 中解析会话级资源引用的知识库ID列表。
     *
     * <p>读取由 {@link AgentAssemblyService#buildRuntimeContext} 注入的
     * {@code aegis.sessionKbIds} 键值，该值存储了会话中用户选择引用的知识库ID。</p>
     *
     * @param ctx 运行时上下文
     * @return 会话级知识库ID列表，若不存在则返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<Long> resolveSessionKbIds(RuntimeContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        try {
            Object raw = ctx.get("aegis.sessionKbIds");
            if (raw == null) {
                return List.of();
            }
            if (raw instanceof List<?> list) {
                List<Long> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof Number n) {
                        result.add(n.longValue());
                    }
                }
                return result;
            }
            if (raw.getClass().isArray()) {
                Object[] arr = (Object[]) raw;
                List<Long> result = new ArrayList<>();
                for (Object item : arr) {
                    if (item instanceof Number n) {
                        result.add(n.longValue());
                    }
                }
                return result;
            }
            log.debug("aegis.sessionKbIds 类型不匹配: type={}", raw.getClass().getName());
        } catch (Exception e) {
            log.debug("解析会话级知识库ID失败: {}", e.getMessage());
        }
        return List.of();
    }
}
