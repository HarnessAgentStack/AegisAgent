package com.aegis.runtime.integration.middleware;

import com.aegis.core.domain.security.SensitiveWord;
import com.aegis.core.enums.security.SensitiveAction;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.policy.SensitiveWordService;
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

import java.util.List;
import java.util.function.Function;

/**
 * 内容过滤中间件（AgentScope onSystemPrompt 触发点实现）。
 *
 * <p><b>P0-10 改造</b>：在 LLM 系统提示词构建时变换式介入，符合 AgentScope
 * 推荐的 prompt 改写场景。
 *
 * <h3>onSystemPrompt 触发点优势</h3>
 * <p>{@code onSystemPrompt(Agent agent, String prompt)} 返回 {@code Mono<String>}，
 * 是 AgentScope 5 类触发点中唯一的"变换式"（Transformer）触发点。本中间件在
 * 系统提示词中追加敏感词过滤指令，让 LLM 在生成时遵守合规约束，无需修改用户输入。
 *
 * <h3>策略</h3>
 * <ol>
 *   <li>加载所有启用的敏感词（不区分 scope，因为 system prompt 是模型行为约束层）</li>
 *   <li>若存在 BLOCK 模式敏感词，追加合规指令禁止响应</li>
 *   <li>若存在 REPLACE 模式敏感词，追加合规指令替换占位符</li>
 *   <li>MARK 模式仅记录，不修改 prompt</li>
 * </ol>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisContentFilterMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final SensitiveWordService sensitiveWordService;

    @Override
    public int order() {
        // P0 MW-01 修复：AS 降序执行，内容过滤须在配额检查之后执行
        return 60;
    }

    /**
     * P0 SEC-01 修复：preCall 阶段对用户输入做实际敏感词匹配。
     *
     * <p>BLOCK 模式：用户输入含敏感词时直接拦截（Flux.empty）；
     * REPLACE 模式：交由 onSystemPrompt 追加指令（LLM 侧脱敏）。
     *
     * @param agent  智能体
     * @param ctx    运行时上下文
     * @param input  智能体输入
     * @param next   下游中间件
     * @return 事件流
     */
    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                     Function<AgentInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(input);
        }
        String userMessage = taskCtx.getUserMessage();
        if (userMessage == null || userMessage.isEmpty()) {
            return next.apply(input);
        }
        try {
            // P1 MW-10 修复：按租户隔离查询敏感词，避免租户间配置互相干扰
            List<SensitiveWord> words = sensitiveWordService.listEnabledWords(taskCtx.getTenantId());
            if (words == null || words.isEmpty()) {
                return next.apply(input);
            }
            // P0 SEC-01 修复：BLOCK 模式实际匹配用户输入
            String lowerMsg = userMessage.toLowerCase();
            for (SensitiveWord word : words) {
                if (word.getAction() == SensitiveAction.BLOCK
                        && word.getWord() != null && !word.getWord().isEmpty()
                        && lowerMsg.contains(word.getWord().toLowerCase())) {
                    taskCtx.setBlocked(true);
                    taskCtx.setBlockReason("用户输入包含敏感词 [BLOCK]: " + word.getWord());
                    log.warn("ContentFilter BLOCK 拦截: agentId={}, word={}",
                            agent != null ? agent.getAgentId() : "null", word.getWord());
                    return Flux.<AgentEvent>empty();
                }
            }
            // INPUT 内容安全评估由 AegisSecurityMiddleware.onAgent(order=90) 统一负责，此处不重复评估
        } catch (Exception e) {
            log.error("ContentFilter onAgent 异常, 透传", e);
        }
        return next.apply(input);
    }

    /**
     * 变换式：在 system prompt 中追加合规指令。
     *
     * <p>策略：加载所有启用的敏感词，若存在 BLOCK/REPLACE 模式且 prompt 未包含
     * 合规指令标记，则追加合规指令让 LLM 在生成时遵守。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(prompt);
        }
        try {
            // P1 MW-10 修复：按租户隔离查询敏感词，从 AegisTaskContext 获取 tenantId
            AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
            List<SensitiveWord> words = sensitiveWordService.listEnabledWords(
                    taskCtx != null ? taskCtx.getTenantId() : null);
            if (words == null || words.isEmpty()) {
                return Mono.just(prompt);
            }

            boolean hasBlock = words.stream()
                    .anyMatch(w -> w.getAction() == SensitiveAction.BLOCK
                            && w.getWord() != null && !w.getWord().isEmpty());
            boolean hasReplace = words.stream()
                    .anyMatch(w -> w.getAction() == SensitiveAction.REPLACE
                            && w.getWord() != null && !w.getWord().isEmpty());

            if (!hasBlock && !hasReplace) {
                return Mono.just(prompt);
            }

            // 防止重复追加
            if (prompt.contains("【AEGIS_CONTENT_FILTER】")) {
                return Mono.just(prompt);
            }

            StringBuilder directive = new StringBuilder("\n\n【AEGIS_CONTENT_FILTER】\n");
            if (hasBlock) {
                directive.append("- 禁止输出包含敏感词的内容\n");
            }
            if (hasReplace) {
                directive.append("- 若必须引用敏感词，请用 *** 占位符替换\n");
            }
            log.debug("ContentFilter onSystemPrompt: agentId={}, appended directive",
                    agent != null ? agent.getAgentId() : "null");
            return Mono.just(prompt + directive.toString());
        } catch (Exception e) {
            log.error("ContentFilter onSystemPrompt 异常, 透传原 prompt", e);
            return Mono.just(prompt);
        }
    }

    // onActing 已删除：工具入参/结果策略评估由 AegisSecurityMiddleware.onActing(order=30) 的 evaluateToolPolicy 统一负责
}
