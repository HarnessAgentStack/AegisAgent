package com.aegis.runtime.web;

import com.aegis.core.common.tenant.TenantContextHolder;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.Result;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.security.HitlHistory;
import com.aegis.core.domain.session.Session;
import com.aegis.core.enums.security.HitlAction;
import com.aegis.core.enums.session.SessionStatus;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;
import com.aegis.dal.mapper.security.HitlHistoryMapper;
import com.aegis.runtime.service.policy.HitlFlowService;
import com.aegis.runtime.service.conversation.InterruptSignalManager;
import com.aegis.runtime.service.conversation.SessionManageService;
import io.agentscope.core.event.ConfirmResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务控制接口。
 *
 * <p>对运行中任务提供控制能力：中断、重试、回退与 HITL（Human-In-The-Loop）恢复。
 * 与 {@link TaskController} 的任务执行流解耦，控制信号通过 {@link InterruptSignalManager}
 * 向运行链路下发控制事件。
 *
 * @author wang.zhen
 * @see TaskController
 * @see InterruptSignalManager
 */
@Slf4j
@RestController
@RequestMapping("/api/runtime/control")
@RequiredArgsConstructor
public class TaskControlController {

    private final InterruptSignalManager interruptSignalManager;
    private final SessionManageService sessionManageService;
    private final HitlFlowService hitlFlowService;
    // HITL 审计写入：approve/reject 均落库 sec_hitl_history
    private final HitlHistoryMapper hitlHistoryMapper;

