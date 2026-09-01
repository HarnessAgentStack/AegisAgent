package com.aegis.runtime.integration.middleware;

import com.aegis.core.domain.security.HitlNode;
import com.aegis.core.dto.security.BuiltinToolRiskConfig;
import com.aegis.core.dto.security.PolicyDecision;
import com.aegis.core.dto.security.SecurityPolicyContext;
import com.aegis.core.dto.security.ToolRiskInfo;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.runtime.service.conversation.AegisTaskContext;
import com.aegis.runtime.service.conversation.ContentAdapter;
import com.aegis.runtime.service.policy.AegisSecurityPolicyEngine;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/**
 * Aegis 安全中间件，在 4 个拦截点执行安全策略评估。
 *
 * <p>基于 {@link AegisSecurityPolicyEngine} 统一引擎，在 AgentScope 洋葱链的 4 个拦截点
 * 执行安全策略评估，覆盖输入→系统提示→模型调用→工具执行全链路。
 *
 * <h3>拦截点覆盖</h3>
 * <ul>
 *   <li>{@code onAgent(order=90)} — 输入初筛（内容安全评估）</li>
 *   <li>{@code onSystemPrompt(order=55)} — 注入安全策略指令</li>
 *   <li>{@code onModelCall(order=40)} — 模型安全路由（STRICT+L4→本地加密）</li>
 *   <li>{@code onActing(order=30)} — 工具调用策略判定 + 出站策略联动</li>
 * </ul>
 *
 * <h3>执行顺序（order 降序 = AgentScope 洋葱链外层先执行）</h3>
 * <pre>
 * order=90  onAgent        输入初筛（内容安全）
 * order=55  onSystemPrompt 注入策略指令到系统提示词
 * order=40  onModelCall    模型路由（STRICT→本地加密）
 * order=30  onActing       工具调用 ALLOW/ASK/REJECT
 * </pre>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisSecurityMiddleware implements MiddlewareBase, OrderedMiddleware {

    private final AegisSecurityPolicyEngine securityPolicyEngine;
    // 通配符 HITL 规则覆盖：onActing 中检查通配符 HitlNode 条件
    private final AegisHitlRuleLoader hitlRuleLoader;

    @Override
    public int order() {
        return 90;
    }

    // ==================== onAgent: 输入初筛 ====================

    /**
     * onAgent 拦截点：用户输入安全初筛。
     *
     * <p>执行内容安全策略评估（敏感词 BLOCK/REPLACE），BLOCK 直接拦截返回空流，
     * REPLACE 脱敏后放行。同时检查治理档位 × 资源级别联动。
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
            // 拆分手输文本与附件区（2026-09-01 用户确认的降级脱敏策略）：
            // 附件解析文本（PDF/Word）常含安全合规术语（如"降低被黑客攻击的风险"），
            // 按 BLOCK 拦截会误伤正常文档处理。故：手输部分维持完整检测（BLOCK 拦截），
            // 附件部分命中 BLOCK 词时降级为 *** 替换脱敏后放行（敏感词不透传 LLM）。
            int markerIdx = userMessage.indexOf(ContentAdapter.ATTACHMENT_SECTION_MARKER);
            String typedPart = markerIdx >= 0
                    ? userMessage.substring(0, markerIdx) : userMessage;
            String attachmentPart = markerIdx >= 0
                    ? userMessage.substring(markerIdx) : null;

            // 1. 手输部分：完整内容安全评估（BLOCK 拦截 / REPLACE 脱敏）
            SecurityPolicyContext policyCtx = SecurityPolicyContext.builder()
                    .tenantId(taskCtx.getTenantId())
                    .agentId(taskCtx.getAgentId())
                    .governanceTier(taskCtx.getGovernanceTier())
                    .action(SecurityPolicyContext.Action.INPUT)
                    .content(typedPart)
                    .contentSummary(typedPart.length() > 200 ? typedPart.substring(0, 200) : typedPart)
                    .sessionId(taskCtx.getSessionId())
                    .traceId(taskCtx.getTraceId())
                    .build();

            PolicyDecision decision = securityPolicyEngine.evaluateContentPolicy(policyCtx);

            if (decision.isReject()) {
                taskCtx.setBlocked(true);
                taskCtx.setBlockReason(decision.getReason());
                log.warn("SecurityMiddleware onAgent BLOCK: agentId={}, reason={}",
                        agent != null ? agent.getAgentId() : "null", decision.getReason());
                return Flux.<AgentEvent>empty();
            }

            String finalTyped = typedPart;
            if (decision.isMask() && decision.getMaskedContent() != null) {
                finalTyped = decision.getMaskedContent();
                log.info("SecurityMiddleware onAgent MASK: agentId={}, masked",
                        agent != null ? agent.getAgentId() : "null");
            }

            // 2. 附件部分：降级脱敏评估（BLOCK 词替换 *** 后放行）
            String finalAttachment = attachmentPart;
            if (attachmentPart != null) {
                SecurityPolicyContext attCtx = SecurityPolicyContext.builder()
                        .tenantId(taskCtx.getTenantId())
                        .agentId(taskCtx.getAgentId())
                        .governanceTier(taskCtx.getGovernanceTier())
                        .action(SecurityPolicyContext.Action.INPUT)
                        .content(attachmentPart)
                        .sessionId(taskCtx.getSessionId())
                        .traceId(taskCtx.getTraceId())
                        .build();
                PolicyDecision attDecision = securityPolicyEngine.evaluateAttachmentContentPolicy(attCtx);
                if (attDecision.isMask() && attDecision.getMaskedContent() != null) {
                    finalAttachment = attDecision.getMaskedContent();
                }
            }

            // 3. 重组用户消息（任一部分被脱敏时更新）
            if (finalTyped != typedPart || finalAttachment != attachmentPart) {
                taskCtx.setUserMessage(finalTyped + (finalAttachment != null ? finalAttachment : ""));
            }

        } catch (Exception e) {
            log.error("SecurityMiddleware onAgent 异常，透传", e);
        }

        return next.apply(input);
    }

    // ==================== onSystemPrompt: 注入策略指令 ====================

    /**
     * onSystemPrompt 拦截点：在系统提示词中注入安全策略指令。
     *
     * <p>根据治理档位追加相应的约束指令，让 LLM 在生成时遵守安全规范。
     */
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return Mono.just(prompt);
        }

        try {
            AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
            GovernanceTier tier = taskCtx != null ? taskCtx.getGovernanceTier() : GovernanceTier.STANDARD;

            StringBuilder directive = new StringBuilder("\n\n【AEGIS_SECURITY_POLICY】\n");
            directive.append("- 治理档位: ").append(tier.getDesc()).append("\n");

            if (tier == GovernanceTier.STRICT) {
                directive.append("- 严格模式：禁止输出敏感数据、禁止访问外部网络、关键操作需确认\n");
                directive.append("- 所有工具调用必须经过审批，禁止直接执行高风险操作\n");
            } else if (tier == GovernanceTier.ENHANCED) {
                directive.append("- 增强模式：高风险工具调用需确认，输出内容将自动脱敏\n");
            }

            // 防止重复追加
            if (prompt.contains("【AEGIS_SECURITY_POLICY】")) {
                return Mono.just(prompt);
            }

            log.debug("SecurityMiddleware onSystemPrompt: agentId={}, tier={}",
                    agent != null ? agent.getAgentId() : "null", tier);
            return Mono.just(prompt + directive.toString());

        } catch (Exception e) {
            log.error("SecurityMiddleware onSystemPrompt 异常，透传原 prompt", e);
            return Mono.just(prompt);
        }
    }

    // ==================== onModelCall: 模型安全路由 ====================

    /**
     * onModelCall 拦截点：模型路由策略评估。
     *
     * <p>STRICT 档位 + L4 资源时强制路由到本地/加密模型。
     */
    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
                                        Function<ModelCallInput, Flux<AgentEvent>> next) {
        try {
            AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
            if (taskCtx == null) {
                return next.apply(input);
            }

            String modelName = input.model() != null ? input.model().getModelName() : null;
            if (modelName == null) {
                return next.apply(input);
            }

            SecurityPolicyContext policyCtx = SecurityPolicyContext.builder()
                    .tenantId(taskCtx.getTenantId())
                    .agentId(taskCtx.getAgentId())
                    .governanceTier(taskCtx.getGovernanceTier())
                    .resourceType(ResourceType.AGENT)
                    .resourceLevel(taskCtx.getAgentLevel())
                    .action(SecurityPolicyContext.Action.MODEL_CALL)
                    .content(modelName)
                    .build();

            PolicyDecision decision = securityPolicyEngine.evaluateModelPolicy(policyCtx);

            if (decision.isRouteLocal() && decision.getRouteTarget() != null) {
                log.info("SecurityMiddleware onModelCall ROUTE_LOCAL: {} → {}", modelName, decision.getRouteTarget());
                // 记录路由决策到 TaskContext，供下游日志/审计使用
                taskCtx.recordPolicyDecision(modelName, decision);
                // 注：ModelCallInput 的 model() 是 Model 类型，无法直接用 String 构造新实例。
                // 路由决策已记录，实际模型路由由 AegisSecurityPolicyEngine 内部的 ModelRouter 完成。
            }

            return next.apply(input);

        } catch (Exception e) {
            log.error("SecurityMiddleware onModelCall 异常，透传原模型", e);
            return next.apply(input);
        }
    }

    // ==================== onActing: 工具调用策略判定 ====================

    /**
     * onActing 拦截点：工具调用策略评估。
     *
     * <p>当 LLM 生成 ToolUseBlock 时触发，评估是否允许/需审批/拒绝该工具调用。
     * 已审批通过的工具直接放行，不重复触发审批。
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                      Function<ActingInput, Flux<AgentEvent>> next) {
        AegisTaskContext taskCtx = ctx.get(AegisTaskContext.class);
        if (taskCtx == null) {
            return next.apply(input);
        }

        try {
            List<ToolUseBlock> toolCalls = input.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                return next.apply(input);
            }

            for (ToolUseBlock toolCall : toolCalls) {
                String toolName = toolCall.getName();
                if (toolName == null || toolName.isEmpty()) {
                    continue;
                }

                // === 已审批通过的工具直接放行（防止重复审批）===
                if (taskCtx.isToolApproved(toolName)) {
                    log.debug("SecurityMiddleware onActing 已审批放行: tool={}", toolName);
                    continue;
                }

                // === 内置低风险工具（needApproval=false）直接放行，无需审批 ===
                ToolRiskInfo builtinRisk = BuiltinToolRiskConfig.getRiskInfo(toolName);
                if (builtinRisk != null && !builtinRisk.isNeedApproval()) {
                    taskCtx.markToolApproved(toolName, PolicyDecision.allow(null, "builtin-low-risk", null, null));
                    log.debug("SecurityMiddleware onActing 内置低风险工具直接放行: tool={}, riskLevel={}", toolName, builtinRisk.getRiskLevel());
                    continue;
                }

                // === MCP 只读工具 / AgentScope 通用只读工具前缀放行 ===
                // 非内置工具（builtinRisk == null）默认视为 MCP 动态工具，
                // 只读前缀工具（get_*, query_*, search_*, list_*, describe_*, check_*,
                // fetch_*, read_*, lookup_*, resolve_*, find_*）直接放行，无需审批
                if (builtinRisk == null && isMcpReadOnlyTool(toolName)) {
                    taskCtx.markToolApproved(toolName, PolicyDecision.allow(null, "mcp-readonly-prefix", null, null));
                    log.debug("SecurityMiddleware onActing MCP只读前缀放行: tool={}", toolName);
                    continue;
                }

                // === 构建策略上下文并评估 ===
                var ctxBuilder = SecurityPolicyContext.builder()
                        .tenantId(taskCtx.getTenantId())
                        .agentId(taskCtx.getAgentId())
                        .governanceTier(taskCtx.getGovernanceTier())
                        .resourceCode(toolName)
                        .action(SecurityPolicyContext.Action.TOOL_CALL)
                        .content(toolCall.getInput() != null ? toolCall.getInput().toString() : null)
                        .sessionId(taskCtx.getSessionId())
                        .traceId(taskCtx.getTraceId());

                // 获取工具的 securityLevel：优先内置 BuiltinToolRiskConfig，其次 TaskContext 注入值（含 MCP 工具）
                SecurityLevel toolLevel;
                if (builtinRisk != null) {
                    toolLevel = mapBuiltinRiskLevel(builtinRisk);
                } else {
                    toolLevel = taskCtx.getToolSecurityLevel(toolName);
                }
                if (toolLevel != null) {
                    ctxBuilder.resourceLevel(toolLevel);
                }

                SecurityPolicyContext policyCtx = ctxBuilder.build();
                PolicyDecision decision = securityPolicyEngine.evaluateToolPolicy(policyCtx);

                // 记录策略决策到 TaskContext
                taskCtx.recordPolicyDecision(toolName, decision);

                if (decision.isReject()) {
                    taskCtx.setBlocked(true);
                    taskCtx.setBlockReason("工具调用被拒绝: " + decision.getReason());
                    log.warn("SecurityMiddleware onActing REJECT: tool={}, reason={}", toolName, decision.getReason());
                    return Flux.<AgentEvent>empty();
                }

                if (decision.isAsk()) {
                    // 触发 HITL 审批：仅标记为待审批，暂停工具执行，等待用户审批后由 HITL 流恢复
                    // 不调用 markToolApproved，否则 ASK 决策被立即当作 ALLOW 放行，使人工审批形同虚设
                    taskCtx.setPendingApproval(toolName, decision);
                    log.info("SecurityMiddleware onActing ASK: tool={}, reason={}", toolName, decision.getReason());
                    return Flux.<AgentEvent>empty();
                }

                // 通配符 HITL 规则覆盖：在 ALLOW 放行前检查通配符 HitlNode 条件
                // AS PermissionEngine 无法通配匹配，故在 onActing 中间件层面统一兜底
                // 注：MCP 工具与未知工具(toolLevel=null)默认低风险，不通配符拦截；
                //     管理员需审批 MCP 工具时应配置 sec_tool_policy 或 toolName 精确规则
                if (!decision.isReject()) {
                    List<HitlNode> wildcardNodes = hitlRuleLoader.resolveWildcardHitlNodes(taskCtx.getAgentId());
                    for (HitlNode wn : wildcardNodes) {
                        if (matchesWildcardRule(wn, toolName, toolLevel, builtinRisk)) {
                            PolicyDecision wildcardDecision = PolicyDecision.ask(
                                    "匹配通配符审批规则: " + wn.getNodeName(),
                                    null,
                                    "wildcard-hitl-rule-" + wn.getId(),
                                    null, null, null);
                            taskCtx.setPendingApproval(toolName, wildcardDecision);
                            log.info("P2-11 通配符 HITL 规则命中: tool={}, nodeName={}, nodeId={}",
                                    toolName, wn.getNodeName(), wn.getId());
                            return Flux.<AgentEvent>empty();
                        }
                    }
                }

                // ALLOW 决策：标记为已审批，防止后续重复评估
                taskCtx.markToolApproved(toolName, decision);
                log.debug("SecurityMiddleware onActing ALLOW: tool={}", toolName);
            }

        } catch (Exception e) {
            log.error("SecurityMiddleware onActing 异常，透传", e);
        }

        return next.apply(input);
    }

    /**
     * 将 BuiltinToolRiskConfig 中的 ToolRiskInfo 映射为 SecurityLevel。
     *
     * <p>规则与 {@link com.aegis.runtime.integration.agent.AegisAgentInstanceManager#mapToolLevel} 保持一致：
     * needApproval=true → L3（ASK），否则按风险等级：LOW→L1，MEDIUM→L2，HIGH→L3。
     */
    private SecurityLevel mapBuiltinRiskLevel(ToolRiskInfo risk) {
        if (risk == null) {
            return SecurityLevel.L1;
        }
        if (risk.isNeedApproval()) {
            return SecurityLevel.L3;
        }
        ToolRiskInfo.RiskLevel level = risk.getRiskLevel();
        if (level == null) {
            return SecurityLevel.L1;
        }
        return switch (level) {
            case HIGH, CRITICAL -> SecurityLevel.L3;
            case MEDIUM -> SecurityLevel.L2;
            default -> SecurityLevel.L1;
        };
    }

    /**
     * 检查工具调用是否命中通配符 HitlNode 规则。
     *
     * <p>通配符规则的 triggerCondition 不含明确 toolName（或为 "*"），通过条件字段做匹配。
     * 支持 4 种条件字段（按优先级判定，命中其一即拦截）：
     * <ul>
     *   <li>{@code toolName:"xxx"} → 精确工具名匹配（向后兼容，与通配符语义对齐）</li>
     *   <li>{@code toolSecurityLevel:">=3"} → 工具实际安全等级 ≥ 阈值才拦截</li>
     *   <li>{@code toolTypes:["WRITE","CODE_EXEC"]} → 工具类型命中集合才拦截</li>
     *   <li>{@code rule:"securityLevel>=L3" / "toolName:xxx"} → 显式规则串（向后兼容）</li>
     * </ul>
     *
     * <p><b>未知工具不拦截原则</b>：MCP 工具与未在 {@link BuiltinToolRiskConfig} 注册的工具，
     * {@code toolLevel} 与 {@code builtinRisk} 均为 null，一律不命中通配符规则（默认低风险放行）。
     * 管理员需审批此类工具时，应配置 {@code sec_tool_policy}（按 tool_type=工具名 APPROVE）
     * 或 HitlNode 的 {@code toolName} 精确匹配，而非依赖通配符兜底。
     * 这避免"未配置=需审批"的误判将天气查询等低风险 MCP 工具错误纳入审批流程。
     *
     * @param node        通配符 HitlNode
     * @param toolName    当前工具名
     * @param toolLevel   工具安全等级（内置工具映射值，MCP/未知工具为 null）
     * @param builtinRisk 内置工具风险信息（非内置工具为 null）
     * @return true=命中，需触发 ASK 审批
     */
    private boolean matchesWildcardRule(HitlNode node, String toolName,
                                        SecurityLevel toolLevel, ToolRiskInfo builtinRisk) {
        if (node == null || node.getTriggerCondition() == null) {
            return false;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(node.getTriggerCondition());

            // 1. toolName 精确匹配（向后兼容：triggerCondition 含明确 toolName 时按精确匹配）
            com.fasterxml.jackson.databind.JsonNode toolNameNode = root.get("toolName");
            if (toolNameNode != null && !toolNameNode.isNull()
                    && !toolNameNode.asText().isBlank()
                    && !"*".equals(toolNameNode.asText().trim())) {
                return toolName.equals(toolNameNode.asText().trim());
            }

            // 2. toolSecurityLevel 阈值条件（如 ">=3"）：按工具实际安全等级匹配
            com.fasterxml.jackson.databind.JsonNode secLevelNode = root.get("toolSecurityLevel");
            if (secLevelNode != null && !secLevelNode.isNull() && !secLevelNode.asText().isBlank()) {
                if (toolLevel == null) {
                    return false;
                }
                int threshold = parseLevelThreshold(secLevelNode.asText());
                return threshold > 0 && toolLevel.getLevel() >= threshold;
            }

            // 3. toolTypes 集合条件：按工具类型匹配（仅对内置工具生效）
            com.fasterxml.jackson.databind.JsonNode toolTypesNode = root.get("toolTypes");
            if (toolTypesNode != null && toolTypesNode.isArray() && toolTypesNode.size() > 0) {
                if (builtinRisk == null || builtinRisk.getToolType() == null) {
                    return false;
                }
                String currentToolType = builtinRisk.getToolType().name();
                for (com.fasterxml.jackson.databind.JsonNode t : toolTypesNode) {
                    if (currentToolType.equals(t.asText())) {
                        return true;
                    }
                }
                return false;
            }

            // 4. rule 显式规则串（向后兼容）
            com.fasterxml.jackson.databind.JsonNode ruleNode = root.get("rule");
            if (ruleNode == null || ruleNode.isNull() || ruleNode.asText().isBlank()) {
                // 无任何条件字段 → 不拦截（避免误伤 MCP/未知工具）
                return false;
            }
            String rule = ruleNode.asText();
            if (rule.contains("securityLevel>=L3") || rule.contains("security_level>=L3")) {
                if (toolLevel == null) {
                    return false;
                }
                return toolLevel == SecurityLevel.L3 || toolLevel == SecurityLevel.L4;
            }
            if (rule.startsWith("toolName:")) {
                return toolName.equals(rule.substring("toolName:".length()).trim());
            }
            // 未知 rule 格式 → 保守不拦截
            return false;
        } catch (Exception e) {
            log.warn("P2-11: 通配符规则解析失败，保守不拦截: nodeId={}, trigger={}",
                    node.getId(), node.getTriggerCondition());
            return false;
        }
    }

    /**
     * 从阈值表达式（如 ">=3"、">=L3"、"3"）解析等级阈值整数。
     *
     * @param expr 阈值表达式
     * @return 等级阈值（1-4），解析失败返回 0
     */
    private int parseLevelThreshold(String expr) {
        if (expr == null || expr.isBlank()) {
            return 0;
        }
        String digits = expr.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 判断工具名是否匹配 MCP / 通用只读工具前缀。
     * 只读前缀：get_*, query_*, search_*, list_*, describe_*, check_*,
     * fetch_*, read_*, lookup_*, resolve_*, find_*
     * 这些前缀的工具默认只读，无副作用，放行无需审批。
     *
     * @param toolName 工具名
     * @return true 表示是只读前缀工具
     */
    private static boolean isMcpReadOnlyTool(String toolName) {
        if (toolName == null || toolName.isEmpty()) {
            return false;
        }
        String lower = toolName.toLowerCase();
        // 按优先级排序的只读前缀（长的优先匹配，避免短前缀误命中）
        String[] prefixes = {
                "describe_", "fetch_", "lookup_", "resolve_",
                "search_", "query_", "check_", "read_",
                "find_", "list_", "get_"
        };
        for (String prefix : prefixes) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
