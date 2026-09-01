package com.aegis.runtime.service.agent;

import com.aegis.core.domain.agent.AgentBinding;
import com.aegis.core.domain.resource.Skill;
import io.agentscope.core.agent.RuntimeContext;

import java.util.List;

/**
 * 装配期资源上下文：一次查询、装配链与运行时中间件共享。
 *
 * <p>收敛 T3/T4 的重复 SELECT--此前同一次会话装配期内，{@code listEnabledBindings(agentId)}
 * 被 ToolBridge / SkillRepository / RagMiddleware / BindingSyncMiddleware / RuntimeContext 构建
 * 各自独立查询（5+ 次 SQL），且绑定 Skill 实体被 ToolBridge 与 SkillRepository 各自
 * {@code findSkillById} 逐个加载（双重加载）。
 *
 * <p>改造后：{@link AgentAssemblyService} 装配期一次查询 enabled 绑定列表 + 一次
 * {@code selectBatchIds} 批量加载绑定 Skill 实体，封装为本上下文：
 * <ul>
 *   <li><b>装配链传递</b>（ToolBridge 在 buildAgent/loadToolkit 内，早于 RuntimeContext 构建）：
 *       通过 {@code acquireOrBuild -> buildAgent -> loadToolkit} 参数穿透传递</li>
 *   <li><b>运行时传递</b>（RAG / SkillRepository / BindingSync 在中间件钩子内）：
 *       写入 {@link RuntimeContext} 属性 {@link #CTX_ENABLED_BINDINGS} /
 *       {@link #CTX_BOUND_SKILLS}，由 {@link #enabledBindingsOf(RuntimeContext)} /
 *       {@link #boundSkillsOf(RuntimeContext)} 读取</li>
 * </ul>
 *
 * <h3>语义说明</h3>
 * <p>{@code enabledBindings} 与 {@code ResourceQueryService#listEnabledBindings} 查询语义
 * 完全一致（agentId + enabled=true，跨版本）；{@code boundSkills} 为其中 SKILL 类型绑定的
 * 全部实体（不区分 PUBLISHED 状态，由各消费方按自身规则过滤）。模板 bindings
 * （AgentPoolManager 按 agentId+version 查询）与本上下文<b>语义不同</b>，指纹计算仍用模板
 * bindings，本上下文仅替代各消费方的 enabled 查询。
 *
 * <h3>回退策略</h3>
 * <p>非装配路径调用（无 RuntimeContext 属性）时，消费方回退原 DB 直查，行为不变。
 *
 * @author wang.zhen
 */
public record AssemblyResourceContext(List<AgentBinding> enabledBindings, List<Skill> boundSkills) {

    /** RuntimeContext 属性 key：装配期查询的 enabled 绑定全量列表 */
    public static final String CTX_ENABLED_BINDINGS = "aegis.enabledBindings";

    /** RuntimeContext 属性 key：装配期批量加载的绑定 Skill 实体列表 */
    public static final String CTX_BOUND_SKILLS = "aegis.boundSkills";

    /** 空上下文（agent 无任何绑定/查询失败时的兜底） */
    public static final AssemblyResourceContext EMPTY =
            new AssemblyResourceContext(List.of(), List.of());

    /**
     * 从 RuntimeContext 读取装配期预加载的 enabled 绑定列表。
     *
     * @param ctx 运行时上下文（可为 null）
     * @return 预加载列表；非装配路径（属性缺失）返回 null，调用方回退 DB 查询
     */
    public static List<AgentBinding> enabledBindingsOf(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        Object value = ctx.get(CTX_ENABLED_BINDINGS);
        // 空列表是有效装配结果（无绑定），不触发回退；仅属性缺失（非装配路径）返回 null
        return value instanceof List<?> list ? uncheckedBindings(list) : null;
    }

    /**
     * 从 RuntimeContext 读取装配期预加载的绑定 Skill 实体列表。
     *
     * @param ctx 运行时上下文（可为 null）
     * @return 预加载列表；非装配路径（属性缺失）返回 null，调用方回退 DB 查询
     */
    public static List<Skill> boundSkillsOf(RuntimeContext ctx) {
        if (ctx == null) {
            return null;
        }
        Object value = ctx.get(CTX_BOUND_SKILLS);
        // 空列表是有效装配结果（无绑定技能），不触发回退；仅属性缺失（非装配路径）返回 null
        return value instanceof List<?> list ? uncheckedSkills(list) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<AgentBinding> uncheckedBindings(List<?> list) {
        return (List<AgentBinding>) list;
    }

    @SuppressWarnings("unchecked")
    private static List<Skill> uncheckedSkills(List<?> list) {
        return (List<Skill>) list;
    }
}
