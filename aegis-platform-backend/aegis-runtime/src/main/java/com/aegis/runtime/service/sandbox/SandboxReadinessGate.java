package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.SandboxAllocationContext;
import com.aegis.core.enums.sandbox.IsolationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱就绪门控（T1 沙箱惰性分配核心）。
 *
 * <p>收敛沙箱类工具的分配入口：工具调用前调 {@link #awaitSandboxReady}，三态语义
 * （已分配快路径 / 预取中 / 同步兜底）。Phase 1 实现"已分配 + 同步兜底"两态
 * （正确性地板），Phase 2 将接入预取 future 共享。
 *
 * <h3>Phase 1 行为</h3>
 * <ul>
 *   <li>已分配：{@code sessionBindings} 命中 → 毫秒级返回，零 allocateSlot</li>
 *   <li>未触发：同步调 {@link AegisSandboxCoordinator#allocateSlot}（复用 Coordinator 分布式锁与池路由），
 *       成功后写入 {@code sessionBindings} 供同会话后续工具复用</li>
 *   <li>失败：抛 {@link SandboxNotReadyException}，工具门控捕获转结构化错误，不阻塞 SSE</li>
 * </ul>
 *
 * <p>单飞语义：{@link AegisSandboxCoordinator#allocateSlot} 内部 {@code findOccupiedBySlotKey}
 * + 分布式锁已保证跨 JVM 幂等；{@code sessionBindings} 是 JVM 内优化，减少同会话重复 DB 查询。
 *
 * <p>线程安全：{@code sessionBindings} 用 {@link ConcurrentHashMap}，{@link SandboxHandle} 为不可变 record。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
public class SandboxReadinessGate {

    private final AegisSandboxCoordinator coordinator;

    /** 会话级已分配句柄缓存（sessionId → handle），同会话多次工具调用复用 */
    private final ConcurrentHashMap<String, SandboxHandle> sessionBindings = new ConcurrentHashMap<>();

    /** Phase 2 预留：sessionId → 分配 future，供意图预取与门控共享单飞 */
    private final ConcurrentHashMap<String, CompletableFuture<SandboxHandle>> prefetchFutures = new ConcurrentHashMap<>();

    public SandboxReadinessGate(AegisSandboxCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * 等待沙箱就绪（三态收敛）。
     *
     * @param req        分配请求上下文
     * @param timeoutSec 超时秒数（Phase 1 同步调用，超时由 Coordinator 内部锁控制；保留参数供 Phase 2 future.get 用）
     * @return 已分配的沙箱句柄
     * @throws SandboxNotReadyException 分配失败/池满/配额超限
     */
    public SandboxHandle awaitSandboxReady(SandboxReadinessRequest req, long timeoutSec) {
        if (req == null || req.sessionId() == null) {
            throw new SandboxNotReadyException(null, null, "awaitSandboxReady 请求非法: req或sessionId为空");
        }

        // 1. 已分配快路径
        SandboxHandle cached = sessionBindings.get(req.sessionId());
        if (cached != null) {
            log.debug("awaitSandboxReady 命中已分配: sessionId={}, instanceId={}",
                    req.sessionId(), cached.instanceId());
            return cached;
        }

        // 2. Phase 2 预取中：共享 future（单飞 by sessionId）
        CompletableFuture<SandboxHandle> existing = prefetchFutures.get(req.sessionId());
        if (existing != null) {
            if (!existing.isDone()) {
                try {
                    SandboxHandle h = existing.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                    if (h != null) {
                        log.info("awaitSandboxReady 命中预取 future: sessionId={}, instanceId={}",
                                req.sessionId(), h.instanceId());
                        return h;
                    }
                } catch (java.util.concurrent.TimeoutException te) {
                    log.warn("awaitSandboxReady 预取 future 超时，转同步兜底: sessionId={}", req.sessionId());
                } catch (Exception ex) {
                    // 预取失败：移除异常 future，走同步兜底重试一次（§7.3 降级）
                    prefetchFutures.remove(req.sessionId(), existing);
                    log.warn("awaitSandboxReady 预取 future 异常完成，转同步兜底: sessionId={}, err={}",
                            req.sessionId(), ex.getMessage());
                }
            } else if (existing.isCompletedExceptionally()) {
                prefetchFutures.remove(req.sessionId(), existing);
            }
        }

        // 3. 同步兜底分配（预取未命中 / 预取失败 / 预取超时）
        SandboxHandle handle = allocateSync(req);
        sessionBindings.put(req.sessionId(), handle);
        prefetchFutures.remove(req.sessionId());
        log.info("awaitSandboxReady 同步兜底分配成功: sessionId={}, slotKey={}, instanceId={}, pod={}, namespace={}",
                req.sessionId(), req.slotKey(), handle.instanceId(), handle.podName(), handle.namespace());
        return handle;
    }

    /**
     * 同步分配沙箱（兜底路径）。
     *
     * <p>复用 {@link AegisSandboxCoordinator#allocateSlot} 的分布式锁 + 池路由 + OCCUPIED 复用，
     * 零容量层改动（§3.2）。
     */
    private SandboxHandle allocateSync(SandboxReadinessRequest req) {
        try {
            SandboxAllocationContext alloc = coordinator.allocateSlot(
                    req.scope(), req.slotKey(), req.tenantId(), req.userId(),
                    req.agentId(), req.sessionId(),
                    req.strategy() != null ? req.strategy() : IsolationStrategy.SHARED_PER_SCOPE,
                    req.agentType());
            if (alloc == null || !alloc.isSuccess()) {
                String errMsg = alloc != null ? alloc.getErrorMessage() : "allocation=null";
                log.warn("awaitSandboxReady 同步兜底分配失败: sessionId={}, slotKey={}, err={}",
                        req.sessionId(), req.slotKey(), errMsg);
                throw new SandboxNotReadyException(req.sessionId(), req.slotKey(),
                        "沙箱分配失败: " + errMsg);
            }
            return new SandboxHandle(alloc.getInstanceId(), alloc.getPodName(),
                    alloc.getNamespace(), req.slotKey(), req.sessionId());
        } catch (SandboxNotReadyException e) {
            throw e;
        } catch (Exception e) {
            log.warn("awaitSandboxReady 同步兜底分配异常: sessionId={}, slotKey={}, err={}",
                    req.sessionId(), req.slotKey(), e.getMessage());
            throw new SandboxNotReadyException(req.sessionId(), req.slotKey(),
                    "沙箱分配异常: " + e.getMessage(), e);
        }
    }

    /**
     * 查询会话当前已分配句柄（不触发分配）。
     *
     * <p>供心跳中间件、空闲释放追踪器判断"是否已分配"使用。
     */
    public SandboxHandle peek(String sessionId) {
        return sessionId == null ? null : sessionBindings.get(sessionId);
    }

    /**
     * 清理会话绑定（释放后调用）。
     *
     * <p>由 {@code IdleReleaseTracker}（Phase 3）或 {@code closeAgent} 在释放沙箱后调用，
     * 避免下次工具调用命中已释放的 stale handle。
     */
    public void clear(String sessionId) {
        if (sessionId != null) {
            sessionBindings.remove(sessionId);
            CompletableFuture<SandboxHandle> f = prefetchFutures.remove(sessionId);
            if (f != null && !f.isDone()) {
                f.cancel(true);
            }
        }
    }

    /**
     * Phase 2 意图预取（非阻塞，异步分配）。
     *
     * <p>由 {@code AegisIntentMiddleware} 在 TASK/SKILL_CREATE 意图识别后调用，
     * {@code computeIfAbsent} 单飞保证同 sessionId 只发起一次分配。结果写入
     * {@code prefetchFutures} 供工具门控 {@code awaitSandboxReady} 共享。
     *
     * <p>异常隔离：future 内 {@code completeExceptionally}，不向外抛；
     * 预取失败由门控自动降级同步兜底（§7.3）。
     */
    public void prefetchAsync(SandboxReadinessRequest req) {
        if (req == null || req.sessionId() == null) {
            return;
        }
        SandboxHandle cached = sessionBindings.get(req.sessionId());
        if (cached != null) {
            log.debug("prefetchAsync 跳过(已分配): sessionId={}", req.sessionId());
            return;
        }
        try {
            prefetchFutures.computeIfAbsent(req.sessionId(), k -> {
                log.info("prefetchAsync 发起异步分配: sessionId={}, slotKey={}",
                        req.sessionId(), req.slotKey());
                return CompletableFuture.supplyAsync(() -> {
                    SandboxHandle h = allocateSync(req);
                    sessionBindings.put(req.sessionId(), h);
                    return h;
                }).whenComplete((h, err) -> {
                    if (err != null) {
                        log.warn("prefetchAsync 异步分配失败(门控将降级同步兜底): sessionId={}, err={}",
                                req.sessionId(), err.getMessage());
                    } else {
                        prefetchFutures.remove(req.sessionId());
                    }
                });
            });
        } catch (Exception e) {
            log.warn("prefetchAsync 发起失败(已隔离): sessionId={}, error={}",
                    req.sessionId(), e.getMessage());
        }
    }
}
