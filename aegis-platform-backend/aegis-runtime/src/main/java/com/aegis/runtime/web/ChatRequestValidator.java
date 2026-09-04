package com.aegis.runtime.web;

import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.dto.agent.AgentEvent;
import com.aegis.core.dto.agent.AttachmentRef;
import com.aegis.core.dto.chat.ChatRequest;
import com.aegis.core.domain.session.Session;
import com.aegis.core.enums.session.SessionStatus;
import com.aegis.runtime.service.conversation.InterruptSignalManager;
import com.aegis.runtime.service.conversation.SessionManageService;
import com.aegis.runtime.service.document.FileStorageService;
import com.aegis.runtime.service.security.UserStatusCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.util.List;
import java.util.Map;

/**
 * 对话请求前置校验（Web 层门控）。
 *
 * <p>校验项：参数完整性 → 身份合法性 → 附件（数量/大小/归属）→ 用户状态 →
 * 并发互斥 → replyId 去重。通过返回 {@code Mono.empty()}，失败抛 {@link ChatValidationException}。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRequestValidator {

    private final InterruptSignalManager interruptSignalManager;
    private final SessionManageService sessionManageService;
    private final UnifiedJedis jedis;
    private final FileStorageService fileStorageService;
    private final UserStatusCache userStatusCache;

    private static final String REPLY_DEDUP_KEY_PREFIX = "reply:";
    private static final long REPLY_DEDUP_TTL_SECONDS = 300;

    /** 附件数量上限 */
    private static final int MAX_ATTACHMENTS = 10;
    /** 单文件大小上限（50MB） */
    private static final int MAX_FILE_SIZE_KB = 50 * 1024;

    /**
     * 执行全部校验。前 3 项纯内存同步执行；用户状态 / 并发 / 附件归属需查 DB，切 boundedElastic。
     *
     * @param request 对话请求
     * @return 校验通过返回 {@code Mono.empty()}
     */
    public Mono<Void> validate(ChatRequest request) {
        // 1. 参数完整性校验
        AgentEvent paramError = checkParameters(request);
        if (paramError != null) {
            return Mono.error(new ChatValidationException(paramError));
        }

        // 2. 身份合法性校验（fail-closed）
        AgentEvent authError = checkIdentity(request);
        if (authError != null) {
            return Mono.error(new ChatValidationException(authError));
        }

        // 3. 附件快速校验（数量 + 大小，纯内存）
        AgentEvent attachmentQuickError = checkAttachmentsQuick(request);
        if (attachmentQuickError != null) {
            return Mono.error(new ChatValidationException(attachmentQuickError));
        }

        // 4. 用户状态 + 并发互斥 + 附件归属（需 DB 查询，切到 boundedElastic）
        return Mono.fromCallable(() -> {
                    // 本方法早于 TaskExecutionService 绑定租户，需手动绑定供 MyBatis 租户插件读取。
                    // 不绑定时：fail-closed 插件直接报错，或 UserStatusCache fail-open 导致禁用拦截失效。
                    // 必须 finally 清理，否则线程归还池后残留租户会污染下一请求。
                    TenantContextHolder.bind(request.getTenantId());
                    try {
                        checkUserStatus(request);
                        checkConcurrency(request);
                        checkAttachmentsOwnership(request);
                        return (Void) null;
                    } finally {
                        TenantContextHolder.clear();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(v -> checkReplyDedup(request));
    }

    /**
     * 参数校验：agentId 必填；message 仅在新会话（无 sessionId）时必填——
     * HITL 恢复场景由 {@code HitlFlowService.buildResumeMessages} 注入内容，允许空消息。
     */
    private AgentEvent checkParameters(ChatRequest request) {
        if (request.getAgentId() == null) {
            return AgentEvent.of("error", Map.of("code", "PARAM_ERROR", "message", "agentId 不能为空"));
        }
        boolean isHitlResume = request.getSessionId() != null && !request.getSessionId().isEmpty();
        if (!isHitlResume && (request.getMessage() == null || request.getMessage().isEmpty())) {
            return AgentEvent.of("error", Map.of("code", "PARAM_ERROR", "message", "消息内容不能为空"));
        }
        return null;
    }

    /**
     * 身份校验：tenantId / userId > 0（fail-closed）。
     */
    private AgentEvent checkIdentity(ChatRequest request) {
        if (request.getTenantId() == null || request.getTenantId() <= 0) {
            return AgentEvent.of("error", Map.of("code", "UNAUTHORIZED", "message", "tenantId 缺失或非法"));
        }
        if (request.getUserId() == null || request.getUserId() <= 0) {
            return AgentEvent.of("error", Map.of("code", "UNAUTHORIZED", "message", "userId 缺失或非法"));
        }
        return null;
    }

    /**
     * 附件快速校验（纯内存，无需 DB）：数量 + 大小。
     */
    private AgentEvent checkAttachmentsQuick(ChatRequest request) {
        List<AttachmentRef> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return null;
        }

        // 数量上限
        if (attachments.size() > MAX_ATTACHMENTS) {
            return AgentEvent.of("error", Map.of("code", "ATTACHMENT_TOO_MANY",
                    "message", "附件数量超过上限（最多 " + MAX_ATTACHMENTS + " 个）"));
        }

        // 单文件大小上限
        for (AttachmentRef att : attachments) {
            if (att.getSizeKB() != null && att.getSizeKB() > MAX_FILE_SIZE_KB) {
                return AgentEvent.of("error", Map.of("code", "ATTACHMENT_TOO_LARGE",
                        "message", "附件大小超过上限（单文件最多 50MB）",
                        "filename", att.getName() != null ? att.getName() : "unknown"));
            }
        }

        return null;
    }

    /**
     * 用户状态校验：禁用用户拒绝对话（Caffeine 缓存 60s，admin 禁用后最长 60s 生效）。
     */
    private void checkUserStatus(ChatRequest request) {
        if (userStatusCache.isDisabled(request.getUserId())) {
            log.warn("用户已禁用，拒绝对话: userId={}, tenantId={}", request.getUserId(), request.getTenantId());
            throw new ChatValidationException(AgentEvent.of("error", Map.of(
                    "code", "FORBIDDEN",
                    "message", "用户已被禁用，请联系管理员")));
        }
    }

    /**
     * 附件归属校验：先按 (fileId, tenantId, userId) 精确匹配；失败时二次查询区分
     * "不存在"与"跨租户/跨用户越权"，避免向客户端泄露归属信息。
     *
     * @throws ChatValidationException 校验失败时抛出
     */
    private void checkAttachmentsOwnership(ChatRequest request) {
        List<AttachmentRef> attachments = request.getAttachments();
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        Long tenantId = request.getTenantId();
        Long userId = request.getUserId();

        for (AttachmentRef att : attachments) {
            if (att.getFileId() == null || att.getFileId().isEmpty()) {
                log.warn("附件缺少 fileId: name={}", att.getName());
                throw new ChatValidationException(AgentEvent.of("error", Map.of(
                        "code", "ATTACHMENT_INVALID",
                        "message", "附件缺少 fileId，请重新上传",
                        "filename", att.getName() != null ? att.getName() : "unknown")));
            }

            // 精确匹配（存在 + 归属）；不匹配时二次查询区分错误类型
            AttachmentRef actualRef = fileStorageService.getRef(att.getFileId(), tenantId, userId);
            if (actualRef == null) {
                AttachmentRef rawRef = fileStorageService.getRef(att.getFileId());
                if (rawRef == null) {
                    log.warn("附件 fileId 不存在: fileId={}", att.getFileId());
                    throw new ChatValidationException(AgentEvent.of("error", Map.of(
                            "code", "ATTACHMENT_NOT_FOUND",
                            "message", "附件不存在（fileId=" + att.getFileId() + "），请重新上传")));
                } else {
                    log.warn("附件归属校验失败（跨租户/跨用户）: fileId={}, requestTenantId={}, requestUserId={}, ownerTenantId={}, ownerUserId={}",
                            att.getFileId(), tenantId, userId, rawRef.getTenantId(), rawRef.getUserId());
                    throw new ChatValidationException(AgentEvent.of("error", Map.of(
                            "code", "ATTACHMENT_FORBIDDEN",
                            "message", "附件归属校验失败，无权访问该附件")));
                }
            }
        }
    }

    /**
     * 并发互斥：同一会话已有活跃 SSE 流时拒绝新请求。
     *
     * <p>放行条件（任一成立即放行）：无活跃 sink、DB 会话处于非活跃态、心跳超 30s 未更新。
     * 三者皆不成立才判为真正运行中的任务并拒绝，返回 {@code recoverable=true}
     * 供前端提示用户先中断。
     */
    private void checkConcurrency(ChatRequest request) {
        String existingSessionId = request.getSessionId();
        if (existingSessionId == null || existingSessionId.isEmpty()) {
            return;
        }

        // 先清理一次僵尸 sink（心跳超时），防止客户端断连后 sink 残留误判
        interruptSignalManager.cleanupStaleSinks();

        if (!interruptSignalManager.isRunning(existingSessionId)) {
            return;
        }

        // 放行条件 1：sink 存在但 DB 会话已非活跃 → 清理残留 sink
        Session existingSession = sessionManageService.getSession(existingSessionId, request.getTenantId());
        if (existingSession != null && isInactiveStatus(existingSession.getStatus())) {
            log.warn("清理非活跃会话残留 sink 后放行: sessionId={}, status={}",
                    existingSessionId, existingSession.getStatus());
            interruptSignalManager.forceUnregister(existingSessionId);
            return;
        }

        // 放行条件 2：心跳超 30s 未更新 → 视为卡死，强制清理
        long heartbeatAge = interruptSignalManager.getHeartbeatAgeMs(existingSessionId);
        if (heartbeatAge > 30_000) {
            log.warn("心跳超时（{}ms），强制清理残留 sink 后放行: sessionId={}",
                    heartbeatAge, existingSessionId);
            interruptSignalManager.forceUnregister(existingSessionId);
            return;
        }

        // 三个条件都不成立 → 真正运行中的任务，拒绝
        log.warn("并发 SSE 流拒绝: sessionId={}, userId={}, agentId={}, heartbeatAge={}ms",
                existingSessionId, request.getUserId(), request.getAgentId(), heartbeatAge);
        throw new ChatValidationException(AgentEvent.of("error", Map.of(
                "code", "CONFLICT",
                "message", "会话已有进行中的任务，请等待完成或先中断后再发起新请求",
                "recoverable", true,
                "sessionId", existingSessionId)));
    }

    /**
     * replyId 去重：Redis SETNX，防止网络抖动重试导致重复落库。
     */
    private Mono<Void> checkReplyDedup(ChatRequest request) {
        String replyId = request.getReplyId();
        if (replyId == null || replyId.isEmpty()) {
            return Mono.empty();
        }

        String existingSessionId = request.getSessionId();
        // dedup key 携带 tenantId 段：新会话（"new"）场景下不同租户撞 replyId 不再互相拒绝
        Long tenantId = request.getTenantId();
        String tenantSegment = tenantId != null ? "t" + tenantId + ":" : "";
        String dedupKey = REPLY_DEDUP_KEY_PREFIX + tenantSegment
                + (existingSessionId != null ? existingSessionId : "new") + ":" + replyId;

        return Mono.fromCallable(() -> {
                    try {
                        String setResult = jedis.set(dedupKey, "1",
                                SetParams.setParams().nx().ex(REPLY_DEDUP_TTL_SECONDS));
                        if (setResult == null) {
                            log.warn("replyId 重复请求被拒绝: replyId={}, sessionId={}", replyId, existingSessionId);
                            throw new ChatValidationException(AgentEvent.of("error", Map.of(
                                    "code", "DUPLICATE_REQUEST",
                                    "message", "重复请求已处理，请勿重复提交")));
                        }
                    } catch (ChatValidationException e) {
                        throw e;
                    } catch (Exception e) {
                        // Redis 不可用时降级：不阻断主流程
                        log.warn("replyId 去重校验失败（Redis 不可用，降级放行）: replyId={}", replyId, e);
                    }
                    return (Void) null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private boolean isInactiveStatus(SessionStatus status) {
        return status == SessionStatus.PAUSED
                || status == SessionStatus.ENDED
                || status == SessionStatus.EXCEPTION
                || status == SessionStatus.INTERRUPTED
                || status == SessionStatus.EXPIRED;
    }
}
