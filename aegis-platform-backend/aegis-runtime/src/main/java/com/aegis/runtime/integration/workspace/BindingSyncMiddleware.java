package com.aegis.runtime.integration.workspace;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.workspace.AgentWorkspaceMaterial;
import com.aegis.runtime.service.agent.AssemblyResourceContext;
import com.aegis.runtime.service.agent.ResourceQueryService;
import com.aegis.runtime.service.workspace.WorkspaceMaterialService;
import com.aegis.runtime.integration.middleware.OrderedMiddleware;
import com.aegis.runtime.integration.workspace.WorkspaceMaterializer;
import com.aegis.runtime.integration.skill.AegisSkillRepository;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 绑定同步中间件：在 Agent 执行前检测 agent_binding 变更，触发增量物化。
 *
 * <p>作为 AgentScope {@link MiddlewareBase} 的实现，注入 HarnessAgent 的中间件链，
 * 在 onAgent 钩子中对比当前绑定指纹与已存储指纹，不匹配时调用
 * {@link WorkspaceMaterializer} 重新物化工作区文件。
 *
 * <h3>P0-01 修复：多用户隔离破坏</h3>
 * <p>原实现 {@code selectOne} 仅按 agentId 查询未带 userId，UNIVERSAL 智能体(USER scope)
 * 多用户场景下指纹记录会相互覆盖，可能导致物化到错误命名空间。
 * <p>修复：改为 {@code selectList} 查询所有匹配 agentId 的记录(含不同 userId)，
 * 对每条指纹不匹配的记录使用其自身的 userId 重新物化，确保用户隔离正确。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>从 agent 获取 agentId（可能为 null，需防御）</li>
 *   <li>计算当前绑定指纹</li>
 *   <li>查 agent_workspace_material 表（按 agentId 查全部记录，含不同 userId）</li>
 *   <li>对每条指纹不匹配的记录，使用其 userId 重新物化</li>
 *   <li>透传 next.apply(input)</li>
 * </ol>
 *
 * <h3>执行顺序</h3>
 * <p>{@link #order()} 返回 75，位于 Tenant(80) 之后、Intent(67)/RAG(65) 之前。
 * 绑定同步检测在租户校验通过后才执行，Security/Tenant 拦截的请求不会触发
 * computeFingerprint 与 findByAgentAndUser 的 DB I/O。
 *
 * <p>注意：本中间件已纳入 Aegis OrderedMiddleware 洋葱链（与 Tenant/ContentFilter/
 * Memory 等同链装配），由 {@code AegisMiddlewareChain} 统一排序注入 HarnessAgent。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BindingSyncMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final WorkspaceMaterializer materializer;
    private final WorkspaceMaterialService workspaceMaterialService;
    private final ResourceQueryService resourceQueryService;

    @Override
    public int order() {
        // order=75：位于 Tenant(80) 之后、Intent(67)/RAG(65) 之前。
        // 绑定同步检测在租户校验之后执行，被 Security/Tenant 拦截的请求不触发
        // computeFingerprint/findByAgentAndUser 的 DB I/O。
        return 75;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        // 1. 从 agent 获取 agentId（防御 null）
        String agentIdStr = agent.getAgentId();
        if (agentIdStr == null || agentIdStr.isBlank()) {
            log.debug("agent.getAgentId() 为空，跳过绑定同步检查");
            return next.apply(input);
        }

        long agentId;
        try {
            agentId = Long.parseLong(agentIdStr);
        } catch (NumberFormatException e) {
            log.warn("agentId 无法解析为 long，跳过绑定同步检查: agentId={}", agentIdStr);
            return next.apply(input);
        }

        // 2. 计算当前绑定指纹
        //    P2-1：从 RuntimeContext 读取装配期已加载的 enabledBindings，消除每轮重复 listEnabledBindings 查询
        String currentFingerprint;
        List<AgentBinding> ctxBindings = AssemblyResourceContext.enabledBindingsOf(ctx);
        if (ctxBindings != null && !ctxBindings.isEmpty()) {
            // 装配路径：直接从上下文绑定计算指纹（0 DB 查询）
            currentFingerprint = BindingFingerprinter.fingerprint(ctxBindings);
        } else {
            // 回退路径：RuntimeContext 无装配期属性（非装配入口或上下文缺失）
            try {
                currentFingerprint = materializer.computeFingerprint(agentId);
            } catch (Exception e) {
                log.warn("计算绑定指纹失败，跳过同步检查: agentId={}", agentId, e);
                return next.apply(input);
            }
        }

        // P1 AGT-09 修复：仅检查当前会话用户的指纹（从 RuntimeContext 获取 userId），
        //    不再同步查 DB 全量用户物化记录并同步物化；全量重物化改为异步触发，避免阻塞当前会话。
        Long currentUserId = null;
        try {
            String userIdStr = ctx.getUserId();
            if (userIdStr != null && !userIdStr.isBlank()) {
                currentUserId = Long.parseLong(userIdStr);
            }
        } catch (Exception e) {
            log.debug("userId 解析失败，降级为全量同步逻辑: agentId={}, error={}", agentId, e.getMessage());
        }

        // P2-1：agentType 从 RuntimeContext 读取（装配期已注入），消除 findAgentDefById 重复查询
        String agentType = ctx.get(AegisSkillRepository.CTX_AGENT_TYPE, String.class);
        if (agentType == null || agentType.isBlank()) {
            agentType = resolveAgentType(agentId); // 回退路径
        }

        if (currentUserId != null && currentUserId > 0) {
            // 仅检查当前用户的物化记录
            try {
                AgentWorkspaceMaterial stored = workspaceMaterialService.findByAgentAndUser(agentId, currentUserId);
                if (stored == null) {
                    log.info("当前用户无物化记录，触发首次物化: agentId={}, userId={}", agentId, currentUserId);
                    materializer.materialize(agentType, agentId, currentUserId, null);
                } else if (!currentFingerprint.equals(stored.getMaterialFingerprint())) {
                    log.info("当前用户绑定指纹变更，触发重新物化: agentId={}, userId={}, stored={}, current={}",
                            agentId, currentUserId, stored.getMaterialFingerprint(), currentFingerprint);
                    materializer.materialize(agentType, agentId, currentUserId, null);
                } else {
                    log.debug("当前用户绑定指纹未变更，跳过物化: agentId={}, userId={}", agentId, currentUserId);
                }
            } catch (Exception e) {
                log.error("当前用户绑定同步检查异常，继续执行 Agent: agentId={}, userId={}", agentId, currentUserId, e);
            }

            // P1 AGT-09 修复：全量重物化异步触发，不阻塞当前会话
            triggerAsyncFullRematerialize(agentId, currentFingerprint, agentType);
        } else {
            // userId 不可用时，降级为原全量同步逻辑
            syncRematerializeAll(agentId, currentFingerprint, agentType);
        }

        // 4. 透传 next
        return next.apply(input);
    }

    /**
     * P1 AGT-09 修复：异步触发全量重物化（不阻塞当前会话）。
     *
     * <p>查 agent_workspace_material 表全部记录，对指纹不匹配的记录使用其自身 userId 重新物化。
     * 通过 boundedElastic 调度器异步执行，失败仅记录日志。
     */
    private void triggerAsyncFullRematerialize(long agentId, String currentFingerprint, String agentType) {
        reactor.core.publisher.Mono.fromRunnable(
                        () -> syncRematerializeAll(agentId, currentFingerprint, agentType))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        e -> log.error("异步全量重物化失败: agentId={}", agentId, e));
    }

    /**
     * 全量同步重物化：查 agent_workspace_material 表全部记录，对指纹不匹配的重新物化。
     */
    private void syncRematerializeAll(long agentId, String currentFingerprint, String agentType) {
        try {
            List<AgentWorkspaceMaterial> storedList = workspaceMaterialService.listByAgentId(agentId);
            if (storedList == null || storedList.isEmpty()) {
                return;
            }
            for (AgentWorkspaceMaterial stored : storedList) {
                if (currentFingerprint.equals(stored.getMaterialFingerprint())) {
                    continue;
                }
                long userId = stored.getUserId() != null ? stored.getUserId() : 0L;
                log.info("全量重物化: agentId={}, userId={}, stored={}, current={}",
                        agentId, userId, stored.getMaterialFingerprint(), currentFingerprint);
                materializer.materialize(agentType, agentId, userId, null);
            }
        } catch (Exception e) {
            log.error("全量重物化异常: agentId={}", agentId, e);
        }
    }

    /**
     * 查询智能体类型。
     *
     * <p>从 agent_def 表加载 AgentDef，取其 agentType 字段。
     * 查询失败时默认返回 APPLICATION（应用智能体）。
     */
    private String resolveAgentType(long agentId) {
        try {
            AgentDef def = resourceQueryService.findAgentDefById(agentId);
            if (def != null && def.getAgentType() != null) {
                return def.getAgentType().name();
            }
        } catch (Exception e) {
            log.warn("查询智能体类型失败，使用默认值 APPLICATION: agentId={}", agentId, e);
        }
        return "APPLICATION";
    }
}
