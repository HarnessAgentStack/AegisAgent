package com.aegis.runtime.web;

import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.dto.chat.ChatRequest;
import com.aegis.core.common.web.Result;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;
import com.aegis.core.web.annotation.DeptId;
import com.aegis.runtime.service.conversation.SessionManageService;
import com.aegis.runtime.service.conversation.TaskExecutionService;
import com.aegis.runtime.service.document.FileStorageService;
import com.aegis.core.dto.agent.AttachmentRef;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务控制器。
 *
 * <p>运行平面对外任务接口入口，承接智能体会话创建与消息发送，输出 SSE 流式响应。
 * 接收网关透传的租户/用户身份，调度 {@code TaskExecutionService} 执行任务。
 *
 * @author wang.zhen
 * @see ChatRequestValidator
 * @see TaskControlController
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/task")
@RequiredArgsConstructor
public class TaskController {

    private final ChatRequestValidator requestValidator;
    private final TaskExecutionService taskExecutionService;
    private final SessionManageService sessionManageService;
    private final FileStorageService fileStorageService;

    /**
     * 发送消息并启动任务（SSE 流式响应）。
     *
     * <p>接收网关注入的 X-Tenant-Id / X-User-Id / X-Dept-Id 头，
     * 与请求体合并为 {@link ChatRequest}，经 {@link ChatRequestValidator} 校验后
     * 调用 {@link TaskExecutionService#execute} 返回 SSE 事件流。
     *
     * @param request  对话请求（agentId / sessionId / message）
     * @param tenantId 租户ID（网关注入）
     * @param userId   用户ID（网关注入）
     * @param deptId   部门ID（网关注入，可选）
     * @param clientIp 客户端IP（网关注入）
     * @param userAgent User-Agent
     * @return SSE 事件流
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@Valid @RequestBody ChatRequest request,
                                               @TenantId Long tenantId,
                                               @UserId Long userId,
                                               @DeptId Long deptId,
                                               @RequestHeader(value = "X-Client-IP", required = false) String clientIp,
                                               @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                               @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // 注入网关上下文
        injectContext(request, tenantId, userId, deptId);

        com.aegis.core.dto.chat.SessionResourcesRef res = request.getResources();
        log.info("Chat request: agentId={}, sessionId={}, tenantId={}, userId={}, kbIds={}, mcpIds={}",
                request.getAgentId(), request.getSessionId(), tenantId, userId,
                res != null ? res.getKbIds() : null,
                res != null ? res.getMcpIds() : null);

        return requestValidator.validate(request)
                .thenMany(taskExecutionService.execute(request))
                .map(this::toServerSentEvent)
                .onErrorResume(ChatValidationException.class, e ->
                        Flux.just(toServerSentEvent(e.getErrorEvent()),
                                  toServerSentEvent(AgentEvent.of("done", Map.of()))));
    }

    /**
     * 非流式对话（用于测试与简单场景）。
     */
    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> chatSync(@Valid @RequestBody ChatRequest request,
                                               @TenantId Long tenantId,
                                               @UserId Long userId,
                                               @DeptId Long deptId) {
        injectContext(request, tenantId, userId, deptId);

        // 聚合 SSE 流为单一响应（使用线程安全容器）
        StringBuffer content = new StringBuffer();
        java.util.concurrent.ConcurrentHashMap<String, Object> meta = new java.util.concurrent.ConcurrentHashMap<>();
        return requestValidator.validate(request)
                .thenMany(taskExecutionService.execute(request))
                .doOnNext(event -> {
                    String eventType = event.getEvent();
                    if ("text.delta".equals(eventType) && event.getData() instanceof Map<?, ?> m) {
                        Object delta = m.get("delta");
                        if (delta != null) content.append(delta);
                    } else if ("done".equals(eventType) && event.getData() instanceof Map<?, ?> m) {
                        meta.putAll((Map<String, Object>) m);
                    } else if ("agent_start".equals(eventType) && event.getData() instanceof Map<?, ?> m) {
                        meta.put("sessionId", m.get("sessionId"));
                        meta.put("taskId", m.get("taskId"));
                    }
                })
                .then(Mono.fromSupplier(() -> {
                    Map<String, Object> resp = new HashMap<>();
                    resp.put("content", content.toString());
                    resp.putAll(meta);
                    return resp;
                }));
    }

