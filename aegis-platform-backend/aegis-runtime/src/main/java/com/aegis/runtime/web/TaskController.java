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
 * 任务接口入口：接收对话请求，经 {@link ChatRequestValidator} 校验后
 * 交由 {@link TaskExecutionService} 执行，输出 SSE 事件流。
 *
 * <p>租户/用户/部门身份由网关 Header 注入，不接受请求体传递。
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
     * 发送消息并流式返回执行事件。
     *
     * <p>校验失败不中断连接，而是补发 error + done 事件，保证 SSE 流正常闭合。
     *
     * @param request     对话请求（agentId / sessionId / message）
     * @param tenantId    租户ID（网关注入）
     * @param userId      用户ID（网关注入）
     * @param deptId      部门ID（网关注入，可选）
     * @param clientIp    客户端IP
     * @param userAgent   客户端 User-Agent
     * @param lastEventId 断线重连的最后事件ID（当前未启用，预留）
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
     * 非流式对话：聚合 SSE 流为单一 JSON 响应（测试与简单场景）。
     *
     * <p>text.delta 的 delta 累加为 content；agent_start 的 sessionId/taskId
     * 与 done 事件的其余字段合并进响应。
     */
    @PostMapping(value = "/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Object>> chatSync(@Valid @RequestBody ChatRequest request,
                                               @TenantId Long tenantId,
                                               @UserId Long userId,
                                               @DeptId Long deptId) {
        injectContext(request, tenantId, userId, deptId);

        // doOnNext 可能在不同线程执行，容器必须线程安全
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
     * 重新生成 AI 消息：级联删除指定消息及其后所有消息，基于上一条 user 消息重新执行。
     *
     * <p>会话归属（IDOR 防护）由 {@code prepareRegenerate} 校验，并发互斥由前置校验覆盖。
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
     * 编辑用户消息并重新生成：级联删除原消息及其后所有消息，持久化新文本后重新执行。
     *
     * <p>会话归属（IDOR 防护）由 {@code prepareEdit} 校验；新附件/技能/资源引用一并生效。
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
     * 用网关 Header 的身份值覆盖请求体同名字段。
     *
     * <p>无条件覆盖（Header 为 null 时也覆盖为 null），杜绝客户端在请求体伪造
     * tenantId/userId/deptId 透传下游。
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
     * 事件转 SSE 帧：事件名作 event，事件整体序列化为 data。
     */
    private ServerSentEvent<String> toServerSentEvent(AgentEvent event) {
        return ServerSentEvent.<String>builder()
                .event(event.getEvent())
                .data(JSON.toJSONString(event))
                .build();
    }
}
