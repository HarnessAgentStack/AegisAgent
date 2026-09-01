package com.aegis.runtime.integration.middleware;

import io.agentscope.core.middleware.MiddlewareBase;

/**
 * 带 order 的 MiddlewareBase 标记接口（Aegis 装配阶段排序用）。
 *
 * <p>AgentScope 2.0.2 的 {@link MiddlewareBase} 已内置 {@code default int order()}
 * 方法（默认值 1）。本接口将其重新声明为抽象方法，强制 Aegis 自研中间件显式提供 order，
 * 供 {@link AegisMiddlewareChain} 装配阶段排序使用。
 *
 * <h3>为什么需要显式 order</h3>
 * <p>Spring 自动注入 {@code List<MiddlewareBase>} 时默认按 Bean 名字母序，顺序不可控。
 * Aegis 多个中间件存在执行顺序硬依赖（如租户隔离必须在配额检查前完成 tenantId 注入），
 * 因此必须通过 order 显式约定顺序。
 *
 * <h3>与 AgentScope 内核的关系</h3>
 * <p>{@link AegisMiddlewareChain#build()} 按 {@link #order()} 升序排序后传入
 * {@code HarnessAgent.Builder.middlewares()}。随后 AgentScope 2.0.2 的
 * {@code ReActAgent.Builder} 会按 {@code order()} <b>降序</b>重排（值越大越外层、越先执行），
 * 该排序对所有 5 个拦截点统一生效。因此 Aegis 设定的 order 值会直接决定最终执行顺序。
 *
 * <p><b>取值约定</b>（P0 MW-01 已修复：匹配 AgentScope 2.0.2 降序语义，值越大越先执行）：
 * <ul>
 *   <li>80 = 租户隔离（注入 TenantContextHolder，必须首道）</li>
 *   <li>70 = 配额检查（依赖 tenantId 已注入）</li>
 *   <li>65 = RAG 检索（在内容过滤前注入知识库上下文，依赖 tenantId + 配额已校验）</li>
 *   <li>60 = 内容过滤（onSystemPrompt，变换式）</li>
 *   <li>30 = 成本统计（须在审计前完成 token 累积）</li>
 *   <li>20 = 审计日志（记录完整结果）</li>
 *   <li>10 = 跨会话记忆（preCall 末道）</li>
 * </ul>
 *
 * <h3>对比 AS 内置中间件</h3>
 * <p>AS 内置中间件（如 CompactionMiddleware、SandboxLifecycleMiddleware）由
 * HarnessAgent 内核固定装配顺序，无需 order；Aegis 自研中间件因 Spring 注入
 * 顺序随机，必须显式 order 排序。
 *
 * @author wang.zhen
 */
public interface OrderedMiddleware extends MiddlewareBase {

    /**
     * 执行顺序值。
     *
     * <p>AgentScope 2.0.2 中值越大越先执行（外层）。P0 MW-01 已反转所有取值以匹配 AS 降序语义。
     * 取值约定与执行顺序见类级 Javadoc。
     *
     * @return 顺序值
     */
    int order();
}
