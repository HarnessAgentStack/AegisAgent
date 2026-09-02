package com.aegis.runtime.integration.middleware;

import com.aegis.core.domain.security.HitlNode;
import com.aegis.runtime.service.policy.HitlRuleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HITL 规则加载器：将 DB 中的 HitlNode 配置转化为 AS PermissionRule。
 *
 * <p>职责：
 * <ul>
 *   <li>buildAgent 阶段一次性加载 agentId 启用的 HitlNode 列表</li>
 *   <li>每个 HitlNode 转化为一条 {@link PermissionBehavior#ASK} 规则，
 *       规则命中时 AS 自动发出 RequireUserConfirmEvent 暂停执行</li>
 *   <li>用户确认后，第二次调用携带 ConfirmResult 恢复执行，
 *       ConfirmResult.rules 自动注册到 PermissionEngine 实现规则学习</li>
 * </ul>
 *
 * <h3>规则映射策略</h3>
 * <ul>
 *   <li>{@code ruleId}：{@code hitl-{nodeId}}（便于审计追溯）</li>
 *   <li>{@code toolName}：从 {@code triggerCondition} JSON 解析，缺省 {@code "*"}（通配）</li>
 *   <li>{@code ruleContent}：保留原 triggerCondition，交由工具 matchRule 解释</li>
 *   <li>{@code behavior}：统一 ASK（需用户确认）</li>
 * </ul>
 *
 * <h3>调用方</h3>
 * <p>{@code AegisAgentInstanceManager.buildAgent} 在构造 PermissionContextState 时调用，
 * 合并静态规则（SSRF 等）与动态 HitlNode 规则。
 *
 * @author wang.zhen
 * @see RequireUserConfirmEvent 由 AS PermissionEngine 自动发出
 * @see ConfirmResult 用户确认后回传，触发规则学习
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AegisHitlRuleLoader {

    /** 规则来源标识，用于在 PermissionRule.source 中追溯 HitlNode 来源 */
    private static final String RULE_SOURCE = "aegis-hitl-policy";

    /** 通配工具名（HitlNode 未指定具体工具时使用） */
    private static final String WILDCARD_TOOL = "*";

    /** 用于安全解析 triggerCondition JSON，替代手工 indexOf 解析 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 规则缓存，按 agentId 维度，带 TTL（默认 60 秒）。
     *
     * <p>过期后下次 loadHitlRules 触发重新查询 DB，实现近实时热更新；
     * {@link #forceReload()} 供手动立即刷新。
     */
    private final Map<Long, CachedNodes> ruleCache = new ConcurrentHashMap<>();

    /** 缓存有效期（毫秒） */
    private static final long CACHE_TTL_MS = 60_000L;

    private final HitlRuleService hitlRuleService;

    /**
     * 将指定 agentId 启用的 HitlNode 列表转化为 AS PermissionRule，
     * 累积注入到 PermissionContextState.Builder 的 ask 规则表。
     *
     * <p>查询失败时降级为空规则（不影响其他规则装配），符合"软失败"原则。
     *
     * <p>triggerCondition 未指定明确 toolName 时跳过该规则（不使用通配符 {@code "*"}，
     * 避免拦截所有工具）；通配符匹配由 onActing 中间件在工具执行前检查。
     *
     * <p>从带 TTL 的缓存读取 HitlNode 列表，支持近实时热更新。
     *
     * @param agentId       智能体 ID
     * @param permBuilder   权限上下文 Builder（累积注入 ask 规则）
     */
    public void loadHitlRules(long agentId, PermissionContextState.Builder permBuilder) {
        // 从缓存读取，未命中或过期时重新查询 DB
        List<HitlNode> nodes = getCachedNodes(agentId);

        if (nodes == null || nodes.isEmpty()) {
            log.debug("agentId={} 无启用的 HitlNode，跳过动态 HITL 规则装配", agentId);
            return;
        }

        for (HitlNode node : nodes) {
            if (node.getSlaHours() == null || node.getSlaHours() <= 0) {
                // SLA 未配置视为无效节点，跳过（与原中间件逻辑保持一致）
                continue;
            }
            String toolName = resolveToolName(node.getTriggerCondition());
            if (WILDCARD_TOOL.equals(toolName)) {
                // 未指定明确工具名时不注入 AS PermissionRule（避免通配符拦截所有工具）
                // 安全级别等条件过滤应由 onActing 中间件层面的工具执行前检查处理
                log.info("HITL 规则跳过（未指定明确 toolName，不注入 AS PermissionEngine）: agentId={}, nodeId={}, nodeName={}",
                        agentId, node.getId(), node.getNodeName());
                continue;
            }
            String ruleId = "hitl-" + node.getId();
            PermissionRule rule = new PermissionRule(
                    toolName, node.getTriggerCondition(),
                    PermissionBehavior.ASK, ruleId);

            // HitlNode 触发 ASK 行为，命中时由 AS 自动发出 RequireUserConfirmEvent
            permBuilder.addAskRule(toolName, rule);
            // slaHours 已记录于日志与 HitlNode 配置中；
            // AS PermissionRule 当前不支持审批超时自动拒绝，超时自动拒绝需 AS 框架支持后实现
            log.info("HITL 规则注入: agentId={}, nodeId={}, nodeName={}, toolName={}, slaHours={}",
                    agentId, node.getId(), node.getNodeName(), toolName, node.getSlaHours());
        }
    }

    /**
     * 解析通配符 HITL 规则——triggerCondition 未指定 toolName（或为 "*"）的 HitlNode。
     *
     * <p>这些规则在 {@link #loadHitlRules} 中被跳过（不注入 AS PermissionEngine），
     * 因为 AS PermissionRule 无法通配匹配。本方法将它们提取出来，
     * 供安全中间件 onActing 在工具执行前逐一检查：
     * 若工具命中通配规则的条件（rule 字段），则触发 ASK 审批。
     *
     * @param agentId 智能体 ID
     * @return 通配符 HitlNode 列表（无则返回空列表）
     */
    public List<HitlNode> resolveWildcardHitlNodes(long agentId) {
        List<HitlNode> nodes = getCachedNodes(agentId);
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }
        List<HitlNode> wildcardNodes = new java.util.ArrayList<>();
        for (HitlNode node : nodes) {
            if (node.getSlaHours() == null || node.getSlaHours() <= 0) {
                continue;
            }
            String toolName = resolveToolName(node.getTriggerCondition());
            if (WILDCARD_TOOL.equals(toolName)) {
                wildcardNodes.add(node);
            }
        }
        return wildcardNodes;
    }

    /**
     * 解析指定 agentId 将被注入 ASK 规则的工具名集合。
     *
     * <p>{@code AegisAgentInstanceManager.buildPermissionContext} 在为低风险内置工具
     * 注册 ALLOW 规则前，必须先排除该集合中的工具——AgentScope PermissionEngine 评估序为
     * deny → ask → 工具自检 → allow（ASK 先于 ALLOW），排除重叠是为消除规则歧义，
     * 而非"ALLOW 覆盖 ASK"（原注释与源码行为矛盾，已修正）。
     *
     * <p>判定逻辑与 {@link #loadHitlRules} 完全一致（SLA 有效 + 指定明确 toolName）。
     *
     * @param agentId 智能体 ID
     * @return 将注入 ASK 规则的工具名集合（无则返回空集合）
     */
    public Set<String> resolveAskToolNames(long agentId) {
        List<HitlNode> nodes = getCachedNodes(agentId);
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> askTools = new HashSet<>();
        for (HitlNode node : nodes) {
            if (node.getSlaHours() == null || node.getSlaHours() <= 0) {
                continue;
            }
            String toolName = resolveToolName(node.getTriggerCondition());
            if (!WILDCARD_TOOL.equals(toolName)) {
                askTools.add(toolName);
            }
        }
        return askTools;
    }

    /**
     * 从缓存获取 HitlNode 列表，未命中或过期时重新查询 DB。
     *
     * @param agentId 智能体 ID
     * @return HitlNode 列表（永不为 null，查询失败返回空列表）
     */
    private List<HitlNode> getCachedNodes(long agentId) {
        CachedNodes cached = ruleCache.get(agentId);
        if (cached != null && System.currentTimeMillis() < cached.expireAt) {
            return cached.nodes;
        }
        // 缓存未命中或已过期，重新查询 DB
        List<HitlNode> nodes;
        try {
            nodes = hitlRuleService.listEnabledNodes(agentId);
        } catch (Exception e) {
            log.warn("HitlNode 加载失败，跳过动态 HITL 规则装配: agentId={}, error={}",
                    agentId, e.getMessage());
            return Collections.emptyList();
        }
        if (nodes == null) {
            nodes = Collections.emptyList();
        }
        ruleCache.put(agentId, new CachedNodes(nodes, System.currentTimeMillis() + CACHE_TTL_MS));
        return nodes;
    }

    /**
     * 手动触发全部规则缓存清空，下次 loadHitlRules 重新从 DB 加载。
     */
    public void forceReload() {
        ruleCache.clear();
        log.info("HITL 规则缓存已清空，下次加载将重新查询 DB");
    }

    /**
     * 清空指定 agentId 的规则缓存。
     *
     * @param agentId 智能体 ID
     */
    public void forceReload(long agentId) {
        ruleCache.remove(agentId);
        log.info("HITL 规则缓存已清空: agentId={}", agentId);
    }

    /**
     * 缓存条目，持有 HitlNode 列表与过期时间戳。
     */
    private static class CachedNodes {
        final List<HitlNode> nodes;
        final long expireAt;

        CachedNodes(List<HitlNode> nodes, long expireAt) {
            this.nodes = nodes;
            this.expireAt = expireAt;
        }
    }

    /**
     * 从 triggerCondition JSON 中解析工具名。
     *
     * <p>使用 Jackson {@link ObjectMapper#readTree(String)} 解析，正确处理转义引号、
     * 嵌套对象、null 值等边界情况。解析失败或缺失时使用通配符 {@code "*"}。
     *
     * @param triggerCondition HitlNode.triggerCondition JSON 字符串
     * @return 工具名，或 {@code "*"} 表示通配
     */
    private String resolveToolName(String triggerCondition) {
        if (triggerCondition == null || triggerCondition.isBlank()) {
            return WILDCARD_TOOL;
        }
        try {
            // 使用 Jackson 解析 JSON，正确处理转义引号、嵌套对象、null 值
            JsonNode root = OBJECT_MAPPER.readTree(triggerCondition);
            JsonNode toolNameNode = root.get("toolName");
            if (toolNameNode == null || toolNameNode.isNull()) {
                // 兼容下划线命名
                toolNameNode = root.get("tool_name");
            }
            if (toolNameNode == null || toolNameNode.isNull()) {
                return WILDCARD_TOOL;
            }
            String toolName = toolNameNode.asText();
            return (toolName == null || toolName.isBlank()) ? WILDCARD_TOOL : toolName.trim();
        } catch (Exception e) {
            log.warn("triggerCondition JSON 解析失败，回退通配符: triggerCondition={}", triggerCondition, e);
            return WILDCARD_TOOL;
        }
    }
}
