package com.aegis.runtime.integration.middleware;

import com.aegis.core.domain.monitor.AuditLog;
import com.aegis.core.enums.monitor.AuditLogType;
import com.aegis.core.enums.monitor.AuditResult;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.metering.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.aegis.core.dto.security.PolicyDecision;

/**
 * 审计日志中间件（AgentScope onAgent 触发点实现）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>preCall（next 之前）：异步记录 SESSION 类型 CHAT_START（RECORDED，保留90天）</li>
 *   <li>事件流 doOnNext：实时消费 {@link AegisTaskContext#drainPendingAuditDecisions()}，
 *       将每次策略决策即时写入 POLICY_DECISION 审计日志（不等待会话结束）</li>
 *   <li>postCall（doFinally）：
 *     <ul>
 *       <li>SESSION 类型 CHAT_COMPLETE（SUCCESS/ALERT，保留90天）</li>
 *       <li>若 context.isBlocked()：SECURITY 类型 SECURITY_BLOCK（BLOCKED，保留365天）</li>
 *       <li>兜底 drain：异常/中断路径下未消费的决策在此补记，确保零遗漏</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>POLICY_DECISION 实时写入</b>：2026-08 修复——原实现在 doFinally 中一次性遍历
 * {@code policyDecisions}（Map 按 toolCode 去重），存在两个缺陷：① 同一工具被多次
 * 决策（如 DENY→APPROVE 升级）时仅保留最后一条；② 决策发生在会话中途却要等会话
 * 结束才落库。现改为 doOnNext 实时消费待审计队列，每次决策独立成一条审计记录。
 *
 * <p>审计写入失败不阻塞主流程，仅记录错误日志。
 *
 * <h3>执行顺序</h3>
 * <p>order=20。preCall 最后执行（确保所有校验已通过后记录 START），
 * postCall 最先执行（确保记录完整结果）。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisAuditLogMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final AuditLogService auditLogService;

    /** P1 MW-09 修复：用于安全序列化审计详情 JSON，避免手工拼接导致的转义问题 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public int order() {
        // P0 MW-01 修复：AS 降序执行，审计日志须记录完整结果故靠后
        return 20;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            log.warn("AegisAuditLog: AegisTaskContext 未注入 RuntimeContext，透传: agentId={}",
                    agent != null ? agent.getAgentId() : "null");
            return next.apply(input);
        }

        // preCall：异步记录 CHAT_START
        writeAuditLog(taskCtx, AuditLogType.SESSION, "CHAT_START",
                "AGENT", taskCtx.getAgentDef() != null ? taskCtx.getAgentDef().getAgentName() : null,
                AuditResult.RECORDED, 90,
                buildDetail(taskCtx));

        // P0 MW-02 修复：移除 doOnError，仅保留 doFinally（避免 error 时 writePostCall 双触发）
        return next.apply(input)
                .doOnNext(event -> drainAndWritePolicyDecisions(taskCtx))
                .doFinally(signalType -> writePostCall(taskCtx));
    }

    /**
     * 消费待审计策略决策队列，实时写入 POLICY_DECISION 审计日志。
     *
     * <p>在事件流 doOnNext 中调用：策略决策由安全中间件在 onActing 阶段产生
     * （事件流的内部环节），决策产生的下一帧事件流经此处时即被消费写入。
     */
    private void drainAndWritePolicyDecisions(AegisTaskContext ctx) {
        List<Map.Entry<String, PolicyDecision>> pending = ctx.drainPendingAuditDecisions();
        for (Map.Entry<String, PolicyDecision> entry : pending) {
            writePolicyDecisionLog(ctx, entry.getKey(), entry.getValue());
        }
    }

    /**
     * postCall：记录 CHAT_COMPLETE + SECURITY_BLOCK（若被拦截）+ 决策兜底补记。
     */
    private void writePostCall(AegisTaskContext ctx) {
        // CHAT_COMPLETE
        AuditResult chatResult = ctx.isBlocked() ? AuditResult.ALERT : AuditResult.SUCCESS;
        writeAuditLog(ctx, AuditLogType.SESSION, "CHAT_COMPLETE",
                "AGENT", ctx.getAgentDef() != null ? ctx.getAgentDef().getAgentName() : null,
                chatResult, 90,
                buildDetail(ctx));

        // SECURITY_BLOCK（若被拦截）
        if (ctx.isBlocked()) {
            Map<String, Object> blockDetail = new LinkedHashMap<>(1);
            blockDetail.put("blockReason", ctx.getBlockReason() != null ? ctx.getBlockReason() : "");
            writeAuditLog(ctx, AuditLogType.SECURITY, "SECURITY_BLOCK",
                    "AGENT", ctx.getAgentDef() != null ? ctx.getAgentDef().getAgentName() : null,
                    AuditResult.BLOCKED, 365,
                    toJson(blockDetail));
        }

        // 兜底：异常/中断路径下事件流提前终止，未消费的决策在此补记
        drainAndWritePolicyDecisions(ctx);
    }

    /**
     * 写入单条 POLICY_DECISION 审计日志。
     */
    private void writePolicyDecisionLog(AegisTaskContext ctx, String toolCode, PolicyDecision decision) {
        if (decision == null) {
            return;
        }
        Map<String, Object> decisionDetail = new LinkedHashMap<>(6);
        decisionDetail.put("toolCode", toolCode);
        decisionDetail.put("decision", decision.getDecision() != null ? decision.getDecision().name() : "UNKNOWN");
        decisionDetail.put("reason", decision.getReason());
        decisionDetail.put("policyId", decision.getPolicyId());
        decisionDetail.put("ruleId", decision.getRuleId());
        decisionDetail.put("resourceId", decision.getResourceId());
        writeAuditLog(ctx, AuditLogType.POLICY_DECISION, "POLICY_DECISION",
                "TOOL", toolCode,
                AuditResult.RECORDED, 90,
                toJson(decisionDetail));
    }

    /**
     * 异步写入审计日志，失败不抛异常。
     */
    private void writeAuditLog(AegisTaskContext ctx, AuditLogType logType, String operation,
                               String resourceType, String resourceName,
                               AuditResult result, int retentionDays, String detail) {
        Mono.fromRunnable(() -> {
            try {
                AuditLog audit = AuditLog.builder()
                        .logType(logType)
                        .userId(ctx.getUserId())
                        .operation(operation)
                        .resourceType(resourceType)
                        .resourceName(resourceName)
                        .result(result)
                        .ip(ctx.getClientIp())
                        .userAgent(ctx.getUserAgent())
                        .traceId(ctx.getTraceId())
                        .sessionId(ctx.getSessionId())
                        .agentId(ctx.getAgentId())
                        .retentionDays(retentionDays)
                        .occurTime(LocalDateTime.now())
                        .detail(detail)
                        .build();
                audit.setTenantId(ctx.getTenantId());
                auditLogService.writeAuditLog(audit);
                log.debug("AuditLog recorded: type={}, operation={}, sessionId={}, agentId={}",
                        logType, operation, ctx.getSessionId(), ctx.getAgentId());
            } catch (Exception e) {
                log.error("AuditLog write failed: type={}, operation={}, sessionId={}",
                        logType, operation, ctx.getSessionId(), e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 构建审计详情 JSON。
     *
     * <p>P1 MW-09 修复：原实现用 String.format 拼 JSON，无法处理字段值中的引号/反斜杠等特殊字符，
     * 存在 JSON 注入与解析失败风险。改用 Jackson 序列化 Map，保证合法 JSON 与正确转义。
     */
    private String buildDetail(AegisTaskContext ctx) {
        Map<String, Object> detail = new LinkedHashMap<>(4);
        detail.put("sessionId", ctx.getSessionId());
        detail.put("agentId", ctx.getAgentId());
        detail.put("tokenInput", ctx.getTokenInput());
        detail.put("tokenOutput", ctx.getTokenOutput());
        return toJson(detail);
    }

    /**
     * P1 MW-09 修复：使用 Jackson 将 Map 序列化为 JSON，失败时返回安全占位。
     */
    private String toJson(Map<String, Object> data) {
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("AuditLog detail 序列化失败，返回占位 JSON", e);
            return "{\"error\":\"detail serialize failed\"}";
        }
    }
}