    /**
     * 中断运行中任务。
     *
     * <p>通过 {@link InterruptSignalManager} 向会话的 Reactor Flux 链注入中断信号，
     * 触发 {@code takeUntilOther} 取消执行流。{@code doOnCancel} 回调将：
     * <ol>
     *   <li>保存已累积的部分输出到 session_message 表</li>
     *   <li>更新会话状态为 INTERRUPTED</li>
     * </ol>
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID（filter 注入）
     * @param userId    用户ID（filter 注入）
     * @return 操作结果（success=true 表示中断信号已发送）
     */
    @PostMapping("/{sessionId}/interrupt")
    public Mono<Result<Void>> interrupt(@PathVariable String sessionId,
                                         @TenantId Long tenantId,
                                         @UserId Long userId) {
        log.info("Task interrupt signal received: sessionId={}, tenantId={}, userId={}",
                sessionId, tenantId, userId);

        // 阻塞 DB 操作切换到 boundedElastic 线程池，避免阻塞 Netty event loop
        return Mono.<Result<Void>>fromCallable(() -> {
            TenantContextHolder.bind(tenantId);
            try {
            // 校验会话存在 + 归属（跨租户/横向越权防护）
            Session session = assertSessionOwnership(sessionId, tenantId, userId);

            if (!interruptSignalManager.isRunning(sessionId)) {
                log.warn("Session not running, cannot interrupt: sessionId={}, status={}",
                        sessionId, session.getStatus());
                return Result.fail(ResultCode.CONFLICT, "会话未在运行中，无法中断");
            }

            boolean sent = interruptSignalManager.interrupt(sessionId, "用户主动中断");
            if (sent) {
                log.info("Interrupt signal sent successfully: sessionId={}", sessionId);
                return Result.success(null);
            } else {
                return Result.fail(ResultCode.INTERNAL_ERROR, "中断信号发送失败");
            }
            } finally {
                TenantContextHolder.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 重试上一步失败节点。
     *
     * <p>将 INTERRUPTED 或 EXCEPTION 状态的会话恢复为 STARTED，由调用方重新发起 SSE 连接继续执行。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID（filter 注入）
     * @param userId    用户ID（filter 注入）
     * @return 操作结果
     */
    @PostMapping("/{sessionId}/retry")
    public Mono<Result<Void>> retry(@PathVariable String sessionId,
                                     @TenantId Long tenantId,
                                     @UserId Long userId) {
        log.info("Task retry signal received: sessionId={}, tenantId={}, userId={}",
                sessionId, tenantId, userId);

        // 阻塞 DB 操作切换到 boundedElastic 线程池，避免阻塞 Netty event loop
        return Mono.<Result<Void>>fromCallable(() -> {
            TenantContextHolder.bind(tenantId);
            try {
            Session session = assertSessionOwnership(sessionId, tenantId, userId);

            SessionStatus status = session.getStatus();
            if (status != SessionStatus.INTERRUPTED && status != SessionStatus.EXCEPTION) {
                return Result.fail(ResultCode.CONFLICT,
                        "仅 INTERRUPTED 或 EXCEPTION 状态可重试，当前: " + status);
            }

            // 恢复为 STARTED，由调用方重新发起 SSE 连接继续执行
            sessionManageService.updateStatus(sessionId, SessionStatus.STARTED);
            log.info("Session status updated to STARTED for retry: sessionId={}", sessionId);
            return Result.success(null);
            } finally {
                TenantContextHolder.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 回退至指定节点。
     *
     * <p>需要节点级执行图支持，当前返回 NOT_IMPLEMENTED。
     *
     * @param sessionId 会话ID
     * @return 操作结果（NOT_IMPLEMENTED）
     */
    @PostMapping("/{sessionId}/rollback")
    public Result<Void> rollback(@PathVariable String sessionId) {
        log.info("Task rollback signal received: sessionId={}", sessionId);
        return Result.fail(ResultCode.NOT_IMPLEMENTED,
                "回退功能需要节点级执行图支持，计划在 P1 阶段实现");
    }

    /**
     * HITL 审批通过，恢复挂起任务。
     *
     * <p>将 PAUSED 状态的会话恢复为 STARTED，由调用方重新发起 SSE 连接继续执行。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID（filter 注入）
     * @param userId    用户ID（filter 注入）
     * @return 操作结果
     */
    @PostMapping("/{sessionId}/hitl/approve")
    public Mono<Result<Void>> approveHitl(@PathVariable String sessionId,
                                           @TenantId Long tenantId,
                                           @UserId Long userId) {
        log.info("HITL approve signal received: sessionId={}, tenantId={}, userId={}",
                sessionId, tenantId, userId);

        // 阻塞 DB 操作切换到 boundedElastic 线程池，避免阻塞 Netty event loop
        return Mono.<Result<Void>>fromCallable(() -> {
            TenantContextHolder.bind(tenantId);
            try {
            Session session = assertSessionOwnership(sessionId, tenantId, userId);

            SessionStatus status = session.getStatus();
            // 允许 PAUSED 或 ENDED（有待审批HITL请求）状态下审批通过
            if (status != SessionStatus.PAUSED) {
                if (status == SessionStatus.ENDED && hitlFlowService.hasPendingRequest(sessionId)) {
                    log.warn("HITL 容错审批：会话状态为 ENDED 但存在待审批请求，允许恢复: sessionId={}", sessionId);
                } else {
                    return Result.fail(ResultCode.CONFLICT,
                            "仅 PAUSED 状态可审批通过，当前: " + status);
                }
            }

            // 审批通过前清理残留 sink，否则第二次请求被 isRunning 拦截
            interruptSignalManager.forceUnregister(sessionId);

            // 审批通过时构造 ConfirmResult 并保存到 Redis
            // 非自动放行场景下，saveHitlRequest 已在 hitl.request 事件处理中调用，
            // 此处 markApproved 将其转换为 ConfirmResult，供下一轮对话的 buildResumeMessages 使用
            List<ConfirmResult> results = hitlFlowService.markApproved(sessionId);
            if (results.isEmpty()) {
                log.warn("HITL 审批通过但无 ConfirmResult: sessionId={}，可能 saveHitlRequest 未被调用", sessionId);
            } else {
                log.info("HITL markApproved 成功: sessionId={}, confirmResultCount={}", sessionId, results.size());
            }

            // 清除状态缓存中的旧状态，确保新状态生效
            sessionManageService.updateStatus(sessionId, SessionStatus.STARTED);
            log.info("Session approved (status={}), status updated to STARTED: sessionId={}",
                    status, sessionId);

            // 写入 HITL 审计记录到 sec_hitl_history
            writeHitlHistory(sessionId, session, userId, HitlAction.APPROVE,
                    results.isEmpty() ? null : "toolCount=" + results.size());
            return Result.success(null);
            } finally {
                TenantContextHolder.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * HITL 审批驳回。
     *
     * <p>将 PAUSED 状态的会话置为 ENDED，任务终止。
     *
     * @param sessionId 会话ID
     * @param tenantId  租户ID（filter 注入）
     * @param userId    用户ID（filter 注入）
     * @return 操作结果
     */
    @PostMapping("/{sessionId}/hitl/reject")
    public Mono<Result<Void>> rejectHitl(@PathVariable String sessionId,
                                          @TenantId Long tenantId,
                                          @UserId Long userId) {
        log.info("HITL reject signal received: sessionId={}, tenantId={}, userId={}",
                sessionId, tenantId, userId);

        // 阻塞 DB 操作切换到 boundedElastic 线程池，避免阻塞 Netty event loop
        return Mono.<Result<Void>>fromCallable(() -> {
            TenantContextHolder.bind(tenantId);
            try {
            Session session = assertSessionOwnership(sessionId, tenantId, userId);

            if (session.getStatus() != SessionStatus.PAUSED) {
                return Result.fail(ResultCode.CONFLICT,
                        "仅 PAUSED 状态可审批驳回，当前: " + session.getStatus());
            }

            // 审批驳回清理残留 sink
            interruptSignalManager.forceUnregister(sessionId);

            // 审批驳回：清理 HITL 状态（请求数据 + 审批标记），避免残留
            hitlFlowService.clearHitlState(sessionId);
            log.info("HITL 驳回已清理状态: sessionId={}", sessionId);

            sessionManageService.updateStatus(sessionId, SessionStatus.ENDED);
            log.info("Session rejected, status updated to ENDED: sessionId={}", sessionId);

            // 写入 HITL 审计记录到 sec_hitl_history
            writeHitlHistory(sessionId, session, userId, HitlAction.REJECT, "用户驳回");
            return Result.success(null);
            } finally {
                TenantContextHolder.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ============ 内部方法 ============

    /**
     * 写入 HITL 审计记录到 sec_hitl_history 表，记录操作人、动作、时间与详情，支撑审计追溯。
     *
     * @param sessionId 会话 ID
     * @param session   会话实体（含 agentId、tenantId）
     * @param userId    操作人用户 ID
     * @param action    审批动作（APPROVE / REJECT）
     * @param detail    操作详情
     */
    private void writeHitlHistory(String sessionId, Session session,
                                  Long userId, HitlAction action, String detail) {
        try {
            // HitlHistory 继承 TenantEntity，tenantId 为父类字段，需用 setter 设置
            HitlHistory history = new HitlHistory();
            history.setTenantId(session.getTenantId());
            history.setAgentId(session.getAgentId());
            history.setSessionId(sessionId);
            history.setAction(action);
            history.setOperatorUserId(userId);
            history.setDetail(detail);
            history.setOccurTime(LocalDateTime.now());
            hitlHistoryMapper.insert(history);
            log.info("HITL 审计记录已写入: sessionId={}, action={}, userId={}",
                    sessionId, action, userId);
        } catch (Exception e) {
            // 审计写入失败不阻断主流程，仅记录错误日志
            log.error("HITL 审计记录写入失败（不阻断审批流程）: sessionId={}, action={}, error={}",
                    sessionId, action, e.getMessage(), e);
        }
    }

    /**
     * 校验会话存在且归属于当前租户与用户。
     *
     * @param sessionId 会话ID
     * @param tenantId  当前请求租户ID（filter 注入，非空）
     * @param userId    当前请求用户ID（filter 注入，非空）
     * @return 校验通过的会话实体
     * @throws BusinessException 会话不存在或归属校验失败时抛出
     */
    private Session assertSessionOwnership(String sessionId, Long tenantId, Long userId) {
        Session session = sessionManageService.getSession(sessionId, tenantId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在: " + sessionId);
        }
        // 横向越权防护：仅会话所有者可控制
        if (userId != null && !userId.equals(session.getUserId())) {
            log.warn("横向越权拦截: sessionId={}, requestUserId={}, ownerUserId={}",
                    sessionId, userId, session.getUserId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该会话");
        }
        return session;
    }
}
