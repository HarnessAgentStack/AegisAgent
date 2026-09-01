package com.aegis.dal.observe;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.common.web.PageRequest;
import com.aegis.core.common.web.PageResult;
import com.aegis.core.domain.monitor.SpanEntity;
import com.aegis.core.domain.monitor.TraceEntity;
import com.aegis.core.dto.observe.*;
import com.aegis.core.spi.TraceStore;
import com.aegis.dal.mapper.monitor.SpanMapper;
import com.aegis.dal.mapper.monitor.TraceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aegis.observe.store", havingValue = "mysql", matchIfMissing = true)
public class MysqlTraceStore implements TraceStore {

    private final TraceMapper traceMapper;
    private final SpanMapper spanMapper;
    private final ObserveProperties properties;
    
    /** ObjectMapper 实例（用于解析/生成 JSON） */
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    @Override
    public String storeType() {
        return "mysql";
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBatch(List<TraceRecord> traces, List<SpanRecord> spans) {
        if (traces != null && !traces.isEmpty()) {
            List<TraceEntity> traceEntities = traces.stream()
                .map(this::toTraceEntity)
                .collect(Collectors.toList());
            for (TraceEntity te : traceEntities) {
                try {
                    traceMapper.insert(te);
                } catch (Exception e) {
                    // trace_id 有唯一索引 uk_trace_id，retry 场景下同一条 trace 会再次 insert
                    // 此处静默忽略，避免观测性数据影响主业务链路
                    if (isDuplicateKeyException(e)) {
                        log.debug("Trace already exists, skipping: traceId={}", te.getTraceId());
                    } else {
                        throw e;
                    }
                }
            }
        }
        if (spans != null && !spans.isEmpty()) {
            List<SpanEntity> spanEntities = spans.stream()
                .map(this::toSpanEntity)
                .collect(Collectors.toList());
            for (SpanEntity se : spanEntities) {
                try {
                    spanMapper.insert(se);
                } catch (Exception e) {
                    if (isDuplicateKeyException(e)) {
                        log.debug("Span already exists, skipping: spanId={}", se.getSpanId());
                    } else {
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * 判断是否为 MySQL 唯一键冲突异常。
     */
    private boolean isDuplicateKeyException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.sql.SQLIntegrityConstraintViolationException
                    && cause.getMessage() != null
                    && cause.getMessage().contains("Duplicate entry")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public PageResult<TraceRecord> queryTraces(TraceQuery query) {
        LambdaQueryWrapper<TraceEntity> wrapper = new LambdaQueryWrapper<>();

        if (query.getTraceId() != null && !query.getTraceId().isEmpty()) {
            wrapper.eq(TraceEntity::getTraceId, query.getTraceId());
        }
        if (query.getSessionId() != null && !query.getSessionId().isEmpty()) {
            wrapper.eq(TraceEntity::getSessionId, query.getSessionId());
        }
        if (query.getUserId() != null) {
            wrapper.eq(TraceEntity::getUserId, query.getUserId());
        }
        if (query.getAgentId() != null) {
            wrapper.eq(TraceEntity::getAgentId, query.getAgentId());
        }
        if (query.getStatus() != null && !query.getStatus().isEmpty()) {
            wrapper.eq(TraceEntity::getStatus, query.getStatus());
        }
        if (query.getStartTime() != null && query.getEndTime() != null) {
            wrapper.between(TraceEntity::getStartTime,
                query.getStartTime(),
                query.getEndTime());
        }

        wrapper.orderByDesc(TraceEntity::getStartTime);

        int page = query.getPage() != null ? query.getPage() : 1;
        int size = query.getSize() != null ? query.getSize() : 20;

        Page<TraceEntity> pageResult = traceMapper.selectPage(
            new Page<>(page, size), wrapper);

        List<TraceRecord> records = pageResult.getRecords().stream()
            .map(this::toTraceRecord)
            .collect(Collectors.toList());

        return PageResult.of(records, pageResult.getTotal(), page, size);
    }

    @Override
    public TraceDetail getTraceDetail(String traceId) {
        TraceEntity traceEntity = traceMapper.selectOne(
            new LambdaQueryWrapper<TraceEntity>().eq(TraceEntity::getTraceId, traceId));
        if (traceEntity == null) {
            return null;
        }

        List<SpanEntity> spanEntities = spanMapper.selectList(
            new LambdaQueryWrapper<SpanEntity>()
                .eq(SpanEntity::getTraceId, traceId)
                .orderByAsc(SpanEntity::getStartTime));

        List<SpanRecord> spans = spanEntities.stream()
            .map(this::toSpanRecord)
            .collect(Collectors.toList());

        // 计算 roundIndex、stepIndex 和友好显示名
        enrichSpanMetadata(spans);

        return TraceDetail.builder()
            .trace(toTraceRecord(traceEntity))
            .spans(spans)
            .build();
    }

    /**
     * 为 Span 计算轮次分组信息和友好名称。
     * <p>
     * 分组规则：
     * - AGENT_ASSEMBLY 作为初始化轮次（roundIndex = -1）
     * - 每个 LLM_CALL 开始新的一轮
     * - 同一轮内的 TOOL_CALL 归入当前轮
     * - HITL_WAIT、SANDBOX_EXEC 跨轮时归到所在轮
     */
    private void enrichSpanMetadata(List<SpanRecord> spans) {
        int currentRound = -1;
        int stepInRound = 0;

        for (SpanRecord span : spans) {
            String type = span.getSpanType();
            if (type == null) type = "UNKNOWN";

            switch (type) {
                case "AGENT_ASSEMBLY":
                    // 初始化轮次
                    span.setRoundIndex(-1);
                    span.setStepIndex(0);
                    span.setDisplayName("智能体装配");
                    break;

                case "LLM_CALL":
                    // 开始新的一轮
                    currentRound++;
                    stepInRound = 0;
                    span.setRoundIndex(currentRound);
                    span.setStepIndex(stepInRound++);
                    // 友好名称：优先使用 meta 中的 modelName，降级到 span.name
                    enrichLlmCallMeta(span);
                    if (span.getDisplayName() == null) {
                        span.setDisplayName(toFriendlyModelName(span.getName()));
                    }
                    break;

                case "TOOL_CALL":
                case "RAG_RETRIEVE":
                case "HITL_WAIT":
                case "SANDBOX_EXEC":
                case "SSE_OUT":
                case "MEMORY_RECALL":
                    // 归入当前轮
                    span.setRoundIndex(currentRound);
                    span.setStepIndex(stepInRound++);
                    break;

                default:
                    span.setRoundIndex(currentRound);
                    span.setStepIndex(stepInRound++);
                    break;
            }
        }
    }

    @Override
    public SessionDetailResponse getSessionDetail(String sessionId) {
        // 1. 查询会话下所有 Trace
        LambdaQueryWrapper<TraceEntity> traceWrapper = new LambdaQueryWrapper<>();
        traceWrapper.eq(TraceEntity::getSessionId, sessionId)
                .orderByAsc(TraceEntity::getStartTime);
        List<TraceEntity> traceEntities = traceMapper.selectList(traceWrapper);

        if (traceEntities.isEmpty()) {
            return null;
        }

        // 2. 查询会话下所有 Span
        List<SpanRecord> allSpans = listSpansBySession(sessionId);

        // 3. 按 traceId 分组 Span
        Map<String, List<SpanRecord>> spansByTrace = allSpans.stream()
                .collect(Collectors.groupingBy(SpanRecord::getTraceId, LinkedHashMap::new, Collectors.toList()));

        // 4. 构建轮次列表
        List<RoundDetail> rounds = new ArrayList<>();
        int roundIndex = 0;
        for (TraceEntity traceEntity : traceEntities) {
            List<SpanRecord> roundSpans = spansByTrace.getOrDefault(traceEntity.getTraceId(), Collections.emptyList());
            RoundDetail round = buildRoundDetail(traceEntity, roundSpans, roundIndex);
            rounds.add(round);
            roundIndex++;
        }

        // 5. 计算会话级汇总
        TraceEntity firstTrace = traceEntities.get(0);
        TraceEntity lastTrace = traceEntities.get(traceEntities.size() - 1);

        long totalDuration = 0;
        int totalTokenInput = 0;
        int totalTokenOutput = 0;

        for (TraceEntity t : traceEntities) {
            if (t.getDurationMs() != null) totalDuration += t.getDurationMs();
            if (t.getTokenInput() != null) totalTokenInput += t.getTokenInput();
            if (t.getTokenOutput() != null) totalTokenOutput += t.getTokenOutput();
        }

        // 计算整体状态
        String sessionStatus = "SUCCESS";
        if (traceEntities.stream().anyMatch(t -> "FAILED".equals(t.getStatus()))) {
            sessionStatus = "PARTIAL";
        }

        return SessionDetailResponse.builder()
                .sessionId(sessionId)
                .agentId(firstTrace.getAgentId())
                .agentName(firstTrace.getAgentName())
                .userId(firstTrace.getUserId())
                .userName(firstTrace.getUserName())
                .totalRounds(rounds.size())
                .totalDurationMs(totalDuration)
                .totalTokenInput(totalTokenInput)
                .totalTokenOutput(totalTokenOutput)
                .status(sessionStatus)
                .startTime(firstTrace.getStartTime())
                .endTime(lastTrace.getEndTime())
                .rounds(rounds)
                .build();
    }

    @Override
    public List<SpanRecord> listSpansBySession(String sessionId) {
        // 先查该会话下的所有 traceIds
        LambdaQueryWrapper<TraceEntity> traceWrapper = new LambdaQueryWrapper<>();
        traceWrapper.eq(TraceEntity::getSessionId, sessionId);
        List<TraceEntity> traceEntities = traceMapper.selectList(traceWrapper);

        if (traceEntities.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> traceIds = traceEntities.stream()
                .map(TraceEntity::getTraceId)
                .collect(Collectors.toList());

        // 通过 traceIds 查询 spans（兼容历史数据 session_id 为 NULL 的情况）
        LambdaQueryWrapper<SpanEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(SpanEntity::getTraceId, traceIds)
                .orderByAsc(SpanEntity::getStartTime);

        List<SpanEntity> spanEntities = spanMapper.selectList(wrapper);
        List<SpanRecord> spans = spanEntities.stream()
                .map(this::toSpanRecord)
                .collect(Collectors.toList());

        // 计算 roundIndex、stepIndex 和 displayName
        enrichSpanMetadata(spans);
        return spans;
    }

    /**
     * 构建单个轮次详情。
     */
    private RoundDetail buildRoundDetail(TraceEntity traceEntity, List<SpanRecord> spans, int roundIndex) {
        // 为该轮 Span 重新计算 roundIndex（因为 listSpansBySession 是全局计算的）
        // 这里我们以当前 trace 为粒度重新分组
        Map<String, List<SpanRecord>> groupedSpans = new LinkedHashMap<>();
        groupedSpans.put(traceEntity.getTraceId(), spans);

        // 基于 span 类型分组，计算 step 索引
        int llmCallCount = 0;
        int toolCallCount = 0;
        int tokenInput = 0;
        int tokenOutput = 0;

        // 构建步骤列表
        List<StepDetail> steps = new ArrayList<>();
        int stepIdx = 0;

        // 先处理 AGENT_ASSEMBLY（如果有）
        for (SpanRecord span : spans) {
            if ("AGENT_ASSEMBLY".equals(span.getSpanType())) {
                steps.add(buildStepDetail(span, -1, stepIdx++));
            }
        }

        // 按 LLM_CALL / TOOL_CALL 分组
        SpanRecord lastLlmCall = null;
        List<SpanRecord> toolBatch = new ArrayList<>();

        for (SpanRecord span : spans) {
            String type = span.getSpanType();
            if ("AGENT_ASSEMBLY".equals(type)) continue;

            switch (type) {
                case "LLM_CALL":
                    // 先处理之前的 tool batch
                    if (!toolBatch.isEmpty()) {
                        for (SpanRecord toolSpan : toolBatch) {
                            steps.add(buildStepDetail(toolSpan, roundIndex, stepIdx++));
                            toolCallCount++;
                        }
                        toolBatch.clear();
                    }
                    steps.add(buildStepDetail(span, roundIndex, stepIdx++));
                    llmCallCount++;
                    lastLlmCall = span;
                    break;

                case "TOOL_CALL":
                    toolBatch.add(span);
                    break;

                default:
                    // 其他类型直接加入
                    steps.add(buildStepDetail(span, roundIndex, stepIdx++));
                    break;
            }
        }

        // 处理最后一批 tool
        for (SpanRecord toolSpan : toolBatch) {
            steps.add(buildStepDetail(toolSpan, roundIndex, stepIdx++));
            toolCallCount++;
        }

        // 聚合 token
        for (SpanRecord span : spans) {
            if (span.getTokenInput() != null) tokenInput += span.getTokenInput();
            if (span.getTokenOutput() != null) tokenOutput += span.getTokenOutput();
        }

        // 从第一个 LLM_CALL 的 meta 中提取用户问题
        String roundTitle = extractUserQuery(spans);

        return RoundDetail.builder()
                .roundIndex(roundIndex)
                .roundTitle(roundTitle)
                .roundType("USER_QUERY")
                .startTime(traceEntity.getStartTime())
                .endTime(traceEntity.getEndTime())
                .durationMs(traceEntity.getDurationMs() != null ? traceEntity.getDurationMs().longValue() : null)
                .llmCallCount(llmCallCount)
                .toolCallCount(toolCallCount)
                .tokenInput(tokenInput)
                .tokenOutput(tokenOutput)
                .status(traceEntity.getStatus())
                .steps(steps)
                .build();
    }

    /**
     * 从 Span meta 中提取用户问题摘要。
     * 优先从 requestContext.messages 中获取，回退到 responseSummary.textPreview。
     */
    private String extractUserQuery(List<SpanRecord> spans) {
        return spans.stream()
                .filter(s -> "LLM_CALL".equals(s.getSpanType()))
                .findFirst()
                .map(s -> {
                    if (s.getMeta() != null) {
                        try {
                            Map<String, Object> meta = objectMapper.readValue(s.getMeta(), Map.class);
                            // 优先尝试从 requestContext.messages 中提取
                            Map<String, Object> requestContext = (Map<String, Object>) meta.get("requestContext");
                            if (requestContext != null) {
                                List<Map<String, Object>> messages = (List<Map<String, Object>>) requestContext.get("messages");
                                if (messages != null) {
                                    for (Map<String, Object> msg : messages) {
                                        if ("user".equals(msg.get("role"))) {
                                            String content = (String) msg.get("content");
                                            if (content != null && !content.isEmpty()) {
                                                return content.length() > 50 ? content.substring(0, 50) + "..." : content;
                                            }
                                        }
                                    }
                                }
                            }
                            // 回退：从 responseSummary.textPreview 提取（去掉思考标签）
                            Map<String, Object> responseSummary = (Map<String, Object>) meta.get("responseSummary");
                            if (responseSummary != null) {
                                String preview = (String) responseSummary.get("textPreview");
                                if (preview != null && !preview.isEmpty()) {
                                    // 去掉 [思考] 标签
                                    String cleaned = preview.replaceAll("\\[思考\\]", "");
                                    if (!cleaned.trim().isEmpty()) {
                                        return cleaned.length() > 50 ? cleaned.substring(0, 50) + "..." : cleaned;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed to extract user query from span meta", e);
                        }
                    }
                    return "（无标题）";
                })
                .orElse("（无标题）");
    }

    /**
     * 构建单个步骤详情。
     */
    private StepDetail buildStepDetail(SpanRecord span, int roundIndex, int stepIndex) {
        StepDetail.StepDetailBuilder builder = StepDetail.builder()
                .stepIndex(stepIndex)
                .spanId(span.getSpanId())
                .spanType(span.getSpanType())
                .status(span.getStatus())
                .startTime(span.getStartTime())
                .endTime(span.getEndTime())
                .durationMs(span.getDurationMs())
                .displayName(span.getDisplayName() != null ? span.getDisplayName() : span.getName())
                .name(span.getName())
                .tokenInput(span.getTokenInput())
                .tokenOutput(span.getTokenOutput());

        // 根据 spanType 填充特定字段
        String type = span.getSpanType() != null ? span.getSpanType() : "UNKNOWN";

        switch (type) {
            case "LLM_CALL" -> {
                // 解析 meta 获取 LLM 特定信息
                if (span.getMeta() != null) {
                    try {
                        Map<String, Object> meta = objectMapper.readValue(span.getMeta(), Map.class);
                        builder.modelName((String) meta.getOrDefault("modelName", span.getName()));
                        builder.modelVersion((String) meta.get("modelVersion"));
                        builder.cachedTokens(meta.get("cachedTokens") instanceof Number
                                ? ((Number) meta.get("cachedTokens")).intValue() : 0);

                        // 请求上下文
                        Map<String, Object> requestContext = (Map<String, Object>) meta.get("requestContext");
                        if (requestContext != null) {
                            builder.requestSummary(Map.of(
                                    "messageCount", requestContext.getOrDefault("messageCount", 0),
                                    "systemCount", requestContext.getOrDefault("systemCount", 0),
                                    "toolCount", requestContext.getOrDefault("toolCount", 0)
                            ));
                            
                            // 解析消息列表（含截断标记）
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> messages = (List<Map<String, Object>>) requestContext.get("messages");
                            if (messages != null) {
                                builder.requestMessages(messages);
                            }
                            
                            // 解析工具列表（含截断标记）
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> tools = (List<Map<String, Object>>) requestContext.get("tools");
                            if (tools != null) {
                                builder.requestTools(tools);
                            }
                            
                            // 消息截断标记和原始数量
                            Object msgTruncated = requestContext.get("messagesTruncated");
                            if (msgTruncated instanceof Boolean) {
                                builder.requestMessagesTruncated((Boolean) msgTruncated);
                            }
                            Object msgOrigCount = requestContext.get("messagesOriginalCount");
                            if (msgOrigCount instanceof Number) {
                                builder.requestMessagesOriginalCount(((Number) msgOrigCount).intValue());
                            }
                            
                            // 工具截断标记和原始数量
                            Object toolTruncated = requestContext.get("toolsTruncated");
                            if (toolTruncated instanceof Boolean) {
                                builder.requestToolsTruncated((Boolean) toolTruncated);
                            }
                            Object toolOrigCount = requestContext.get("toolsOriginalCount");
                            if (toolOrigCount instanceof Number) {
                                builder.requestToolsOriginalCount(((Number) toolOrigCount).intValue());
                            }
                        }
                        
                        // 消息 fallback 标记
                        Object msgFallback = meta.get("messagesFallback");
                        if (msgFallback instanceof Boolean) {
                            builder.messagesFallback((Boolean) msgFallback);
                        }

                        // 响应信息
                        Map<String, Object> responseSummary = (Map<String, Object>) meta.get("responseSummary");
                        if (responseSummary != null) {
                            builder.responseTextPreview((String) responseSummary.get("textPreview"));
                            builder.reasoningPreview((String) responseSummary.get("reasoningContent"));
                            
                            // 工具调用列表
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) responseSummary.get("toolCalls");
                            if (toolCalls != null) {
                                builder.responseToolCalls(toolCalls);
                            }
                            
                            // 响应截断标记
                            Object truncated = responseSummary.get("truncated");
                            if (truncated instanceof Boolean) {
                                builder.responseTextTruncated((Boolean) truncated);
                            }
                            Object origLen = responseSummary.get("originalLength");
                            if (origLen instanceof Number) {
                                builder.responseTextOriginalLength(((Number) origLen).intValue());
                            }
                        }

                        // 完整输出文本（优先从 responseSummary.text 获取，回退到 outputText）
                        String fullText = null;
                        if (responseSummary != null && responseSummary.get("text") instanceof String) {
                            fullText = (String) responseSummary.get("text");
                        } else if (meta.get("outputText") instanceof String) {
                            fullText = (String) meta.get("outputText");
                        } else if (meta.get("text") instanceof String) {
                            fullText = (String) meta.get("text");
                        }
                        
                        if (fullText != null) {
                            // API 层做轻量截断：>10000 字符截断（前端可通过截断标记感知）
                            int maxLen = 10000;
                            boolean truncated = fullText.length() > maxLen;
                            builder.responseText(truncated ? fullText.substring(0, maxLen) + "..." : fullText);
                            builder.responseTextTruncated(truncated);
                            builder.responseTextOriginalLength(fullText.length());
                        } else {
                            // 无输出文本时设置默认值
                            builder.responseText("");
                            builder.responseTextTruncated(false);
                            builder.responseTextOriginalLength(0);
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse LLM_CALL meta", e);
                    }
                }
                // 回退：从 name 推断模型名
                if (builder.build().getModelName() == null) {
                    builder.modelName(toFriendlyModelName(span.getName()));
                }
            }

            case "TOOL_CALL" -> {
                if (span.getMeta() != null) {
                    try {
                        Map<String, Object> meta = objectMapper.readValue(span.getMeta(), Map.class);
                        Map<String, Object> toolContext = (Map<String, Object>) meta.get("toolContext");
                        if (toolContext != null) {
                            Object toolCallId = toolContext.get("toolCallId");
                            if (toolCallId != null) {
                                builder.toolCallId(String.valueOf(toolCallId));
                            }
                            // toolName 已通过 builder.name() 在公共字段中设置
                            Object args = toolContext.get("arguments");
                            if (args instanceof Map) {
                                builder.toolArguments((Map<String, Object>) args);
                                builder.toolArgumentsJson(objectMapper.writeValueAsString(args));
                            }
                            // 优先从 toolContext.resultSummary 解析
                            Map<String, Object> result = (Map<String, Object>) toolContext.get("resultSummary");
                            if (result == null) {
                                // 回退：从 meta 顶层找 resultSummary
                                result = (Map<String, Object>) meta.get("resultSummary");
                            }
                            if (result != null) {
                                builder.toolResultPreview((String) result.get("preview"));
                                builder.toolStatus((String) result.get("status"));
                                // 截断标记
                                Object truncated = result.get("truncated");
                                if (truncated instanceof Boolean) {
                                    builder.toolResultTruncated((Boolean) truncated);
                                }
                                Object origLen = result.get("originalLength");
                                if (origLen instanceof Number) {
                                    builder.toolResultOriginalLength(((Number) origLen).intValue());
                                }
                            }
                            
                            // 尝试获取完整结果文本
                            Object fullResult = toolContext.get("result");
                            boolean resultTruncated = false;
                            int resultOrigLen = 0;
                            if (fullResult instanceof String) {
                                String resultStr = (String) fullResult;
                                resultOrigLen = resultStr.length();
                                // API 层截断：>5000 字符截断
                                int maxLen = 5000;
                                resultTruncated = resultStr.length() > maxLen;
                                builder.toolResult(resultTruncated ? resultStr.substring(0, maxLen) + "..." : resultStr);
                            } else if (fullResult != null) {
                                // 非字符串类型，尝试序列化
                                try {
                                    String serialized = objectMapper.writeValueAsString(fullResult);
                                    resultOrigLen = serialized.length();
                                    int maxLen = 5000;
                                    resultTruncated = serialized.length() > maxLen;
                                    builder.toolResult(resultTruncated ? serialized.substring(0, maxLen) + "..." : serialized);
                                } catch (Exception e) {
                                    builder.toolResult(String.valueOf(fullResult));
                                    resultOrigLen = String.valueOf(fullResult).length();
                                }
                            }
                            builder.toolResultTruncated(resultTruncated);
                            builder.toolResultOriginalLength(resultOrigLen);
                            
                            // 检查 toolContext 级别的截断标记（仅当 API 层未设置时使用）
                            if (!resultTruncated || resultOrigLen == 0) {
                                Object ctxTruncated = toolContext.get("resultTruncated");
                                if (ctxTruncated instanceof Boolean) {
                                    builder.toolResultTruncated((Boolean) ctxTruncated);
                                }
                                Object ctxOrigLen = toolContext.get("resultOriginalLength");
                                if (ctxOrigLen instanceof Number && resultOrigLen == 0) {
                                    builder.toolResultOriginalLength(((Number) ctxOrigLen).intValue());
                                }
                            }
                            
                            // toolName 回退：如果 name 为空则用 meta 中的 toolName
                            if (span.getName() == null || span.getName().isEmpty()) {
                                Object toolName = toolContext.get("toolName");
                                if (toolName == null) toolName = meta.get("toolName");
                                if (toolName != null) builder.name(String.valueOf(toolName));
                            }
                        } else {
                            // 无 toolContext，尝试从顶层 meta 解析（兼容旧格式）
                            Map<String, Object> result = (Map<String, Object>) meta.get("resultSummary");
                            if (result != null) {
                                builder.toolResultPreview((String) result.get("preview"));
                                builder.toolStatus((String) result.get("status"));
                                Object truncated = result.get("truncated");
                                if (truncated instanceof Boolean) {
                                    builder.toolResultTruncated((Boolean) truncated);
                                }
                                Object origLen = result.get("originalLength");
                                if (origLen instanceof Number) {
                                    builder.toolResultOriginalLength(((Number) origLen).intValue());
                                }
                            }
                            Object args = meta.get("arguments");
                            if (args instanceof Map) {
                                builder.toolArguments((Map<String, Object>) args);
                                builder.toolArgumentsJson(objectMapper.writeValueAsString(args));
                            } else if (args instanceof String) {
                                try {
                                    Map<String, Object> argsMap = objectMapper.readValue((String) args, Map.class);
                                    builder.toolArguments(argsMap);
                                    builder.toolArgumentsJson((String) args);
                                } catch (Exception ignored) {
                                    builder.toolArgumentsJson((String) args);
                                }
                            }
                            Object toolName = meta.get("toolName");
                            if (toolName != null) builder.name(String.valueOf(toolName));
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse TOOL_CALL meta", e);
                    }
                }
                // 回退：使用 inputSummary / outputSummary
                if (builder.build().getToolArguments() == null && span.getInputSummary() != null) {
                    try {
                        builder.toolArgumentsJson(span.getInputSummary());
                        builder.toolArguments(objectMapper.readValue(span.getInputSummary(), Map.class));
                    } catch (Exception e) {
                        log.warn("inputSummary 反序列化失败(降级跳过): spanId={}, err={}", span.getSpanId(), e.getMessage());
                    }
                }
                if (builder.build().getToolResultPreview() == null && span.getOutputSummary() != null) {
                    builder.toolResultPreview(span.getOutputSummary().length() > 100
                            ? span.getOutputSummary().substring(0, 100) + "..."
                            : span.getOutputSummary());
                }
                if (builder.build().getToolStatus() == null) {
                    builder.toolStatus(span.getStatus());
                }
                // 确保截断标志有默认值
                if (builder.build().getToolResultTruncated() == null) {
                    builder.toolResultTruncated(false);
                }
                if (builder.build().getToolResultOriginalLength() == null) {
                    String preview = builder.build().getToolResultPreview();
                    builder.toolResultOriginalLength(preview != null ? preview.length() : 0);
                }
            }

            case "AGENT_ASSEMBLY" -> {
                builder.displayName("智能体装配");
            }

            default -> {
                // 其他类型：解析 extraMeta
                if (span.getMeta() != null) {
                    try {
                        builder.extraMeta(objectMapper.readValue(span.getMeta(), Map.class));
                    } catch (Exception e) {
                        log.warn("extraMeta 反序列化失败(降级跳过): spanId={}, err={}", span.getSpanId(), e.getMessage());
                    }
                }
            }
        }

        return builder.build();
    }

    /**
     * 将 ModelTier 枚举名转为友好中文名称。
     */
    private String toFriendlyModelName(String modelName) {
        if (modelName == null) return "未知模型";
        return switch (modelName.toUpperCase()) {
            case "STANDARD" -> "豆包标准版";
            case "PRO" -> "豆包专业版";
            case "ULTRA" -> "豆包旗舰版";
            case "REASONING" -> "豆包推理版";
            case "LITE" -> "豆包轻量版";
            default -> modelName;
        };
    }

    @Override
    public PageResult<TraceRecord> queryBySession(String sessionId, PageRequest page) {
        LambdaQueryWrapper<TraceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceEntity::getSessionId, sessionId)
            .orderByDesc(TraceEntity::getStartTime);

        Page<TraceEntity> pageResult = traceMapper.selectPage(
            new Page<>(page.getPageNum(), page.getPageSize()), wrapper);

        List<TraceRecord> records = pageResult.getRecords().stream()
            .map(this::toTraceRecord)
            .collect(Collectors.toList());

        return PageResult.of(records, pageResult.getTotal(), page.getPageNum(), page.getPageSize());
    }

    @Override
    public PageResult<SessionSummary> querySessions(PageRequest page) {
        Long tenantId = TenantContextHolder.getTenantId();
        int pageNum = page.normalizedPageNum();
        int pageSize = page.normalizedPageSize();
        long offset = page.offset();

        long total = traceMapper.countSessions(tenantId);
        if (total == 0) {
            return PageResult.empty(page);
        }

        List<SessionSummary> list = traceMapper.selectSessionSummary(tenantId, offset, pageSize);
        return PageResult.of(list, total, pageNum, pageSize);
    }

    @Override
    public PageResult<TraceRecord> queryByUser(Long userId, PageRequest page) {
        LambdaQueryWrapper<TraceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceEntity::getUserId, userId)
            .orderByDesc(TraceEntity::getStartTime);

        Page<TraceEntity> pageResult = traceMapper.selectPage(
            new Page<>(page.getPageNum(), page.getPageSize()), wrapper);

        List<TraceRecord> records = pageResult.getRecords().stream()
            .map(this::toTraceRecord)
            .collect(Collectors.toList());

        return PageResult.of(records, pageResult.getTotal(), page.getPageNum(), page.getPageSize());
    }

    @Override
    public PageResult<TraceRecord> queryByAgent(Long agentId, PageRequest page) {
        LambdaQueryWrapper<TraceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraceEntity::getAgentId, agentId)
            .orderByDesc(TraceEntity::getStartTime);

        Page<TraceEntity> pageResult = traceMapper.selectPage(
            new Page<>(page.getPageNum(), page.getPageSize()), wrapper);

        List<TraceRecord> records = pageResult.getRecords().stream()
            .map(this::toTraceRecord)
            .collect(Collectors.toList());

        return PageResult.of(records, pageResult.getTotal(), page.getPageNum(), page.getPageSize());
    }

    @Override
    public ObserveStats stats(StatsQuery query) {
        LambdaQueryWrapper<TraceEntity> countWrapper = new LambdaQueryWrapper<>();
        applyScopeAndTimeFilters(countWrapper, query);

        long total = traceMapper.selectCount(countWrapper);

        LambdaQueryWrapper<TraceEntity> failWrapper = new LambdaQueryWrapper<>();
        applyScopeAndTimeFilters(failWrapper, query);
        failWrapper.eq(TraceEntity::getStatus, "FAILED");
        long failed = traceMapper.selectCount(failWrapper);

        double successRate = total > 0 ? (double)(total - failed) / total : 1.0;

        LambdaQueryWrapper<TraceEntity> detailWrapper = new LambdaQueryWrapper<>();
        applyScopeAndTimeFilters(detailWrapper, query);
        detailWrapper.isNotNull(TraceEntity::getDurationMs);
        detailWrapper.orderByDesc(TraceEntity::getDurationMs);

        List<TraceEntity> allTraces = traceMapper.selectList(detailWrapper);

        double avgDuration = allTraces.stream()
            .filter(t -> t.getDurationMs() != null)
            .mapToInt(TraceEntity::getDurationMs)
            .average()
            .orElse(0);

        List<Integer> durations = allTraces.stream()
            .filter(t -> t.getDurationMs() != null)
            .map(TraceEntity::getDurationMs)
            .sorted()
            .collect(Collectors.toList());

        double p95Duration = 0.0;
        if (!durations.isEmpty()) {
            int p95Index = (int) Math.ceil(durations.size() * 0.95) - 1;
            p95Duration = durations.get(Math.min(p95Index, durations.size() - 1));
        }

        long totalTokens = allTraces.stream()
            .filter(t -> t.getTokenInput() != null && t.getTokenOutput() != null)
            .mapToLong(t -> t.getTokenInput() + t.getTokenOutput())
            .sum();

        Map<String, Long> failureDistribution = allTraces.stream()
            .filter(t -> "FAILED".equals(t.getStatus()))
            .collect(Collectors.groupingBy(
                t -> t.getErrorMsg() != null ? t.getErrorMsg().substring(0, Math.min(50, t.getErrorMsg().length())) : "unknown",
                Collectors.counting()));

        return ObserveStats.builder()
            .totalTraces(total)
            .successRate(successRate)
            .avgDurationMs(avgDuration)
            .p95DurationMs(p95Duration)
            .totalTokens(totalTokens)
            .failureDistribution(failureDistribution)
            .build();
    }

    private void applyScopeAndTimeFilters(LambdaQueryWrapper<TraceEntity> wrapper, StatsQuery query) {
        if (query.getScope() != null && query.getScopeValue() != null) {
            switch (query.getScope()) {
                case "user" -> wrapper.eq(TraceEntity::getUserId, Long.valueOf(query.getScopeValue()));
                case "agent" -> wrapper.eq(TraceEntity::getAgentId, Long.valueOf(query.getScopeValue()));
                case "session" -> wrapper.eq(TraceEntity::getSessionId, query.getScopeValue());
                default -> { /* 不识别的 scope 忽略 */ }
            }
        }
        if (query.getStartTime() != null && query.getEndTime() != null) {
            wrapper.between(TraceEntity::getStartTime, query.getStartTime(), query.getEndTime());
        }
    }

    @Override
    public void cleanExpired(int retentionDays) {
        if (retentionDays <= 0) {
            retentionDays = properties.getRetentionDays();
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            LambdaQueryWrapper<TraceEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.lt(TraceEntity::getStartTime, cutoff);
            traceMapper.delete(wrapper);
            log.info("Cleaned up expired traces older than {} days", retentionDays);
        } catch (Exception e) {
            log.error("Failed to clean expired traces", e);
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void scheduledCleanExpired() {
        cleanExpired(properties.getRetentionDays());
    }

    private TraceEntity toTraceEntity(TraceRecord record) {
        TraceEntity entity = TraceEntity.builder()
            .traceId(record.getTraceId())
            .sessionId(record.getSessionId())
            .agentId(record.getAgentId())
            .agentName(record.getAgentName())
            .userId(record.getUserId())
            .userName(record.getUserName())
            .apiPath(record.getApiPath())
            .status(record.getStatus())
            .startTime(record.getStartTime())
            .endTime(record.getEndTime())
            .durationMs(record.getDurationMs() != null ? record.getDurationMs().intValue() : null)
            .tokenInput(record.getTokenInput())
            .tokenOutput(record.getTokenOutput())
            .errorMsg(record.getErrorMsg())
            .spanCount(record.getSpanCount())
            .sseEventCount(record.getSseEventCount())
            .build();
        entity.setTenantId(record.getTenantId());
        return entity;
    }

    private SpanEntity toSpanEntity(SpanRecord record) {
        return SpanEntity.builder()
            .traceId(record.getTraceId())
            .spanId(record.getSpanId())
            .parentSpanId(record.getParentSpanId())
            .spanType(record.getSpanType())
            .name(record.getName())
            .agentId(record.getAgentId())
            .userId(record.getUserId())
            .sessionId(record.getSessionId())
            .status(record.getStatus())
            .startTime(record.getStartTime())
            .endTime(record.getEndTime())
            .durationMs(record.getDurationMs() != null ? record.getDurationMs().intValue() : null)
            .inputSummary(record.getInputSummary())
            .outputSummary(record.getOutputSummary())
            .tokenInput(record.getTokenInput())
            .tokenOutput(record.getTokenOutput())
            .errorMsg(record.getErrorMsg())
            .meta(record.getMeta())
            .build();
    }

    private TraceRecord toTraceRecord(TraceEntity entity) {
        return TraceRecord.builder()
            .traceId(entity.getTraceId())
            .sessionId(entity.getSessionId())
            .agentId(entity.getAgentId())
            .agentName(entity.getAgentName())
            .userId(entity.getUserId())
            .userName(entity.getUserName())
            .apiPath(entity.getApiPath())
            .status(entity.getStatus())
            .startTime(entity.getStartTime())
            .endTime(entity.getEndTime())
            .durationMs(entity.getDurationMs() != null ? entity.getDurationMs().longValue() : null)
            .tokenInput(entity.getTokenInput())
            .tokenOutput(entity.getTokenOutput())
            .errorMsg(entity.getErrorMsg())
            .spanCount(entity.getSpanCount())
            .sseEventCount(entity.getSseEventCount())
            .tenantId(entity.getTenantId())
            .build();
    }

    private SpanRecord toSpanRecord(SpanEntity entity) {
        return SpanRecord.builder()
            .traceId(entity.getTraceId())
            .spanId(entity.getSpanId())
            .parentSpanId(entity.getParentSpanId())
            .spanType(entity.getSpanType())
            .name(entity.getName())
            .agentId(entity.getAgentId())
            .userId(entity.getUserId())
            .sessionId(entity.getSessionId())
            .status(entity.getStatus())
            .startTime(entity.getStartTime())
            .endTime(entity.getEndTime())
            .durationMs(entity.getDurationMs() != null ? entity.getDurationMs().longValue() : null)
            .inputSummary(entity.getInputSummary())
            .outputSummary(entity.getOutputSummary())
            .tokenInput(entity.getTokenInput())
            .tokenOutput(entity.getTokenOutput())
            .errorMsg(entity.getErrorMsg())
            .meta(entity.getMeta())
            .build();
    }

    /**
     * 从 LLM_CALL Span 的 meta JSON 中提取 modelName、cacheTokens 等字段。
     * <p>
     * meta JSON 结构（由 {@code AegisTraceMiddleware.buildModelCallMeta} 构建）：
     * <pre>
     * {
     *   "modelName": "豆包标准版",          // 顶层快捷字段
     *   "requestContext": { ... },
     *   "responseSummary": { ... },
     *   "context": { ... }
     * }
     * </pre>
     */
    private void enrichLlmCallMeta(SpanRecord span) {
        String metaStr = span.getMeta();
        if (metaStr == null || metaStr.isEmpty()) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = objectMapper.readValue(metaStr, new TypeReference<Map<String, Object>>() {});

            // modelName：优先顶层 → 降级 requestContext.modelName → 降级 span.name
            Object modelName = meta.get("modelName");
            if (modelName == null) {
                Object requestCtx = meta.get("requestContext");
                if (requestCtx instanceof Map) {
                    modelName = ((Map<?, ?>) requestCtx).get("modelName");
                }
            }
            if (modelName != null) {
                span.setModelName(String.valueOf(modelName));
                span.setDisplayName(toFriendlyModelName(span.getModelName()));
            }
        } catch (Exception e) {
            // meta 解析失败不影响主流程
            log.debug("Failed to parse LLM_CALL meta for spanId={}: {}", span.getSpanId(), e.getMessage());
        }
    }
}