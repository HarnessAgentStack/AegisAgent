package com.aegis.runtime.integration.middleware;

import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.integration.observe.TraceSpanRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 链路追踪中间件 — 零侵入实现。
 *
 * <p>通过 AgentScope 3 个拦截点完成全链路追踪，将追踪逻辑从 {@code TaskExecutionService}
 * 迁移至中间件层，彻底消除业务代码侵入。
 *
 * <h3>拦截点职责</h3>
 * <ul>
 *   <li>{@code onAgent}(order=10)：Trace 生命周期管理 — preCall 开启 Trace，doFinally 结束 Trace</li>
 *   <li>{@code onModelCall}(order=10)：LLM 调用 Span — 直接从 {@link ModelCallInput} 原生 API
 *       获取 messages/tools/model，弃用反射提取，确保入参 100% 完整</li>
 *   <li>{@code onActing}(order=10)：工具调用 Span — 从 {@link ActingInput} 原生 API
 *       获取 toolCalls，累积流式参数和结果</li>
 * </ul>
 *
 * <p><b>入参修复核心</b>：{@link ModelCallInput#messages()} 返回 {@code List<Msg>}，
 * 直接调用原生 API 即可获取完整消息列表，无需反射。同理 {@link ModelCallInput#tools()}
 * 返回 {@code List<ToolSchema>}，{@link ModelCallInput#model()} 返回 {@link Model}。
 *
 * <p><b>出参修复核心</b>：在 {@code onModelCall} 返回的 Flux 上挂 {@code doOnNext}，
 * 监听 {@code TEXT_BLOCK_DELTA} 事件累积输出文本，在 {@code MODEL_CALL_END} 事件
 * 获取 token usage，最终在 {@code doFinally} 记录完整 Span。
 *
 * <h3>执行顺序</h3>
 * <p>order=10，中间件链最内层（最后执行 preCall、最先执行 postCall），
 * 包裹全部业务中间件，确保追踪覆盖完整执行链路。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisTraceMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final TraceSpanRecorder recorder;
    private final ObjectMapper objectMapper;

    /** 模型调用输出文本累积缓冲：replyId → StringBuilder */
    private final Map<String, StringBuilder> modelOutputBuffer = new ConcurrentHashMap<>();

    /** 模型调用 token 统计缓冲：modelCallKey → [inputTokens, outputTokens] */
    private final Map<String, int[]> modelTokenUsage = new ConcurrentHashMap<>();

    /** Trace 级 token 累积：traceId → [totalInputTokens, totalOutputTokens] */
    private final Map<String, int[]> traceTokenStats = new ConcurrentHashMap<>();

    /** 模型调用开始时间：replyId → 开始时间戳 */
    private final Map<String, Long> modelCallStartTimes = new ConcurrentHashMap<>();

    /** 工具调用开始时间：toolCallId → 开始时间戳 */
    private final Map<String, Long> toolCallStartTimes = new ConcurrentHashMap<>();

    /** 工具调用参数累积缓冲：toolCallId → 参数 JSON */
    private final Map<String, StringBuilder> toolArgsBuffer = new ConcurrentHashMap<>();

    /** 工具结果文本累积缓冲：toolCallId → 结果文本 */
    private final Map<String, StringBuilder> toolResultTextBuffer = new ConcurrentHashMap<>();

    /** 工具执行状态：toolCallId → 终态（SUCCESS/ERROR） */
    private final Map<String, String> toolStatusMap = new ConcurrentHashMap<>();

    /** 工具调用名称：toolCallId → 工具名 */
    private final Map<String, String> toolNameMap = new ConcurrentHashMap<>();

    @Override
    public int order() {
        // P2-5：order 10→95，外移至仅次于 Security(90) 的外层，使 RAG(65)/Intent(67)/ContentFilter(60)/
        // Security onAgent(90) 耗时均落入 trace span。原 order=10 处于洋葱最内层，仅包裹 agent 执行核心，
        // 安全/RAG/意图等中间件耗时不被追踪。
        // 注意：TraceSpanRecorder.startTrace 已做幂等（traceId 存在性检查），外移后同一 trace 跨更多中间件，
        // 需回归 span 父子关系与 DuplicateKeyException 兜底（MysqlTraceStore 已处理）。
        // 与 AegisMemory(10)/AegisMask(10) 不再并列，消除原 3-way 顺序歧义。
        return 95;
    }

    // ==================== onAgent: Trace 生命周期 ====================

    /**
     * Trace 生命周期管理。
     *
     * <p>preCall（next 之前）开启 Trace，doFinally 结束 Trace。
     * Trace 记录失败不阻塞主流程。
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc,
                                    AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            log.debug("AegisTraceMiddleware: AegisTaskContext not found in RuntimeContext, skipping trace");
            return next.apply(input);
        }

        String traceId = taskCtx.getTaskId();
        Long agentId = taskCtx.getAgentId();
        String agentName = taskCtx.getAgentDef() != null ? taskCtx.getAgentDef().getAgentName() : "";
        Long userId = taskCtx.getUserId();
        Long tenantId = taskCtx.getTenantId();
        String sessionId = taskCtx.getSessionId();

        // 开启 Trace（从 AegisTaskContext 获取已有 sessionId 与 userName）
        try {
            recorder.startTrace(traceId, sessionId, agentId, agentName,
                    userId, taskCtx.getUserName(), tenantId, "/api/runtime/task/chat");
        } catch (Exception e) {
            log.warn("AegisTraceMiddleware: failed to start trace: {}", e.getMessage());
        }

        // 记录 AGENT_ASSEMBLY Span（装配阶段耗时极短，此处仅标记入口）
        try {
            recorder.recordSpan(traceId, "AGENT_ASSEMBLY", "agent_assembly", "SUCCESS",
                    LocalDateTime.now(), LocalDateTime.now(), 0L,
                    String.format("{\"agentId\":%s}", agentId),
                    null, null, null, null, null);
        } catch (Exception e) {
            log.debug("AegisTraceMiddleware: failed to record assembly span: {}", e.getMessage());
        }

        return next.apply(input)
                .doFinally(signal -> {
                    // 结束 Trace（带 trace 级 token 汇总）
                    try {
                        String status = (signal == SignalType.ON_COMPLETE) ? "SUCCESS" : "FAILED";
                        int[] tokens = traceTokenStats.remove(traceId);
                        int totalIn = (tokens != null) ? tokens[0] : 0;
                        int totalOut = (tokens != null) ? tokens[1] : 0;
                        recorder.endTrace(traceId, status, null, totalIn, totalOut, 0);
                    } catch (Exception e) {
                        log.warn("AegisTraceMiddleware: failed to end trace: {}", e.getMessage());
                    }
                    // 清理缓冲
                    modelOutputBuffer.clear();
                    modelTokenUsage.clear();
                    traceTokenStats.clear();
                    modelCallStartTimes.clear();
                    toolCallStartTimes.clear();
                    toolArgsBuffer.clear();
                    toolResultTextBuffer.clear();
                    toolStatusMap.clear();
                    toolNameMap.clear();
                });
    }

    // ==================== onModelCall: LLM 调用 Span ====================

    /**
     * 记录 LLM 调用 Span。
     *
     * <p><b>关键改进</b>：直接从 {@link ModelCallInput} 原生 API 获取 messages/tools/model，
     * 完全弃用反射提取，确保入参 100% 完整。
     *
     * <p>出参通过 doOnNext 监听 TEXT_BLOCK_DELTA 累积，在 MODEL_CALL_END 获取 token usage，
     * 最终在 doFinally 记录 Span。
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc,
                                        ModelCallInput modelInput,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(modelInput);
        }

        String traceId = taskCtx.getTaskId();

        // === 直接从 ModelCallInput 原生 API 获取入参（无需反射） ===
        List<Msg> messages = modelInput.messages();
        List<ToolSchema> tools = modelInput.tools();
        Model model = modelInput.model();
        String modelName = (model != null) ? model.getModelName() : "unknown";

        // 构建入参摘要
        String inputSummary = buildInputSummary(messages, tools, modelName);

        // 记录调用开始时间（使用 replyId 关联，但 onModelCall 阶段还没有 replyId，
        // 使用 traceId + 时间戳作为临时 key）
        String modelCallKey = traceId + "_" + System.nanoTime();
        long startTime = System.currentTimeMillis();
        modelCallStartTimes.put(modelCallKey, startTime);

        // 把 inputSummary 也缓存起来，供 doFinally 使用
        final String finalInputSummary = inputSummary;
        final String finalModelName = modelName;

        return next.apply(modelInput)
                .doOnNext(event -> {
                    // 累积输出文本与 token usage
                    accumulateModelOutput(event, modelCallKey, traceId);
                })
                .doFinally(signal -> {
                    // 记录 LLM_CALL Span
                    try {
                        Long startTs = modelCallStartTimes.remove(modelCallKey);
                        long durationMs = (startTs != null) ? (System.currentTimeMillis() - startTs) : 0;

                        StringBuilder outputBuf = modelOutputBuffer.remove(modelCallKey);
                        String outputSummary = (outputBuf != null) ? outputBuf.toString() : "";

                        int[] usage = modelTokenUsage.remove(modelCallKey);
                        Integer tokenInput = (usage != null) ? usage[0] : null;
                        Integer tokenOutput = (usage != null) ? usage[1] : null;

                        // 构建 meta（包含 requestContext 和 responseSummary）
                        String meta = buildModelCallMeta(messages, tools, finalModelName,
                                outputSummary, taskCtx);

                        recorder.recordSpan(
                                traceId, "LLM_CALL", finalModelName, "SUCCESS",
                                LocalDateTime.now(), LocalDateTime.now(),
                                durationMs,
                                finalInputSummary, outputSummary,
                                tokenInput, tokenOutput,
                                null, meta);

                        log.debug("AegisTraceMiddleware: LLM_CALL span recorded, modelName={}, "
                                        + "msgCount={}, toolCount={}, outputLen={}, durationMs={}",
                                finalModelName,
                                messages != null ? messages.size() : 0,
                                tools != null ? tools.size() : 0,
                                outputSummary.length(), durationMs);
                    } catch (Exception e) {
                        log.debug("AegisTraceMiddleware: failed to record LLM_CALL span: {}", e.getMessage());
                    }
                });
    }

    // ==================== onActing: 工具调用 Span ====================

    /**
     * 记录工具调用 Span。
     *
     * <p>从 {@link ActingInput#toolCalls()} 原生 API 获取工具调用列表，
     * 监听流式事件累积参数和结果。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc,
                                     ActingInput actingInput,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(actingInput);
        }

        String traceId = taskCtx.getTaskId();
        List<ToolUseBlock> toolCalls = actingInput.toolCalls();

        // 记录工具调用开始时间和名称
        if (toolCalls != null) {
            for (ToolUseBlock tub : toolCalls) {
                String toolCallId = tub.getId();
                if (toolCallId != null) {
                    toolCallStartTimes.put(toolCallId, System.currentTimeMillis());
                    String toolName = tub.getName();
                    if (toolName != null) {
                        toolNameMap.put(toolCallId, toolName);
                    }
                    // 记录工具参数
                    if (tub.getInput() != null && !tub.getInput().isEmpty()) {
                        try {
                            String argsJson = objectMapper.writeValueAsString(tub.getInput());
                            toolArgsBuffer.put(toolCallId, new StringBuilder(argsJson));
                        } catch (Exception e) {
                            toolArgsBuffer.put(toolCallId, new StringBuilder("{}"));
                        }
                    } else {
                        toolArgsBuffer.put(toolCallId, new StringBuilder("{}"));
                    }
                }
            }
        }

        return next.apply(actingInput)
                .doOnNext(event -> {
                    // 累积工具调用参数、结果文本与执行状态
                    accumulateToolEvents(event);
                })
                .doFinally(signal -> {
                    // 记录工具调用 Span
                    try {
                        if (toolCalls != null) {
                            for (ToolUseBlock tub : toolCalls) {
                                String toolCallId = tub.getId();
                                if (toolCallId == null) continue;

                                String toolName = toolNameMap.remove(toolCallId);
                                if (toolName == null) toolName = tub.getName() != null ? tub.getName() : "unknown";

                                Long startTs = toolCallStartTimes.remove(toolCallId);
                                long durationMs = (startTs != null) ? (System.currentTimeMillis() - startTs) : 0;

                                StringBuilder argsBuf = toolArgsBuffer.remove(toolCallId);
                                String argsStr = (argsBuf != null) ? argsBuf.toString() : "{}";

                                StringBuilder resultBuf = toolResultTextBuffer.remove(toolCallId);
                                String resultStr = (resultBuf != null) ? resultBuf.toString() : "";

                                String status = toolStatusMap.remove(toolCallId);
                                if (status == null) status = "SUCCESS";

                                // 构建 tool meta
                                Map<String, Object> toolMeta = new HashMap<>();
                                Map<String, Object> toolContext = new HashMap<>();
                                toolContext.put("toolName", toolName);
                                toolContext.put("toolCallId", toolCallId);
                                try {
                                    Object argsObj = objectMapper.readValue(argsStr, Object.class);
                                    toolContext.put("arguments", argsObj);
                                } catch (Exception e) {
                                    toolContext.put("arguments", argsStr);
                                }
                                Map<String, Object> resultSummary = new HashMap<>();
                                resultSummary.put("preview", truncate(resultStr, 200));
                                resultSummary.put("status", status);
                                resultSummary.put("truncated", resultStr.length() > 2000);
                                resultSummary.put("originalLength", resultStr.length());
                                toolContext.put("resultSummary", resultSummary);
                                toolMeta.put("toolContext", toolContext);

                                String metaJson = objectMapper.writeValueAsString(toolMeta);

                                recorder.recordToolCall(
                                        traceId, toolName, "tool_result", status,
                                        LocalDateTime.now(), durationMs,
                                        truncate(argsStr, 512), truncate(resultStr, 512),
                                        null, metaJson);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("AegisTraceMiddleware: failed to record tool span: {}", e.getMessage());
                    }
                });
    }

    // ==================== 私有工具方法 ====================

    /**
     * 构建 LLM 调用入参摘要。
     *
     * <p>直接从 {@link Msg} 原生 API 提取 role 和 textContent，无需反射。
     */
    private String buildInputSummary(List<Msg> messages, List<ToolSchema> tools, String modelName) {
        try {
            Map<String, Object> summary = new HashMap<>();
            summary.put("modelName", modelName);

            // 消息列表
            if (messages != null && !messages.isEmpty()) {
                List<Map<String, Object>> msgList = new ArrayList<>();
                for (Msg msg : messages) {
                    Map<String, Object> m = new HashMap<>();
                    MsgRole role = msg.getRole();
                    m.put("role", role != null ? role.name() : "UNKNOWN");
                    String text = msg.getTextContent();
                    if (text != null && !text.isEmpty()) {
                        m.put("content", truncate(text, 500));
                    }
                    msgList.add(m);
                }
                summary.put("messages", msgList);
                summary.put("messageCount", msgList.size());
            } else {
                summary.put("messageCount", 0);
                summary.put("messages", List.of());
            }

            // 工具列表
            if (tools != null && !tools.isEmpty()) {
                List<Map<String, Object>> toolList = new ArrayList<>();
                for (ToolSchema ts : tools) {
                    Map<String, Object> t = new HashMap<>();
                    t.put("name", ts.getName());
                    t.put("description", truncate(ts.getDescription(), 200));
                    toolList.add(t);
                }
                summary.put("tools", toolList);
                summary.put("toolCount", toolList.size());
            } else {
                summary.put("toolCount", 0);
            }

            return objectMapper.writeValueAsString(summary);
        } catch (Exception e) {
            // 降级：返回基础信息
            return String.format("{\"modelName\":\"%s\",\"messageCount\":%d,\"toolCount\":%d}",
                    modelName,
                    messages != null ? messages.size() : 0,
                    tools != null ? tools.size() : 0);
        }
    }

    /**
     * 构建 LLM 调用 meta（包含 requestContext 和 responseSummary）。
     *
     * <p>顶层 key 供前端直接索引，嵌套结构保留完整语义。</p>
     */
    private String buildModelCallMeta(List<Msg> messages, List<ToolSchema> tools,
                                       String modelName, String outputText,
                                       AegisTaskContext taskCtx) {
        try {
            Map<String, Object> meta = new HashMap<>();

            // 顶层快捷字段（方便前端直接读取，无需嵌套解析）
            meta.put("modelName", modelName);

            // requestContext
            Map<String, Object> requestContext = new HashMap<>();
            requestContext.put("modelName", modelName);
            requestContext.put("messageCount", messages != null ? messages.size() : 0);

            // 消息列表（精简版，仅 role + content 预览）
            if (messages != null && !messages.isEmpty()) {
                List<Map<String, Object>> msgList = new ArrayList<>();
                for (Msg msg : messages) {
                    Map<String, Object> m = new HashMap<>();
                    MsgRole role = msg.getRole();
                    m.put("role", role != null ? role.name() : "UNKNOWN");
                    String text = msg.getTextContent();
                    if (text != null && !text.isEmpty()) {
                        m.put("content", truncate(text, 200));
                    }
                    msgList.add(m);
                }
                requestContext.put("messages", msgList);
            }

            // 工具列表
            if (tools != null && !tools.isEmpty()) {
                List<String> toolNames = new ArrayList<>();
                for (ToolSchema ts : tools) {
                    if (ts.getName() != null) {
                        toolNames.add(ts.getName());
                    }
                }
                requestContext.put("toolNames", toolNames);
                requestContext.put("toolCount", toolNames.size());
            }

            meta.put("requestContext", requestContext);

            // responseSummary
            Map<String, Object> responseSummary = new HashMap<>();
            responseSummary.put("textPreview", truncate(outputText, 1000));
            responseSummary.put("truncated", outputText.length() > 1000);
            responseSummary.put("originalLength", outputText.length());
            meta.put("responseSummary", responseSummary);

            // 附加上下文（outputText 供前端 LLM 卡片展示完整输出）
            Map<String, Object> context = new HashMap<>();
            context.put("sessionId", taskCtx.getSessionId());
            context.put("agentId", taskCtx.getAgentId());
            context.put("traceId", taskCtx.getTaskId());
            context.put("outputText", truncate(outputText, 5000));
            context.put("outputTextTruncated", outputText.length() > 5000);
            meta.put("context", context);

            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            return String.format("{\"modelName\":\"%s\",\"error\":\"meta build failed: %s\"}",
                    modelName, e.getMessage());
        }
    }

    /**
     * 从流式事件中累积模型输出文本与 token usage。
     */
    private void accumulateModelOutput(AgentEvent event, String modelCallKey, String traceId) {
        if (event == null) return;
        String type = event.getType().name();
        if ("TEXT_BLOCK_DELTA".equals(type) && event instanceof TextBlockDeltaEvent tde) {
            String delta = tde.getDelta();
            if (delta != null && !delta.isEmpty()) {
                StringBuilder buf = modelOutputBuffer.computeIfAbsent(modelCallKey, k -> new StringBuilder());
                buf.append(delta);
            }
        }
        // MODEL_CALL_END：捕获 ChatUsage，供 doFinally 记录 Span 及 trace 级汇总
        if ("MODEL_CALL_END".equals(type) && event instanceof ModelCallEndEvent mce) {
            ChatUsage usage = mce.getUsage();
            if (usage != null) {
                modelTokenUsage.put(modelCallKey, new int[]{usage.getInputTokens(), usage.getOutputTokens()});
                int[] stats = traceTokenStats.computeIfAbsent(traceId, k -> new int[2]);
                synchronized (stats) {
                    stats[0] += usage.getInputTokens();
                    stats[1] += usage.getOutputTokens();
                }
            }
        }
    }

    /**
     * 从流式事件中累积工具调用的参数缓冲、结果文本与执行状态。
     */
    private void accumulateToolEvents(AgentEvent event) {
        if (event == null) return;
        String type = event.getType().name();
        if ("TOOL_CALL_START".equals(type) && event instanceof ToolCallStartEvent tcse) {
            String toolCallId = tcse.getToolCallId();
            if (toolCallId != null) {
                toolArgsBuffer.putIfAbsent(toolCallId, new StringBuilder());
            }
        }
        if ("TOOL_RESULT_TEXT_DELTA".equals(type) && event instanceof ToolResultTextDeltaEvent trtde) {
            String toolCallId = trtde.getToolCallId();
            String delta = trtde.getDelta();
            if (toolCallId != null && delta != null && !delta.isEmpty()) {
                StringBuilder buf = toolResultTextBuffer.computeIfAbsent(toolCallId, k -> new StringBuilder());
                buf.append(delta);
            }
        }
        if ("TOOL_RESULT_END".equals(type) && event instanceof ToolResultEndEvent tree) {
            String toolCallId = tree.getToolCallId();
            if (toolCallId != null) {
                String state = tree.getState() != null ? tree.getState().name() : "SUCCESS";
                toolStatusMap.put(toolCallId,
                        state.contains("ERROR") || state.contains("FAIL") ? "ERROR" : "SUCCESS");
            }
        }
    }

    /**
     * 截断字符串到指定长度。
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...[truncated]";
    }
}
