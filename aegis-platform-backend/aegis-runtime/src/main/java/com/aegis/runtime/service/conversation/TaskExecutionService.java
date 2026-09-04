package com.aegis.runtime.service.conversation;

import com.aegis.core.common.tenant.TenantContextScope;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.dto.chat.ChatRequest;
import com.aegis.core.dto.chat.SkillRef;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.enums.session.SessionStatus;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.agent.AgentAssemblyService;
import com.aegis.runtime.service.policy.HitlFlowService;
import com.aegis.runtime.service.sandbox.SandboxSessionHolder;
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
 * 任务执行器：驱动 AgentScope {@link HarnessAgent} 流式输出，
 * 负责事件转换、输出累积、中断响应与终态委托。
 *
 * <p><b>职责边界</b>——以下能力由其他组件承担，本类不重复实现：
 * <ul>
 *   <li>请求校验 → {@link com.aegis.runtime.web.ChatRequestValidator}（Controller 层）</li>
 *   <li>智能体装配（模板/会话/工具/中间件） → {@link AgentAssemblyService}</li>
 *   <li>消息持久化 + 会话状态机 → {@link SessionProjectionService}</li>
 *   <li>HITL 审批状态管理 → {@link HitlFlowService}</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    /** 智能体装配：模板 → 会话 → 工具 → 中间件 → HarnessAgent */
    private final AgentAssemblyService assemblyService;
    /** 消息落库 + 会话状态机 */
    private final SessionProjectionService projectionService;
    /** AgentScope 事件 → Aegis 事件转换器 */
    private final HarnessEventConverter harnessEventConverter;
    /** 中断信号：用户中断 + Redis 跨实例 + 僵尸 sink 清理 */
    private final InterruptSignalManager interruptSignalManager;
    /** 技能仓库：解析显式 @SKILL 引用的激活/驳回结果 */
    private final AegisSkillRepository skillRepository;
    /** 会话管理：HITL 暂停时将会话置为 PAUSED */
    private final SessionManageService sessionManageService;
    /** HITL 审批状态（Redis 持久化 ConfirmResult） */
    private final HitlFlowService hitlFlowService;
    /** 会话级沙箱持有器：会话真正结束时释放沙箱实例 */
    private final SandboxSessionHolder sandboxSessionHolder;


    /** 可重试的网络异常类型集合 */
    private static final java.util.Set<Class<? extends Throwable>> NETWORK_EXCEPTIONS = Set.of(
            ConnectException.class,
            UnresolvedAddressException.class,
            UnknownHostException.class,
            SocketTimeoutException.class,
            TimeoutException.class
    );

    /** 最大网络重试次数 */
    private static final int MAX_NETWORK_RETRIES = 2;

    /** 重试间隔（毫秒） */
    private static final long RETRY_DELAY_MS = 1500;

    /** 判断异常链中是否包含网络相关异常（含异常类型匹配 + 消息关键词匹配）。 */
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

    /** 判断异常链中是否包含超时异常。 */
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
     * 任务执行入口：装配 → agent_start → 流式执行 → done。校验由 Controller 层完成。
     *
     * <p><b>线程模型</b>：装配在 {@code boundedElastic} 执行（含 DB I/O），流式阶段由
     * AgentScope 内核线程驱动。ThreadLocal 不跨线程，租户上下文用
     * {@link TenantContextScope} 在切换后手动恢复。
     *
     * @param request 对话请求（已通过校验）
     * @return SSE 事件流
     */
    public Flux<AgentEvent> execute(ChatRequest request) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        // 捕获 sessionId 供外层 doFinally 兜底使用（assemble 后才有值）
        final String[] sessionHolder = new String[1];
        // 标记是否发生过错误：外层 doFinally 据此决定终态为 EXCEPTION 还是 INTERRUPTED
        final boolean[] errored = {false};
        final Long tenantId = request.getTenantId();

        // 必须清理：boundedElastic 线程归还池后若残留租户，下一请求（可能属另一租户）
        // 会读到错误租户，导致 MyBatis-Plus 租户插件以错误身份读写数据
        return Mono.fromCallable(() -> {
                    try (var ignore = TenantContextScope.bound(tenantId)) {
                        AegisTaskContext ctx = assemblyService.assemble(request, taskId);
                        sessionHolder[0] = ctx.getSessionId();
                        return ctx;
                    }
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
                    errored[0] = true;
                    Map<String, Object> errorData = buildErrorData(e);
                    return Flux.just(
                            AgentEvent.of("error", errorData),
                            AgentEvent.of("done", Map.of()));
                })
                // 外层兜底：确保会话始终进入终态，杜绝异常分支永久卡在 THINKING/STARTED。
                // 不能按 signal != ON_COMPLETE 判断——onErrorResume 已把错误转成 ON_COMPLETE。
                // terminateIfActive 仅对活跃态生效，对已终态（ENDED/PAUSED/EXCEPTION/INTERRUPTED）
                // 的会话是幂等 no-op。
                .doFinally(signal -> {
                    try {
                        String sid = sessionHolder[0];
                        if (sid != null) {
                            log.info("外层 doFinally 兜底清理: sessionId={}, signal={}", sid, signal);
                            // CANCEL/ERROR：清理中断信号（正常完成不清理，避免误删新请求的 sink）
                            if (signal != SignalType.ON_COMPLETE) {
                                interruptSignalManager.forceUnregister(sid);
                            }
                            // 错误分支→EXCEPTION；取消/正常→INTERRUPTED（正常完成时为幂等 no-op）
                            SessionStatus terminal = errored[0]
                                    ? SessionStatus.EXCEPTION
                                    : SessionStatus.INTERRUPTED;
                            projectionService.onForceTerminate(sid, terminal);
                            // 兜底释放沙箱：内层 doFinally 已在终态路径释放，此处为幂等 no-op；
                            // 但 assemble 抛错等内层未触发的场景仍能正确释放
                            try {
                                sandboxSessionHolder.releaseOnSessionEnd(sid);
                            } catch (Exception sandEx) {
                                log.warn("外层 doFinally 兜底沙箱释放异常: sessionId={}, error={}",
                                        sid, sandEx.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        log.error("外层 doFinally 异常: signal={}", signal, e);
                    }
                });
    }

    /**
     * 流式执行核心：构造消息 → 注册中断信号 → 驱动 AgentScope 流 → 终态处理。
     *
     * <p><b>HITL 暂停与恢复</b>：中间件判定工具调用需用户审批（ASK 决策）时发出
     * {@code hitl.request} 事件，此处保存审批数据到 Redis 并置 {@code hitlPaused}，
     * 流终止后会话转 PAUSED。用户审批后由 {@link com.aegis.runtime.web.HitlController}
     * 再次调用 {@link #execute}，经 {@code HitlFlowService.buildResumeMessages} 注入
     * ConfirmResult 消息，恢复被中断的工具调用。
     *
     * @param ctx 任务执行上下文（含 HarnessAgent、RuntimeContext、用户消息等）
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

        // ====== 阶段 1：构造消息列表（HITL 恢复消息优先，其次用户消息） ======
        List<Msg> msgs = new ArrayList<>();
        String userMessage = ctx.getUserMessage();

        // 1. HITL 恢复：注入 Redis 中的 ConfirmResult。即使 userMessage 为空也必须注入，
        //    否则 AgentScope 无法恢复被中断的工具调用
        List<Msg> resumeMsgs = hitlFlowService.buildResumeMessages(ctx.getSessionId());
        if (!resumeMsgs.isEmpty()) {
            msgs.addAll(resumeMsgs);
            log.info("HITL 恢复：注入 ConfirmResult: sessionId={}, count={}, hasUserMsg={}",
                    ctx.getSessionId(), resumeMsgs.size(), userMessage != null && !userMessage.isEmpty());
        }

        // 2. 追加用户消息 — 含多模态图片时构造 UserMessage（TextBlock + ImageBlock）
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

        // 3. 异常兜底：两者皆空时仅告警，交给 AgentScope 处理
        if (msgs.isEmpty()) {
            log.warn("HITL 恢复：无 ConfirmResult 且无用户消息: sessionId={}", ctx.getSessionId());
        }

        // ====== 阶段 2：累积缓冲 + 中断信号注册 ======
        // outputBuffer    — 累积 text.delta（正式回复），供 onTerminate 落库
        // reasoningBuffer — 累积 reasoning.delta（思考过程），单独落库到 sess_message.reasoning
        // tokenStats      — [input, output]，用数组引用以跨事件共享
        StringBuilder outputBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        int[] tokenStats = {0, 0};

        log.info("HarnessAgent 执行: agentId={}, sessionId={}, agentType={}",
                ctx.getAgentId(), ctx.getSessionId(),
                ctx.getAgentDef() != null ? ctx.getAgentDef().getAgentType() : "UNKNOWN");

        // 注册中断信号 sink — 支持用户主动中断 + Redis 跨实例中断
        // 返回的 Registration 包含 sink 和唯一 registerId，用于精确清理（避免误删新请求的 sink）
        InterruptSignalManager.Registration registration =
                interruptSignalManager.register(ctx.getSessionId());
        Sinks.Many<InterruptSignalManager.InterruptSignal> interruptSink = registration.sink();

        // HITL 暂停标记：数组引用，跨事件在 processConvertedEvent 中共享
        // 当 hitl.request 事件到达时置为 true，doFinally 据此决定设置 PAUSED 还是 ENDED
        final boolean[] hitlPaused = {false};

        // ====== 阶段 3：构建 Flux 管道 ======
        return Flux.concat(
                // 主流：AgentScope 内核驱动 ReAct 循环（推理→工具调用→观察→再推理）
                // 中间件洋葱链在此流中执行：onAgent→onReasoning→onActing→onModelCall→onSystemPrompt
                agent.streamEvents(msgs, rc)
                        // 背压控制：缓冲 256 事件，溢出时丢弃最旧事件（保护内存）
                        .onBackpressureBuffer(256, BufferOverflowStrategy.DROP_OLDEST)
                        // 超时保护：5 分钟无事件则终止流
                        .timeout(Duration.ofMinutes(5))
                        // 网络抖动重试：仅对连接异常/超时/DNS 失败重试 2 次，指数退避 1.5s 起步
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
                        // 中断响应：用户点击"停止"时 interruptSink 发出信号，流被 takeUntilOther 终止
                        .takeUntilOther(interruptSink.asFlux())
                        // 事件转换：每个 AgentScope 事件 → 0~N 个 Aegis 事件
                        .concatMap(harnessEvent -> {
                            // convertMany 允许一个事件转换为多个事件，
                            // 典型场景：skill_creator 工具完成后附带发射 skill.* 编排事件
                            List<AgentEvent> convertedList = harnessEventConverter.convertMany(harnessEvent);
                            if (convertedList == null || convertedList.isEmpty()) {
                                return Mono.empty();
                            }
                            return Flux.fromIterable(convertedList)
                                    .concatMap(converted -> Mono.fromCallable(() -> {
                                        // 事件投影含 HITL Redis 同步读写 + 阻塞 JDBC，移出 AgentScope
                                        // 内核线程交给 boundedElastic，避免热路径阻塞；
                                        // concatMap 保证同一会话内事件顺序
                                        processConvertedEvent(converted, ctx, hitlPaused, tokenStats);
                                        return converted;
                                    }).subscribeOn(Schedulers.boundedElastic()));
                        })
                        // 流中副作用：累积输出 + 更新心跳（每个事件都执行）
                        .doOnNext(event -> {
                            accumulateOutput(event, outputBuffer);       // 累积 text.delta → outputBuffer
                            accumulateReasoning(event, reasoningBuffer); // 累积 reasoning.delta → reasoningBuffer
                            accumulateTokens(event, tokenStats, ctx);    // 累积 token 统计 + 回写 ctx
                            // 心跳更新：防止僵尸清理器因长时间无活跃而误杀（使用 registerId 精确匹配）
                            interruptSignalManager.touchHeartbeat(ctx.getSessionId(), registration.registerId());
                        })
                        // 过滤内部事件：task.status 是统计事件，不发给前端
                        .filter(event -> !"task.status".equals(event.getEvent()))
                        // 终态处理：无论 complete/cancel/error 都执行
                        .doFinally(signalType -> {
                            try {
                                if (hitlPaused[0]) {
                                    // HITL 暂停路径：会话置 PAUSED，等待用户审批。
                                    // 竞态保护：若审批端点已将会话置回 STARTED（用户已审批），跳过 PAUSED
                                    boolean alreadyApproved = hitlFlowService.isApproved(ctx.getSessionId());
                                    if (alreadyApproved) {
                                        log.info("HITL 暂停流结束但已审批通过，跳过 PAUSED 状态: sessionId={}", ctx.getSessionId());
                                    } else {
                                        log.info("HITL 暂停流结束，会话置为 PAUSED: sessionId={}", ctx.getSessionId());
                                        sessionManageService.updateStatus(ctx.getSessionId(),
                                                com.aegis.core.enums.session.SessionStatus.PAUSED);
                                        // 同步更新 statusCache，防止 onTerminate 因缓存仍为 ENDED 而覆盖 PAUSED
                                        projectionService.updateStatusCache(ctx.getSessionId(),
                                                com.aegis.core.enums.session.SessionStatus.PAUSED);
                                    }
                                } else {
                                    // 非 HITL 暂停路径：流正常完成或被中断。
                                    // 若用户已审批通过，ConfirmResult 已被 AgentScope 消费，
                                    // 清理 Redis 审批状态避免污染下一轮对话
                                    if (hitlFlowService.isApproved(ctx.getSessionId())) {
                                        log.info("清理已消费的 HITL 审批状态: sessionId={}", ctx.getSessionId());
                                        hitlFlowService.clearHitlState(ctx.getSessionId());
                                    }
                                    // 兜底：onActing 直接发起的 HITL 请求（未知/MCP 工具默认 ASK、
                                    // 通配符 HitlNode 命中）可能未途经流事件转换，未被
                                    // processConvertedEvent 捕获。此处统一落库 + 置 PAUSED，
                                    // 确保可审批、可恢复、不卡死。
                                    Map<String, Object> pendingHitl = ctx.takePendingHitlRequest();
                                    if (pendingHitl != null) {
                                        Object replyId = pendingHitl.get("replyId");
                                        Object toolCalls = pendingHitl.get("toolCalls");
                                        if (toolCalls instanceof List) {
                                            @SuppressWarnings("unchecked")
                                            List<Map<String, Object>> tcList =
                                                    (List<Map<String, Object>>) toolCalls;
                                            hitlFlowService.saveHitlRequest(ctx.getSessionId(),
                                                    replyId != null ? String.valueOf(replyId) : null, tcList);
                                        }
                                        log.info("HITL 兜底落库（onActing 直接发起）: sessionId={}, tool={}",
                                                ctx.getSessionId(), pendingHitl.get("toolName"));
                                        sessionManageService.updateStatus(ctx.getSessionId(),
                                                SessionStatus.PAUSED);
                                        projectionService.updateStatusCache(ctx.getSessionId(),
                                                SessionStatus.PAUSED);
                                    }
                                }
                                // 统一终态委托：助手消息落库 + 最终状态设置 + 缓存清理
                                log.info("HarnessAgent 流终止: sessionId={}, signal={}, outputLen={}, tokenIn={}, tokenOut={}, hitlPaused={}",
                                        ctx.getSessionId(), signalType, outputBuffer.length(),
                                        tokenStats[0], tokenStats[1], hitlPaused[0]);
                                String reasoningText = reasoningBuffer.length() > 0 ? reasoningBuffer.toString() : null;
                                projectionService.onTerminate(ctx, outputBuffer.toString(),
                                        reasoningText, tokenStats[0], tokenStats[1], signalType);
                                // 注销中断信号 sink（CAS 语义，避免误删新请求的 sink）
                                interruptSignalManager.unregister(ctx.getSessionId(), registration);
                                // 会话真正结束（非 HITL PAUSED）时释放沙箱实例
                                // （OCCUPIED→IDLE 复用不杀 Pod）；HITL 暂停时保留沙箱待审批后恢复
                                if (!hitlPaused[0]) {
                                    try {
                                        sandboxSessionHolder.releaseOnSessionEnd(ctx.getSessionId());
                                    } catch (Exception sandEx) {
                                        // 沙箱释放异常不影响会话生命周期（沙箱由 admin Reconcile 兜底清理）
                                        log.warn("streamExecution doFinally 沙箱释放异常: sessionId={}, error={}",
                                                ctx.getSessionId(), sandEx.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                log.error("streamExecution doFinally 异常: sessionId={}, signal={}",
                                        ctx.getSessionId(), signalType, e);
                            }
                        }),
                // 拦截检测流：主流结束后若中间件设置了 blocked=true（安全拦截/HITL ASK），
                // 补发一个 error 事件让前端可见（主流本身已 complete，需 concat 追加）
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
     * 处理单个已转换的 Aegis 事件的副作用（由 {@code streamExecution} 的 concatMap 逐事件调用）：
     * <ul>
     *   <li>{@code hitl.request} → 保存到 Redis + 设置 hitlPaused 标记</li>
     *   <li>非 {@code skill.*} 事件 → 委托 {@link SessionProjectionService#onEvent} 落库 + 状态流转</li>
     *   <li>{@code done}/{@code agent_end} → 注入 token 统计到事件数据</li>
     * </ul>
     *
     * @param converted    已转换的 Aegis 事件
     * @param ctx          任务上下文
     * @param hitlPaused   HITL 暂停标记（数组引用，跨事件共享，doFinally 读取）
     * @param tokenStats   Token 统计 [input, output]（数组引用，跨事件共享）
     * @return 包含事件的 Mono；事件为 null 时返回 empty
     */
    private Mono<AgentEvent> processConvertedEvent(AgentEvent converted,
                                                   AegisTaskContext ctx,
                                                   final boolean[] hitlPaused,
                                                   final int[] tokenStats) {
        if (converted == null) {
            return Mono.empty();
        }
        // 高频事件（text.delta 等）降噪为 DEBUG，避免逐条 INFO 淹没日志
        if (log.isDebugEnabled()) {
            log.debug("[TaskExecution] Event: type={}, data={}",
                    converted.getEvent(), converted.getData());
        }

        // HITL 审批请求：保存到 Redis 供后续恢复使用，并标记暂停
        if ("hitl.request".equals(converted.getEvent())) {
            if (converted.getData() instanceof Map<?, ?> dm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) dm;
                Object replyId = data.get("replyId");
                Object toolCalls = data.get("toolCalls");
                if (toolCalls instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> tcList = (List<Map<String, Object>>) toolCalls;

                    // 低风险自动放行：转换器判定全部工具无需审批（autoApproved=true）时，
                    // 不置 PAUSED、不透传前端弹窗；直接构造 ConfirmResult（全 approve），
                    // 下一轮对话经 buildResumeMessages 自然恢复执行
                    if (Boolean.TRUE.equals(data.get("autoApproved"))) {
                        hitlFlowService.saveHitlRequest(ctx.getSessionId(),
                                replyId != null ? String.valueOf(replyId) : null, tcList);
                        hitlFlowService.markApproved(ctx.getSessionId());
                        log.info("HITL 低风险自动放行（不弹审批卡）: sessionId={}, toolCount={}, maxRiskLevel={}",
                                ctx.getSessionId(), tcList.size(), data.get("maxRiskLevel"));
                        return Mono.empty();
                    }

                    hitlFlowService.saveHitlRequest(ctx.getSessionId(),
                            replyId != null ? String.valueOf(replyId) : null, tcList);
                }
            }
            hitlPaused[0] = true;
            log.info("HITL 审批请求到达，标记会话为 PAUSED: sessionId={}", ctx.getSessionId());
        }

        // skill.* 编排事件（skill_creator 附加事件）不涉及会话状态变更，跳过投影避免副作用
        String eventType = converted.getEvent();
        boolean isSkillCreatorEvent = eventType != null && eventType.startsWith("skill.");

        // 委托投影服务：工具消息落库 + 状态流转（tool.call→TOOL_CALLING, text.delta→OUTPUTTING, agent_end→ENDED 等）
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
     * 累积正式回复（仅 text.delta）。思考过程由 {@link #accumulateReasoning} 单独累积，不混入回复 content。
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
     * 累积思考过程（reasoning.delta），供 onTerminate 落库到 sess_message.reasoning。
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
     * 累积 Token 统计（仅处理含 tokenInput/tokenOutput 的 task.status 事件），
     * 同时回写 ctx 供中间件 postCall 读取累计值。
     */
    private void accumulateTokens(AgentEvent event, int[] tokenStats, AegisTaskContext ctx) {
        if ("task.status".equals(event.getEvent()) && event.getData() instanceof Map<?, ?> dm) {
            // 仅处理含 token 字段的 task.status（来自 convertModelCallEnd），其他 task.status 跳过
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

    /** 构建 agent_start 事件 — 告知前端智能体已启动（含 taskId/sessionId/agentId/模型/资源数）。 */
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
     * 构建 @SKILL 激活/驳回事件 — 对用户显式 {@code @} 选中的技能给出前端反馈。
     * 可见且有权限 → {@code skill.activated}，不可见/不存在/无权限 → {@code skill.rejected}。
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
     * 构建错误事件数据 — 根据异常类型映射到具体错误码和用户友好消息。
     * 错误码：CONFLICT / QUOTA_EXCEEDED / SERVICE_UNAVAILABLE / GATEWAY_TIMEOUT / HITL_PENDING / INTERNAL_ERROR。
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
