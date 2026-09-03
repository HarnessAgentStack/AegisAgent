package com.aegis.runtime.integration.middleware;

import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.integration.observe.TraceSpanRecorder;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 链路追踪中间件（Phase 2 精简版，order=95）。
 *
 * <p>仅保留 OTel 风格 Span 生命周期与 Token 统计，删除与 Security/PolicyDecision 重叠的逻辑
 * 以及冗长的 meta 构建代码。三个拦截点职责：
 * <ul>
 *   <li>{@code onAgent}：开启 Trace，doFinally 结束 Trace 并汇总 trace 级 token</li>
 *   <li>{@code onModelCall}：累积输出文本与 token usage，记录 LLM_CALL Span</li>
 *   <li>{@code onActing}：累积工具结果文本，记录 TOOL_CALL Span</li>
 * </ul>
 *
 * <p>追踪记录失败一律不阻塞主流程，仅记录 debug/warn 日志。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisTraceMiddleware implements MiddlewareBase {

    private final TraceSpanRecorder recorder;

    /** 模型调用输出文本累积：modelCallKey → StringBuilder */
    private final Map<String, StringBuilder> modelOutputBuffer = new ConcurrentHashMap<>();

    /** 模型调用 token 统计：modelCallKey → [inputTokens, outputTokens] */
    private final Map<String, int[]> modelTokenUsage = new ConcurrentHashMap<>();

    /** Trace 级 token 累积：traceId → [totalInputTokens, totalOutputTokens] */
    private final Map<String, int[]> traceTokenStats = new ConcurrentHashMap<>();

    /** 模型调用开始时间：modelCallKey → 开始时间戳 */
    private final Map<String, Long> modelCallStartTimes = new ConcurrentHashMap<>();

    /** 工具调用开始时间：toolCallId → 开始时间戳 */
    private final Map<String, Long> toolCallStartTimes = new ConcurrentHashMap<>();

    /** 工具结果文本累积：toolCallId → StringBuilder */
    private final Map<String, StringBuilder> toolResultBuffer = new ConcurrentHashMap<>();

    /** 工具名：toolCallId → toolName */
    private final Map<String, String> toolNameMap = new ConcurrentHashMap<>();

    @Override
    public int order() {
        // order=95：洋葱链最外层，包裹 RAG(70)/Mask(50)/Audit(30)，确保整链路耗时落入 trace span
        return 95;
    }

    // ==================== onAgent: Trace 生命周期 ====================

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext rc, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            log.debug("AegisTraceMiddleware: AegisTaskContext not found, skipping trace");
            return next.apply(input);
        }
        String traceId = taskCtx.getTaskId();
        try {
            recorder.startTrace(traceId, taskCtx.getSessionId(), taskCtx.getAgentId(),
                    taskCtx.getAgentDef() != null ? taskCtx.getAgentDef().getAgentName() : "",
                    taskCtx.getUserId(), taskCtx.getUserName(), taskCtx.getTenantId(),
                    "/api/runtime/task/chat");
        } catch (Exception e) {
            log.warn("AegisTraceMiddleware: startTrace failed: {}", e.getMessage());
        }

        return next.apply(input)
                .doFinally(signal -> {
                    try {
                        String status = (signal == SignalType.ON_COMPLETE) ? "SUCCESS" : "FAILED";
                        int[] tokens = traceTokenStats.remove(traceId);
                        int totalIn = (tokens != null) ? tokens[0] : 0;
                        int totalOut = (tokens != null) ? tokens[1] : 0;
                        recorder.endTrace(traceId, status, null, totalIn, totalOut, 0);
                    } catch (Exception e) {
                        log.warn("AegisTraceMiddleware: endTrace failed: {}", e.getMessage());
                    }
                    // 清理本轮缓冲（防内存泄漏）
                    modelOutputBuffer.clear();
                    modelTokenUsage.clear();
                    traceTokenStats.clear();
                    modelCallStartTimes.clear();
                    toolCallStartTimes.clear();
                    toolResultBuffer.clear();
                    toolNameMap.clear();
                });
    }

    // ==================== onModelCall: LLM 调用 Span + Token 累积 ====================

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext rc, ModelCallInput modelInput,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(modelInput);
        }
        String traceId = taskCtx.getTaskId();
        Model model = modelInput.model();
        String modelName = (model != null) ? model.getModelName() : "unknown";
        // AgentScope 中间件在 LLM 调用前拦截时，ModelCallInput.messages() 可能返回 null
        // 优先从 ModelCallInput 取，fallback 到 agent.getMessages()（AgentScope Agent 有此方法）
        List<Msg> rawMessages = modelInput.messages();
        List<Msg> resolvedMessages = rawMessages;
        if ((rawMessages == null || rawMessages.isEmpty()) && agent != null) {
            try {
                java.lang.reflect.Method gm = agent.getClass().getMethod("getMessages");
                Object gmResult = gm.invoke(agent);
                if (gmResult instanceof List) {
                    resolvedMessages = (List<Msg>) gmResult;
                    log.debug("P3-FIX: ModelCallInput.messages()为空，fallback agent.getMessages() 得到 {} 条",
                            resolvedMessages.size());
                }
            } catch (Exception e) {
                log.debug("P3-FIX: agent.getMessages() fallback 失败: {}", e.getMessage());
            }
        }
        final List<Msg> messages = resolvedMessages;
        List<ToolSchema> tools = modelInput.tools();
        int msgCount = messages != null ? messages.size() : 0;
        int toolCount = tools != null ? tools.size() : 0;

        String modelCallKey = traceId + "_" + System.nanoTime();
        modelCallStartTimes.put(modelCallKey, System.currentTimeMillis());
        final String finalModelName = modelName;

        return next.apply(modelInput)
                .doOnNext(event -> accumulateModelOutput(event, modelCallKey, traceId))
                .doFinally(signal -> {
                    try {
                        Long startTs = modelCallStartTimes.remove(modelCallKey);
                        long durationMs = (startTs != null) ? (System.currentTimeMillis() - startTs) : 0;
                        StringBuilder outBuf = modelOutputBuffer.remove(modelCallKey);
                        String outputText = (outBuf != null) ? outBuf.toString() : "";
                        int[] usage = modelTokenUsage.remove(modelCallKey);
                        Integer tokenInput = (usage != null) ? usage[0] : null;
                        Integer tokenOutput = (usage != null) ? usage[1] : null;
                        // P1-B：对齐 MysqlTraceStore.buildStepDetail 读取的富契约 key
                        // 写入 requestContext.messages / responseSummary.text / toolContext 等结构化字段
                        String meta = buildLlmCallMeta(finalModelName, msgCount, toolCount,
                                messages, outputText, tokenInput, tokenOutput);
                        recorder.recordSpan(traceId, "LLM_CALL", finalModelName, "SUCCESS",
                                LocalDateTime.now(), LocalDateTime.now(), durationMs,
                                truncate(buildInputPreview(messages), 512),
                                truncate(outputText, 512),
                                tokenInput, tokenOutput, null, meta);
                    } catch (Exception e) {
                        log.debug("AegisTraceMiddleware: LLM_CALL span failed: {}", e.getMessage());
                    }
                });
    }

    // ==================== onActing: 工具调用 Span ====================

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext rc, ActingInput actingInput,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = rc.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(actingInput);
        }
        String traceId = taskCtx.getTaskId();
        List<ToolUseBlock> toolCalls = actingInput.toolCalls();
        if (toolCalls != null) {
            for (ToolUseBlock tub : toolCalls) {
                String toolCallId = tub.getId();
                if (toolCallId != null) {
                    toolCallStartTimes.put(toolCallId, System.currentTimeMillis());
                    if (tub.getName() != null) {
                        toolNameMap.put(toolCallId, tub.getName());
                    }
                }
            }
        }

        return next.apply(actingInput)
                .doOnNext(this::accumulateToolResult)
                .doFinally(signal -> {
                    if (toolCalls == null) return;
                    try {
                        for (ToolUseBlock tub : toolCalls) {
                            String toolCallId = tub.getId();
                            if (toolCallId == null) continue;
                            String toolName = toolNameMap.remove(toolCallId);
                            if (toolName == null) {
                                toolName = tub.getName() != null ? tub.getName() : "unknown";
                            }
                            Long startTs = toolCallStartTimes.remove(toolCallId);
                            long durationMs = (startTs != null) ? (System.currentTimeMillis() - startTs) : 0;
                            StringBuilder resBuf = toolResultBuffer.remove(toolCallId);
                            String resultStr = (resBuf != null) ? resBuf.toString() : "";
                            recorder.recordToolCall(traceId, toolName, "tool_result", "SUCCESS",
                                    LocalDateTime.now(), durationMs,
                                    truncate(String.valueOf(tub.getInput()), 512),
                                    truncate(resultStr, 512), null);
                        }
                    } catch (Exception e) {
                        log.debug("AegisTraceMiddleware: TOOL_CALL span failed: {}", e.getMessage());
                    }
                });
    }

    // ==================== 私有累积方法 ====================

    private void accumulateModelOutput(AgentEvent event, String modelCallKey, String traceId) {
        if (event == null) return;
        String type = event.getType().name();
        if ("TEXT_BLOCK_DELTA".equals(type) && event instanceof TextBlockDeltaEvent tde) {
            String delta = tde.getDelta();
            if (delta != null && !delta.isEmpty()) {
                modelOutputBuffer.computeIfAbsent(modelCallKey, k -> new StringBuilder()).append(delta);
            }
        }
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

    private void accumulateToolResult(AgentEvent event) {
        if (event == null) return;
        if ("TOOL_RESULT_TEXT_DELTA".equals(event.getType().name())
                && event instanceof ToolResultTextDeltaEvent trtde) {
            String toolCallId = trtde.getToolCallId();
            String delta = trtde.getDelta();
            if (toolCallId != null && delta != null && !delta.isEmpty()) {
                toolResultBuffer.computeIfAbsent(toolCallId, k -> new StringBuilder()).append(delta);
            }
        }
    }

    private static String buildInputPreview(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Msg msg : messages) {
            MsgRole role = msg.getRole();
            sb.append(role != null ? role.name() : "UNKNOWN").append(':');
            String text = msg.getTextContent();
            if (text != null) sb.append(text);
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * P1-B：构造 LLM_CALL Span 的富契约 meta JSON。
     *
     * <p>对齐 {@code MysqlTraceStore.buildStepDetail} 读取的全部 key，
     * 让观测中心会话详情能展示 requestMessages / responseText / tokenUsage：</p>
     * <ul>
     *   <li>{@code modelName} / {@code modelVersion} / {@code cachedTokens}：模型元信息</li>
     *   <li>{@code requestContext.messages}：[{role, content}, ...] 完整消息列表</li>
     *   <li>{@code responseSummary.text} / {@code outputText} / {@code text}：输出文本（多 key 兜底）</li>
     *   <li>{@code inputTokens} / {@code outputTokens}：token 统计</li>
     *   <li>{@code messageCount} / {@code toolCount}：向后兼容旧读取</li>
     * </ul>
     */
    private static String buildLlmCallMeta(String modelName, int msgCount, int toolCount,
                                            List<Msg> messages, String outputText,
                                            Integer tokenInput, Integer tokenOutput) {
        com.alibaba.fastjson2.JSONObject meta = new com.alibaba.fastjson2.JSONObject();
        meta.put("modelName", modelName);
        meta.put("modelVersion", null);
        meta.put("cachedTokens", 0);
        meta.put("messageCount", msgCount);
        meta.put("toolCount", toolCount);
        if (tokenInput != null) meta.put("inputTokens", tokenInput);
        if (tokenOutput != null) meta.put("outputTokens", tokenOutput);

        // requestContext.messages：[{role, content}, ...]
        com.alibaba.fastjson2.JSONObject requestContext = new com.alibaba.fastjson2.JSONObject();
        com.alibaba.fastjson2.JSONArray msgArray = new com.alibaba.fastjson2.JSONArray();
        if (messages != null) {
            for (Msg msg : messages) {
                com.alibaba.fastjson2.JSONObject m = new com.alibaba.fastjson2.JSONObject();
                MsgRole role = msg.getRole();
                m.put("role", role != null ? role.name() : "UNKNOWN");
                m.put("content", msg.getTextContent() != null ? msg.getTextContent() : "");
                msgArray.add(m);
            }
        }
        requestContext.put("messages", msgArray);
        meta.put("requestContext", requestContext);

        // responseSummary.text + 多 key 兜底（outputText / text）
        com.alibaba.fastjson2.JSONObject responseSummary = new com.alibaba.fastjson2.JSONObject();
        responseSummary.put("text", outputText != null ? outputText : "");
        meta.put("responseSummary", responseSummary);
        meta.put("outputText", outputText != null ? outputText : "");
        meta.put("text", outputText != null ? outputText : "");

        return meta.toJSONString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...[truncated]";
    }
}