    /**
     * 查询会话历史消息。
     */
    @PostMapping("/history")
    public Mono<Map<String, Object>> history(@Valid @RequestBody Map<String, Object> body,
                                              @TenantId Long tenantId) {
        String sessionId = (String) body.get("sessionId");
        Integer limit = body.get("limit") instanceof Number n ? n.intValue() : 20;
        return Mono.fromCallable(() -> {
            List<?> messages = sessionManageService.loadHistory(sessionId, tenantId, limit, null);
            Map<String, Object> resp = new HashMap<>();
            resp.put("sessionId", sessionId);
            resp.put("messages", messages);
            resp.put("count", messages.size());
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 查询用户会话列表。
     */
    @PostMapping("/sessions")
    public Mono<Map<String, Object>> sessions(@Valid @RequestBody Map<String, Object> body,
                                               @TenantId Long tenantId,
                                               @UserId Long userId) {
        Integer page = body.get("page") instanceof Number n ? n.intValue() : 1;
        Integer size = body.get("size") instanceof Number n ? n.intValue() : 20;
        // 兼容雪花ID精度：agentId 可能以 Number 或 String 形式传入
        Long agentId;
        Object agentIdVal = body.get("agentId");
        if (agentIdVal instanceof Number n) {
            agentId = n.longValue();
        } else if (agentIdVal instanceof String s && !s.isBlank()) {
            agentId = Long.parseLong(s.trim());
        } else {
            agentId = null;
        }
        return Mono.fromCallable(() -> {
            List<?> sessions = sessionManageService.listSessions(tenantId, userId, agentId, page, size);
            long total = sessionManageService.countSessions(tenantId, userId, agentId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("sessions", sessions);
            resp.put("total", total);
            resp.put("page", page);
            resp.put("size", size);
            return resp;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除会话（级联删除消息）。
     */
    @DeleteMapping("/session/{sessionId}")
    public Mono<Result<Void>> deleteSession(@PathVariable String sessionId,
                                             @RequestHeader("X-Tenant-Id") Long tenantId,
                                             @RequestHeader("X-User-Id") Long userId) {
        return Mono.<Result<Void>>fromCallable(() -> {
            sessionManageService.deleteSession(sessionId, tenantId, userId);
            return Result.success(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 删除单轮消息（用户消息+紧邻助手回复）。
     */
    @DeleteMapping("/session/{sessionId}/message/{messageId}")
    public Mono<Result<Void>> deleteMessage(@PathVariable String sessionId,
                                             @PathVariable Long messageId,
                                             @RequestHeader("X-Tenant-Id") Long tenantId,
                                             @RequestHeader("X-User-Id") Long userId) {
        return Mono.<Result<Void>>fromCallable(() -> {
            sessionManageService.deleteMessage(sessionId, messageId, tenantId, userId);
            return Result.success(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重新生成 AI 消息（任务 8）。
     *
     * <p>删除指定 AI 消息及其后所有消息，基于上一条 user 消息重新执行，返回 SSE 流式响应。
     * 含级联删除语义 + IDOR 防护 + 并发互斥校验（验收 #1/#7/#8）。
     *
     * @param body     请求体（agentId / sessionId / messageId）
     * @param tenantId 租户ID（网关注入）
     * @param userId   用户ID（网关注入）
     * @param deptId   部门ID（网关注入）
     * @return SSE 事件流
     */
    @PostMapping(value = "/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> regenerate(@RequestBody Map<String, Object> body,
                                                      @TenantId Long tenantId,
                                                      @UserId Long userId,
                                                      @DeptId Long deptId) {
        Long agentId = parseBodyLong(body.get("agentId"));
        String sessionId = (String) body.get("sessionId");
        Long messageId = parseBodyLong(body.get("messageId"));

        ChatRequest request = ChatRequest.builder()
                .agentId(agentId)
                .sessionId(sessionId)
                .build();
        injectContext(request, tenantId, userId, deptId);

        log.info("Regenerate request: agentId={}, sessionId={}, messageId={}, tenantId={}, userId={}",
                agentId, sessionId, messageId, tenantId, userId);

        return requestValidator.validate(request)
                .then(Mono.fromCallable(() -> {
                    String userMessage = sessionManageService.prepareRegenerate(
                            sessionId, messageId, tenantId, userId);
                    request.setMessage(userMessage);
                    return request;
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(taskExecutionService::execute)
                .map(this::toServerSentEvent)
                .onErrorResume(ChatValidationException.class, e ->
                        Flux.just(toServerSentEvent(e.getErrorEvent()),
                                  toServerSentEvent(AgentEvent.of("done", Map.of()))));
    }

    /**
     * 编辑用户消息并重新生成（任务 8）。
     *
     * <p>删除指定 user 消息及其后所有消息，持久化新文本重新执行，返回 SSE 流式响应。
     * 支持携带新附件/技能引用（验收 #2/#5）。
     *
     * @param body     请求体（agentId / sessionId / messageId / message / attachments? / skills? / resources?）
     * @param tenantId 租户ID（网关注入）
     * @param userId   用户ID（网关注入）
     * @param deptId   部门ID（网关注入）
     * @return SSE 事件流
     */
    @PostMapping(value = "/edit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> edit(@RequestBody Map<String, Object> body,
                                                @TenantId Long tenantId,
                                                @UserId Long userId,
                                                @DeptId Long deptId) {
        Long agentId = parseBodyLong(body.get("agentId"));
        String sessionId = (String) body.get("sessionId");
        Long messageId = parseBodyLong(body.get("messageId"));
        String newMessage = body.get("message") instanceof String s ? s : null;

        ChatRequest request = ChatRequest.builder()
                .agentId(agentId)
                .sessionId(sessionId)
                .message(newMessage)
                .build();
        if (body.get("attachments") != null) {
            request.setAttachments(JSON.parseObject(JSON.toJSONString(body.get("attachments")),
                    new com.alibaba.fastjson2.TypeReference<List<com.aegis.core.dto.agent.AttachmentRef>>() {}));
        }
        if (body.get("skills") != null) {
            request.setSkills(JSON.parseObject(JSON.toJSONString(body.get("skills")),
                    new com.alibaba.fastjson2.TypeReference<List<com.aegis.core.dto.chat.SkillRef>>() {}));
        }
        if (body.get("resources") != null) {
            request.setResources(JSON.parseObject(JSON.toJSONString(body.get("resources")),
                    com.aegis.core.dto.chat.SessionResourcesRef.class));
        }
        injectContext(request, tenantId, userId, deptId);

        log.info("Edit request: agentId={}, sessionId={}, messageId={}, tenantId={}, userId={}",
                agentId, sessionId, messageId, tenantId, userId);

        return requestValidator.validate(request)
                .then(Mono.fromCallable(() -> {
                    sessionManageService.prepareEdit(
                            sessionId, messageId, tenantId, userId);
                    return request;
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMapMany(taskExecutionService::execute)
                .map(this::toServerSentEvent)
                .onErrorResume(ChatValidationException.class, e ->
                        Flux.just(toServerSentEvent(e.getErrorEvent()),
                                  toServerSentEvent(AgentEvent.of("done", Map.of()))));
    }

    // ============ 内部方法 ============

    /**
     * 注入网关上下文到 ChatRequest（P2-7①：身份三元组强类型注入一次）。
     *
     * <p>以网关 Header 解析值<strong>无条件覆盖</strong>请求体同名字段（即使 Header 为
     * null 也覆盖为 null），杜绝客户端在请求体伪造 tenantId/userId/deptId 透传下游；
     * {@code context} Map 不再承载身份，保留为 API 透传扩展位。
     */
    private void injectContext(ChatRequest request, Long tenantId, Long userId, Long deptId) {
        request.setTenantId(tenantId);
        request.setUserId(userId);
        request.setDeptId(deptId);
    }

    /**
     * 从请求体解析 Long 值（兼容 Number / String / null）。
     */
    private Long parseBodyLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 将 AgentEvent 转为 SSE 帧。
     */
    private ServerSentEvent<String> toServerSentEvent(AgentEvent event) {
        return ServerSentEvent.<String>builder()
                .event(event.getEvent())
                .data(JSON.toJSONString(event))
                .build();
    }
}
