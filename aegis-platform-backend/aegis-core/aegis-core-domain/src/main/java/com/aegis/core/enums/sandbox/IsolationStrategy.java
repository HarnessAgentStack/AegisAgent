package com.aegis.core.enums.sandbox;

import lombok.Getter;

/**
 * 沙箱隔离策略枚举。
 *
 * <p>定义沙箱实例在多租户、多会话场景下的资源隔离与分配策略，
 * 决定沙箱 Pod 的创建粒度与生命周期管理方式。</p>
 *
 * <h3>策略说明</h3>
 * <ul>
 *   <li>{@link #SHARED_PER_SCOPE} — 作用域内共享 Pod，通过 sessionId 实现命名空间级隔离</li>
 *   <li>{@link #DEDICATED_PER_SESSION} — 会话独占 Pod，会话结束后销毁</li>
 *   <li>{@link #SHARED_WITH_QUOTA} — 租户级共享 Pod，配合配额控制实现多租户复用</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
public enum IsolationStrategy {

    /** 作用域内共享 — 共享 Pod，通过 sessionId 实现命名空间级隔离 */
    SHARED_PER_SCOPE("作用域内共享Pod，通过sessionId实现命名空间级隔离"),

    /** 会话独占 — 每个会话独占 Pod，释放时销毁 */
    DEDICATED_PER_SESSION("会话独占Pod，释放时销毁"),

    /** 配额共享 — 租户级共享 Pod，配合配额控制实现多租户复用 */
    SHARED_WITH_QUOTA("租户级共享Pod，配合配额控制实现多租户复用");

    /** 策略描述 */
    private final String description;

    IsolationStrategy(String description) {
        this.description = description;
    }
}