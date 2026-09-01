package com.aegis.core.enums.sandbox;

/**
 * 沙箱隔离作用域枚举。
 *
 * <p>定义沙箱实例的资源复用粒度，决定同一隔离键下的沙箱是否可复用。
 * 与 AgentScope {@code IsolationScope} 对应，在 aegis-core 中定义以避免
 * 核心模块对 AgentScope 的直接依赖。</p>
 *
 * <h3>作用域说明</h3>
 * <ul>
 *   <li>{@link #SESSION} — 会话级隔离，每次会话独占沙箱，会话结束后回收</li>
 *   <li>{@link #USER} — 用户级隔离，同一用户的不同会话可复用沙箱</li>
 *   <li>{@link #AGENT} — Agent 级隔离，同一 Agent 的不同会话可复用沙箱</li>
 *   <li>{@link #GLOBAL} — 全局共享，所有会话复用同一沙箱（特殊场景）</li>
 * </ul>
 *
 * <h3>与 AgentScope 的转换</h3>
 * <p>在 aegis-runtime 模块的 SPI 边界处进行转换：
 * {@code AegisSandboxCoordinator} ↔ {@code K8sSandboxClient} ↔ AgentScope Framework。</p>
 *
 * @author wang.zhen
 */
public enum IsolationScope {

    /** 会话级隔离 — 每次会话独占沙箱 */
    SESSION,

    /** 用户级隔离 — 同一用户的会话可复用 */
    USER,

    /** Agent 级隔离 — 同一 Agent 的会话可复用 */
    AGENT,

    /** 全局共享 — 所有会话共用一个沙箱 */
    GLOBAL
}
