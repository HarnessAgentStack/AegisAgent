package com.aegis.runtime.service.conversation;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.dto.chat.ChatRequest;
import com.aegis.core.dto.chat.SkillRef;
import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.agent.AgentAssemblyService;
import com.aegis.runtime.service.policy.HitlFlowService;
import com.aegis.runtime.integration.agent.HarnessEventConverter;
import com.aegis.runtime.integration.skill.AegisSkillRepository;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import reactor.util.retry.Retry;

/**
 * 任务执行领域服务（优化版）。
 *
 * <p>纯执行器：接收已装配的 {@link AegisTaskContext}，驱动 {@link HarnessAgent} 流式输出。
 * 校验由 {@link com.aegis.runtime.web.ChatRequestValidator} 完成，
 * 装配由 {@link AgentAssemblyService} 完成，
 * 持久化与状态管理由 {@link SessionProjectionService} 完成。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private final AgentAssemblyService assemblyService;
    private final SessionProjectionService projectionService;
    private final HarnessEventConverter harnessEventConverter;
    private final InterruptSignalManager interruptSignalManager;
    /** 技能仓库，用于计算显式引用的激活/驳回结果并下发 SSE 事件 */
    private final AegisSkillRepository skillRepository;
    /** 用于将会话置为 PAUSED 状态 */
    private final SessionManageService sessionManageService;
    /** 用于加载审批通过后的 ConfirmResult */
    private final HitlFlowService hitlFlowService;


    /** 网络相关异常类型集合，用于判断是否可重试 */
    private static final java.util.Set<Class<? extends Throwable>> NETWORK_EXCEPTIONS = Set.of(
            ConnectException.class,
            UnresolvedAddressException.class,
            UnknownHostException.class,
            SocketTimeoutException.class,
            TimeoutException.class
    );

    /** 最大网络重试次数（模型调用层） */
    private static final int MAX_NETWORK_RETRIES = 2;

    /** 重试间隔（毫秒） */
    private static final long RETRY_DELAY_MS = 1500;

    /**
     * 判断异常链中是否包含网络相关异常。
     */
    private static boolean isNetworkError(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            for (Class<? extends Throwable> netEx : NETWORK_EXCEPTIONS) {
                if (netEx.isInstance(cur)) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("connectexception")
                    || lower.contains("unresolvedaddress")
                    || lower.contains("unknownhost")
                    || lower.contains("connection refused")
                    || lower.contains("connection reset")
                    || lower.contains("no such host")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断异常链中是否包含超时异常。
     */
    private static boolean isTimeoutError(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof TimeoutException || cur instanceof SocketTimeoutException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 执行任务并返回 SSE 事件流。
     *
     * <p>编排流程：装配 -> agent_start -> 流式执行 -> done。
     * 校验由 Controller 调用 {@link com.aegis.runtime.web.ChatRequestValidator} 完成。
     *
     * @param request 对话请求（已通过校验）
     * @return SSE 事件流（reactive）
     */
    public Flux<AgentEvent> execute(ChatRequest request) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        // 捕获 sessionId 供外层 doFinally 兜底使用（assemble 后才有值）
        final String[] sessionHolder = new String[1];
        // 捕获当前线程租户上下文，在 Reactor 线程切换后恢复
        final Long tenantId = request.getTenantId();

        return Mono.fromCallable(() -> {
                    // 恢复租户上下文（Reactor 切换线程后 ThreadLocal 丢失）
                    TenantContextHolder.bind(tenantId);
                    AegisTaskContext ctx = assemblyService.assemble(request, taskId);
                    sessionHolder[0] = ctx.getSessionId();
                    return ctx;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ctx -> {
                    if (ctx.isBlocked()) {
                        return Flux.just(
                                AgentEvent.of("error", Map.of(
                                        "code", "BLOCKED",
                                        "message", ctx.getBlockReason())),
                                AgentEvent.of("done", Map.of()));
                    }

                    Flux<AgentEvent> startFlux = Flux.concat(
                            Flux.just(buildStartEvent(ctx)),
                            buildSkillEvents(ctx));
                    Flux<AgentEvent> streamFlux = streamExecution(ctx);
                    Flux<AgentEvent> doneFlux = Flux.just(AgentEvent.of("done", Map.of()));

                    return Flux.concat(startFlux, streamFlux, doneFlux);
                })
                .onErrorResume(e -> {
                    log.error("TaskExecution error: taskId={}", taskId, e);
                    Map<String, Object> errorData = buildErrorData(e);
                    return Flux.just(
                            AgentEvent.of("error", errorData),
                            AgentEvent.of("done", Map.of()));
                })
                .doFinally(signal -> {
                    try {
                        String sid = sessionHolder[0];
                        if (sid != null) {
                            log.info("外层 doFinally 兜底清理: sessionId={}, signal={}", sid, signal);
                            if (signal != SignalType.ON_COMPLETE) {
                                interruptSignalManager.forceUnregister(sid);
                                projectionService.onForceTerminate(sid);
                            }
                        }
                    } catch (Exception e) {
                        log.error("外层 doFinally 异常: signal={}", signal, e);
                    }
                });
    }

    /**
     * 流式执行核心。
     *
     * <p>仅负责：事件转换 + 输出累积 + Token 统计 + 中断响应 + 终态投影委托。
     * 状态管理和消息持久化委托给 {@link SessionProjectionService}。
     *
     * <p>当事件流中出现 {@code hitl.request} 事件时，
     * 标记 session 为 PAUSED 状态，中断后续执行，等待用户审批后由
     * {@link com.aegis.runtime.web.HitlController} 恢复。
     *
     * @param ctx 任务执行上下文
     */
    private Flux<AgentEvent> streamExecution(AegisTaskContext ctx) {
        HarnessAgent agent = ctx.getAgent();
        if (agent == null) {
            log.error("HarnessAgent 不可用: agentId={}", ctx.getAgentId());
            return Flux.just(AgentEvent.of("error", Map.of(
                    "code", "HARNESS_UNAVAILABLE",
                    "message", "运行时内核不可用")));
        }

        RuntimeContext rc = ctx.getRuntimeContext();
        // 通过 AgentScope 原生 RuntimeContext 类型化单例透传 AegisTaskContext，
        // 中间件通过 rc.get(AegisTaskContext.class) 取回。
        rc.put(AegisTaskContext.class, ctx);

        // HITL 恢复场景：始终检查 Redis 中的待审批 ConfirmResult，注入恢复消息
        // 关键修复：无论 userMessage 是否为空，都必须注入 ConfirmResult，
        // 否则 AgentScope 无法恢复被中断的工具调用，HITL 审批流会失效
        List<Msg> msgs = new ArrayList<>();
        String userMessage = ctx.getUserMessage();

        // 1. 优先检查并注入待审批的 HITL ConfirmResult
        List<Msg> resumeMsgs = hitlFlowService.buildResuxmeMessages(ctx.getSessionId());
        if (!resumeMsgs.isEmpty()) {
            msgs.addAll(resumeMsgs);
            // 双审批修复：把已审批工具名标记到新 TaskContext.approvedTools，
            // 使第二轮 onActing 通过 isToolApproved 直接放行，避免通配符 HITL 规则重复触发 ASK。
            // AS ConfirmResult 规则学习只覆盖 loadHitlRules 注入的明确 toolName ASK 规则，
            // 不覆盖 onActing 中间件层手动检查的通配符规则，故需在此补充标记。
            List<String> approvedToolNames = hitlFlowService.listApprovedToolNames(ctx.getSessionId());
            for (String tn : approvedToolNames) {
                ctx.markToolApproved(tn,
                        PolicyDecision.allow(null, "hitl-resume-approved", null, null));
            }
            if (!approvedToolNames.isEmpty()) {
                log.info("HITL 恢复：标记已审批工具免重复审批: sessionId={}, tools={}",
                        ctx.getSessionId(), approvedToolNames);
            }
            log.info("HITL 恢复：注入 ConfirmResult: sessionId={}, count={}, hasUserMsg={}",
                    ctx.getSessionId(), resumeMsgs.size(), userMessage != null && !userMessage.isEmpty());
        }

        // 2. 追加用户消息（如果存在）—含 NATIVE_PASS 图片时构造多模态 UserMessage
        if (userMessage != null && !userMessage.isEmpty()) {
            List<ContentBlock> imageBlocks = ctx.getMultimodalBlocks();
            if (imageBlocks != null && !imageBlocks.isEmpty()) {
                List<ContentBlock> allBlocks = new ArrayList<>();
                allBlocks.add(TextBlock.builder().text(userMessage).build());
                allBlocks.addAll(imageBlocks);
                msgs.add(new UserMessage(allBlocks));
            } else {
                msgs.add(new UserMessage(userMessage));
            }
        }

        // 3. 无任何消息时的兜底：返回空消息列表，由 AgentScope 处理
        if (msgs.isEmpty()) {
            log.warn("HITL 恢复：无 ConfirmResult 且无用户消息: sessionId={}", ctx.getSessionId());
        }
        
        // 累积输出文本与 Token 统计
        StringBuilder outputBuffer = new StringBuilder();
        // P2-TBL：累积 reasoning.delta（思考过程），供 onTerminate 落库到 sess_message.reasoning 字段
        StringBuilder reasoningBuffer = new StringBuilder();
        int[] tokenStats = {0, 0};  // [inputTokens, outputTokens]

        log.info("HarnessAgent 执行: agentId={}, sessionId={}, agentType={}",
                ctx.getAgentId(), ctx.getSessionId(),
                ctx.getAgentDef() != null ? ctx.getAgentDef().getAgentType() : "UNKNOWN");

        // 注册中断信号 sink（返回 Registration 包含 sink 和唯一注册ID）
        InterruptSignalManager.Registration registration =
                interruptSignalManager.register(ctx.getSessionId());
        Sinks.Many<InterruptSignalManager.InterruptSignal> interruptSink = registration.sink();

        // 检测 hitl.request 事件后暂停执行流
        final boolean[] hitlPaused = {false};

        return Flux.concat(
                agent.streamEvents(msgs, rc)
                        .onBackpressureBuffer(256, BufferOverflowStrategy.DROP_OLDEST)
                        .timeout(Duration.ofMinutes(5))
                        .retryWhen(Retry.backoff(MAX_NETWORK_RETRIES, Duration.ofMillis(RETRY_DELAY_MS))
                                .filter(throwable -> isNetworkError(throwable))
                                .onRetryExhaustedThrow((spec, retrySignal) -> {
                                    log.warn("网络重试已耗尽（{}次），返回错误给用户", MAX_NETWORK_RETRIES);
                                    return retrySignal.failure();
                                })
                                .doBeforeRetry(signal ->
                                        log.warn("网络异常重试 {}/{}: {}",
                                                signal.totalRetries() + 1, MAX_NETWORK_RETRIES,
                                                signal.failure().getMessage())))
                        .takeUntilOther(interruptSink.asFlux())
                        .concatMap(harnessEvent -> {
                            // 使用 convertMany：允许一个 AgentScope 事件转换为多个 Aegis 事件
                            // 典型场景：skill_creator 工具执行完成后，附带发射多个 skill.* 编排事件
                            List<AgentEvent> convertedList = harnessEventConverter.convertMany(harnessEvent);
                            if (convertedList == null || convertedList.isEmpty()) {
                                return Mono.empty();
                            }
                            return Flux.fromIterable(convertedList)
                                    .concatMap(converted -> processConvertedEvent(converted, ctx,
                                            hitlPaused, tokenStats));
                        })
                        .doOnNext(event -> {
                            accumulateOutput(event, outputBuffer);
                            accumulateReasoning(event, reasoningBuffer);
                            accumulateTokens(event, tokenStats, ctx);
                            // 更新 sink 心跳，防止定时清理器误判为僵尸（使用 registerId）
                            interruptSignalManager.touchHeartbeat(ctx.getSessionId(), registration.registerId());
                        })
                        // 过滤掉内部统计事件 task.status
                        .filter(event -> !"task.status".equals(event.getEvent()))
                        .doFinally(signalType -> {
                            try {
                                // P2 HITL 启用：若因 hitl.request 暂停，将 session 置为 PAUSED
                                // 竞态修复：仅当未被审批端点标记为 STARTED 时才设置 PAUSED
                                if (hitlPaused[0]) {
                                    boolean alreadyApproved = hitlFlowService.isApproved(ctx.getSessionId());
                                    if (alreadyApproved) {
                                        log.info("HITL 暂停流结束但已审批通过，跳过 PAUSED 状态: sessionId={}", ctx.getSessionId());
                                    } else {
                                        log.info("HITL 暂停流结束，会话置为 PAUSED: sessionId={}", ctx.getSessionId());
                                        sessionManageService.updateStatus(ctx.getSessionId(),
                                                com.aegis.core.enums.session.SessionStatus.PAUSED);
                                        // 竞态修复：同步更新 statusCache，防止 onTerminate 因缓存仍为 ENDED 而覆盖 PAUSED
                                        projectionService.updateStatusCache(ctx.getSessionId(),
                                                com.aegis.core.enums.session.SessionStatus.PAUSED);
                                    }
                                } else {
                                    // 非 HITL 暂停流：检查并清理已消费的 HITL 审批状态
                                    // 当用户已审批通过且流正常完成时，ConfirmResult 已被 AgentScope 消费，
                                    // 需清理 Redis 中的审批状态，避免残留状态污染下一轮对话
                                    if (hitlFlowService.isApproved(ctx.getSessionId())) {
                                        log.info("清理已消费的 HITL 审批状态: sessionId={}", ctx.getSessionId());
                                        hitlFlowService.clearHitlState(ctx.getSessionId());
                                    }
                                }
                                // 统一终态处理：无论 complete/cancel/error 都走同一逻辑
                                log.info("HarnessAgent 流终止: sessionId={}, signal={}, outputLen={}, tokenIn={}, tokenOut={}, hitlPaused={}",
                                        ctx.getSessionId(), signalType, outputBuffer.length(),
                                        tokenStats[0], tokenStats[1], hitlPaused[0]);
                                String reasoningText = reasoningBuffer.length() > 0 ? reasoningBuffer.toString() : null;
                                projectionService.onTerminate(ctx, outputBuffer.toString(),
                                        reasoningText, tokenStats[0], tokenStats[1], signalType);
                                interruptSignalManager.unregister(ctx.getSessionId(), registration);
                            } catch (Exception e) {
                                log.error("streamExecution doFinally 异常: sessionId={}, signal={}",
                                        ctx.getSessionId(), signalType, e);
                            }
                        }),
                // 中间件拦截检测：流结束后若 blocked=true，发送 error 事件让前端可见
                Flux.defer(() -> {
                    if (ctx.isBlocked()) {
                        String reason = ctx.getBlockReason() != null ? ctx.getBlockReason() : "执行被中间件拦截";
                        log.warn("Middleware blocked: agentId={}, reason={}", ctx.getAgentId(), reason);
                        return Flux.just(AgentEvent.of("error", Map.of(
                                "code", "BLOCKED",
                                "message", reason)));
                    }
                    return Flux.empty();
                })
        );
    }

    /**
     * 处理单个已转换的 Aegis AgentEvent，执行副作用（HITL、投影、token 统计等）。
     *
     * <p>由 {@code streamExecution} 的 concatMap 调用，支持一个 AgentScope 事件
     * 转换为多个 Aegis 事件的场景（skill_creator 编排附加事件）。
     *
     * @param converted    已转换的 Aegis 事件
     * @param ctx          任务上下文
     * @param hitlPaused   HITL 暂停标记（数组引用，跨事件共享）
     * @param tokenStats   Token 统计（[input, output]，数组引用，跨事件共享）
     * @return 包含事件的 Mono；若事件为 null 则返回 empty
     */
    private Mono<AgentEvent> processConvertedEvent(AgentEvent converted,
                                                   AegisTaskContext ctx,
                                                   final boolean[] hitlPaused,
                                                   final int[] tokenStats) {
        if (converted == null) {
            return Mono.empty();
        }
        log.info("========== [TaskExecution] Event: type={}, data={} ==========",
                converted.getEvent(), converted.getData());

        // 所有 hitl.request 事件统一走正常 HITL 审批流程（保存请求 → PAUSED → 等待用户审批）。
        if ("hitl.request".equals(converted.getEvent())) {
            // 保存 HITL 请求数据到 Redis（供后续 markApproved 构造 ConfirmResult）
            if (converted.getData() instanceof Map<?, ?> dm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) dm;
                Object replyId = data.get("replyId");
                Object toolCalls = data.get("toolCalls");
                if (toolCalls instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tcList = (List<Map<String, Object>>) toolCalls;
                    hitlFlowService.saveHitlRequest(ctx.getSessionId(),
                            replyId != null ? String.valueOf(replyId) : null, tcList);
                }
            }
            hitlPaused[0] = true;
            log.info("HITL 审批请求到达，标记会话为 PAUSED: sessionId={}", ctx.getSessionId());
        }

        // skill_creator 编排事件不涉及会话状态变更，无需调用 projectionService.onEvent，避免对状态机造成副作用
        String eventType = converted.getEvent();
        boolean isSkillCreatorEvent = eventType != null && eventType.startsWith("skill.");

        // 委托投影服务处理副作用（工具消息落库 + 状态流转）
        // 注意：updateStatusByEvent 会将 hitl.request 设为 PAUSED
        if (!isSkillCreatorEvent) {
            projectionService.onEvent(ctx, converted);
        }
        // 在 done / agent_end 事件中注入 token 统计
        if (("done".equals(converted.getEvent()) || "agent_end".equals(converted.getEvent()))
                && converted.getData() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) converted.getData();
            data.put("tokenInput", tokenStats[0]);
            data.put("tokenOutput", tokenStats[1]);
        }
        return Mono.just(converted);
    }

    /**
     * 累积输出文本。
     *
     * <p>仅累积 text.delta（正式回复），排除 reasoning.delta（思考过程），
     * 思考过程不应混入助手回复的 content 字段。
     */
    private void accumulateOutput(AgentEvent event, StringBuilder outputBuffer) {
        String eventType = event.getEvent();
        if ("text.delta".equals(eventType) && event.getData() instanceof Map<?, ?> dm) {
            Object delta = dm.get("delta");
            if (delta != null) {
                outputBuffer.append(delta);
            }
        }
    }

    /**
     * 累积推理过程（reasoning.delta）。
     *
     * <p>P2-TBL：思考过程累积后供 onTerminate 落库到 sess_message.reasoning 字段（原 0 填充）。
     * 与 outputBuffer 分离，避免思考过程混入助手回复 content。
     */
    private void accumulateReasoning(AgentEvent event, StringBuilder reasoningBuffer) {
        String eventType = event.getEvent();
        if ("reasoning.delta".equals(eventType) && event.getData() instanceof Map<?, ?> dm) {
            Object delta = dm.get("delta");
            if (delta != null) {
                reasoningBuffer.append(delta);
            }
        }
    }

    /**
     * 累积 Token usage 并回写 ctx（供中间件 postCall 读取）。
     *
     * <p>需匹配 {@code "task.status"} 事件并校验数据中包含 tokenInput/tokenOutput 字段，
     * 避免误处理其他 task.status 事件。</p>
     */
    private void accumulateTokens(AgentEvent event, int[] tokenStats, AegisTaskContext ctx) {
        if ("task.status".equals(event.getEvent()) && event.getData() instanceof Map<?, ?> dm) {
            // 仅处理包含 token 统计的 task.status 事件（来自 convertModelCallEnd）
            // 其他 task.status 事件（如 createTaskStatusEvent）不含 token 字段
            if (dm.containsKey("tokenInput") || dm.containsKey("tokenOutput")) {
                Object input = dm.get("tokenInput");
                Object output = dm.get("tokenOutput");
                if (input instanceof Number n) tokenStats[0] += n.intValue();
                if (output instanceof Number n) tokenStats[1] += n.intValue();
                if (ctx != null) {
                    ctx.setTokenInput(tokenStats[0]);
                    ctx.setTokenOutput(tokenStats[1]);
                }
            }
        }
    }

    /**
     * 构建 agent_start 事件。
     */
    private AgentEvent buildStartEvent(AegisTaskContext ctx) {
        AgentDef def = ctx.getAgentDef();
        AgentConfig cfg = ctx.getAgentConfig();
        int resourceCount = ctx.getBindings() != null
                ? (int) ctx.getBindings().stream().filter(b -> b.getEnabled() != null && b.getEnabled()).count()
                : 0;
        Map<String, Object> data = Map.of(
                "taskId", ctx.getTaskId(),
                "sessionId", ctx.getSessionId(),
                "agentId", ctx.getAgentId(),
                "agentName", def != null ? def.getAgentName() : "",
                "agentVersion", ctx.getAgentVersion() != null ? ctx.getAgentVersion() : "",
                "model", cfg != null && cfg.getModelTier() != null ? cfg.getModelTier().name() : "STANDARD",
                "resourceCount", resourceCount,
                "timestamp", System.currentTimeMillis()
        );
        return AgentEvent.of("agent_start", data);
    }

    /**
     * 构建 @SKILL 激活/驳回事件。
     *
     * <p>复用 {@link AegisSkillRepository#resolve(RuntimeContext)} 的单一数据源，
     * 对用户显式 {@code @} 选中的技能给出反馈：可见且有权限的发出 {@code skill.activated}，
     * 不可见/不存在/无权限的发出 {@code skill.rejected}。
     * 无显式引用时不发事件，避免噪音。
     */
    private Flux<AgentEvent> buildSkillEvents(AegisTaskContext ctx) {
        List<SkillRef> requested = ctx.getRequestedSkills();
        if (requested == null || requested.isEmpty()) {
            return Flux.empty();
        }
        RuntimeContext rc = ctx.getRuntimeContext();
        AegisSkillRepository.SkillResolution resolution =
                (rc != null) ? skillRepository.resolve(rc) : new AegisSkillRepository.SkillResolution(List.of(), List.of());
        List<String> rejected = resolution.rejectedCodes() == null ? List.of() : resolution.rejectedCodes();
        List<String> activated = requested.stream()
                .map(SkillRef::getSkillCode)
                .filter(code -> code != null && !code.isBlank() && !rejected.contains(code))
                .toList();

        List<AgentEvent> events = new ArrayList<>();
        if (!activated.isEmpty()) {
            events.add(AgentEvent.of("skill.activated", Map.of(
                    "skills", activated,
                    "timestamp", System.currentTimeMillis())));
        }
        if (!rejected.isEmpty()) {
            events.add(AgentEvent.of("skill.rejected", Map.of(
                    "skills", rejected,
                    "reason", "技能不可见、不存在或无权限",
                    "timestamp", System.currentTimeMillis())));
        }
        return Flux.fromIterable(events);
    }

    /**
     * 构建错误事件数据（根据异常类型映射到具体错误码和用户友好消息）。
     *
     * <p>支持的错误码：
     * <ul>
     *   <li>CONFLICT (409)：序号冲突/状态冲突，建议重试</li>
     *   <li>QUOTA_EXCEEDED (429)：配额超限，联系管理员扩容</li>
     *   <li>SERVICE_UNAVAILABLE (503)：依赖服务不可用，稍后重试</li>
     *   <li>GATEWAY_TIMEOUT (504)：模型调用超时，稍后重试</li>
     *   <li>INTERNAL_ERROR (500)：未知内部错误</li>
     * </ul>
     */
    private Map<String, Object> buildErrorData(Throwable e) {
        if (e instanceof BusinessException be) {
            ResultCode code = be.getResultCode();
            String userMessage = switch (code) {
                case CONFLICT -> "会话数据冲突，请刷新页面重试（错误代码：SEQ_CONFLICT）";
                case QUOTA_EXCEEDED -> "智能体调用配额已用尽，请联系管理员扩容（错误代码：QUOTA_EXCEEDED）";
                case SERVICE_UNAVAILABLE -> "AI 模型服务暂时不可用，请稍后重试（错误代码：SERVICE_UNAVAILABLE）";
                case GATEWAY_TIMEOUT -> "AI 模型响应超时，请稍后重试（错误代码：GATEWAY_TIMEOUT）";
                case NOT_FOUND -> "请求的资源不存在，请检查智能体配置（错误代码：NOT_FOUND）";
                case FORBIDDEN -> "无权限执行此操作，请联系管理员（错误代码：FORBIDDEN）";
                case UNAUTHORIZED -> "登录状态已过期，请重新登录（错误代码：UNAUTHORIZED）";
                case HITL_PENDING -> "智能体已暂停，等待您确认操作（错误代码：HITL_PENDING）";
                default -> be.getMessage() != null ? be.getMessage() : "业务处理失败，请稍后重试";
            };
            return Map.of(
                    "code", code.name(),
                    "message", userMessage,
                    "detail", be.getMessage() != null ? be.getMessage() : "",
                    "timestamp", System.currentTimeMillis());
        }

        // Check for HITL paused state from AgentScope SDK
        String msg = e.getMessage();
        if (msg != null && msg.contains("is paused for human-in-the-loop confirmation")) {
            return Map.of(
                    "code", "HITL_PENDING",
                    "message", "智能体需要您确认工具调用，请在新的对话中继续（错误代码：HITL_PENDING）",
                    "detail", msg,
                    "timestamp", System.currentTimeMillis());
        }

        Throwable cause = e.getCause();
        if (cause != null) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null && causeMsg.contains("is paused for human-in-the-loop confirmation")) {
                return Map.of(
                        "code", "HITL_PENDING",
                        "message", "智能体需要您确认工具调用，请在新的对话中继续（错误代码：HITL_PENDING）",
                        "detail", causeMsg,
                        "timestamp", System.currentTimeMillis());
            }
            if (cause instanceof BusinessException be) {
                return buildErrorData(be);
            }
        }

        // 网络异常识别：DNS解析失败、连接拒绝、超时等
        if (isNetworkError(e)) {
            log.warn("检测到网络异常: {}", e.getMessage());
            String userMessage;
            String code;
            if (isTimeoutError(e)) {
                code = "GATEWAY_TIMEOUT";
                userMessage = "AI 模型响应超时，可能是网络不稳定，请稍后重试（错误代码：GATEWAY_TIMEOUT）";
            } else {
                code = "SERVICE_UNAVAILABLE";
                userMessage = "AI 模型服务网络连接异常，可能是DNS解析失败或网络不稳定，请稍后重试（错误代码：SERVICE_UNAVAILABLE）";
            }
            return Map.of(
                    "code", code,
                    "message", userMessage,
                    "detail", msg != null ? msg : "网络连接失败",
                    "timestamp", System.currentTimeMillis(),
                    "recoverable", true);
        }

        String finalMsg = msg != null ? msg : "内部错误";
        return Map.of(
                "code", "INTERNAL_ERROR",
                "message", "系统开小差了，请稍后重试（错误代码：INTERNAL_ERROR）",
                "detail", finalMsg,
                "timestamp", System.currentTimeMillis());
    }

}
