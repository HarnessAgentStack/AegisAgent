package com.aegis.core.spi;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁协议。
 *
 * <p>抽象平台分布式互斥的统一协议，屏蔽底层实现差异（Redisson / Etcd / Zookeeper）。
 * 提供互斥获取、自动续租与可重入语义，支撑会话串行化、池化资源分配、定时任务防重入等场景。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>可重入：同一线程/同一锁持有者可多次获取，按计数释放</li>
 *   <li>自动续租（看门狗）：持有期间后台续期，防止业务未完成锁先过期</li>
 *   <li>租户前缀隔离：锁键以 tenant:{id}: 为前缀，避免跨租户冲突</li>
 *   <li>释放校验：仅持有者可释放，防止误释放他人锁</li>
 * </ul>
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>会话级串行：同一 sessionId 加锁，保证任务顺序执行</li>
 *   <li>池化资源分配：沙箱/智能体实例分配时加锁防超卖</li>
 * </ul>
 *
 * <p>本协议为同步契约，保持 aegis-core 不引入响应式框架。
 *
 * @author wang.zhen
 */
public interface IDistributedLock {

    /**
     * 尝试获取锁（带超时）。
     *
     * @param key       锁键（建议含租户前缀）
     * @param leaseTime 持有时长（看门狗续租基础），超时后自动释放
     * @param unit      时长单位
     * @param waitTime  获取等待时长
     * @return true 表示获取成功
     */
    boolean tryLock(String key, long waitTime, long leaseTime, TimeUnit unit);

    /**
     * 释放锁（仅持有者可释放）。
     *
     * @param key 锁键
     * @return true 表示释放成功
     */
    boolean unlock(String key);

    /**
     * 续租锁（延长持有时长）。
     *
     * @param key       锁键
     * @param leaseTime 新持有时长
     * @param unit      时长单位
     * @return true 表示续租成功（仍为持有者）
     */
    boolean renew(String key, long leaseTime, TimeUnit unit);

    /**
     * 判断锁是否被持有。
     *
     * @param key 锁键
     * @return true 表示已被持有
     */
    boolean isLocked(String key);
}
