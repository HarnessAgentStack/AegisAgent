package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.runtime.integration.sandbox.AegisSandbox;
import com.aegis.runtime.service.sandbox.AegisSandboxAllocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱会话持有器（周期 3，per-session 粘性持有，framework-drive 模式下的释放记账层）。
 *
 * <p>核心语义：跨轮/跨调用 Pod 复用由框架优先级 3（Redis 持久化 state resume）承担，
 * 本类只负责<b>释放记账</b>——记录"当前会话正在使用哪个沙箱实例"，
 * 供任务终态/会话关闭时调用 {@code release}（OCCUPIED→IDLE 复用不杀 Pod）。</p>
 *
 * <h3>职责分工（framework-drive.enabled=true 生产链路）</h3>
 * <ul>
 *   <li>框架 {@code SandboxManager} 优先级 3：Redis stateStore 加载 → {@code AegisSandboxClient.resume}
 *       → 探活复用 Pod（跨轮粘性的权威机制）</li>
 *   <li>{@code AegisSandboxClient.create/resume}：分配或复用成功后调 {@link #register} 登记本表</li>
 *   <li>{@code TaskExecutionService}（每轮 doFinally 终态）/ {@code SessionManageService}（会话关闭）：
 *       调 {@link #releaseOnSessionEnd} 释放（幂等）</li>
 *   <li>{@link AegisSandboxAllocator}：admin 池分配权威（四级退化 + slotKey 隔离）</li>
 *   <li>框架 {@code SandboxLifecycleMiddleware}：per-call acquire/release（AegisSandbox.stop/shutdown
 *       no-op 保护 Pod，真正的释放走本表 → allocator.release）</li>
 * </ul>
 *
 * <p>纯对话会话（智能体未绑定 sandbox_execution=true 工具）不装配 SandboxContext，
 * 框架不注册 SandboxLifecycleMiddleware，永不触发分配 = 真零容器。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxSessionHolder {

    private final AegisSandboxAllocator allocator;

    /** 会话级登记：sessionId → AegisSandbox（分配/复用成功后登记，释放时移除） */
    private final ConcurrentHashMap<String, AegisSandbox> sessionSandboxes = new ConcurrentHashMap<>();

    /**
     * 登记会话沙箱（由 {@code AegisSandboxClient.create/resume} 在分配或探活复用成功后调用）。
     *
     * <p>幂等：同一会话重复登记时覆盖旧值（并发边界下以最后一次成功分配为准）。</p>
     *
     * @param sessionId 会话 ID
     * @param sandbox   已就绪的 AegisSandbox
     */
    public void register(String sessionId, AegisSandbox sandbox) {
        if (sessionId == null || sandbox == null) {
            return;
        }
        sessionSandboxes.put(sessionId, sandbox);
        log.debug("[sandbox-session] 登记会话沙箱: sessionId={}, instanceId={}",
                sessionId, sandbox.getInstance().getInstanceId());
    }

    /**
     * 会话结束/任务终态释放沙箱（OCCUPIED→IDLE，复用不杀 Pod）。
     *
     * <p>由 {@code TaskExecutionService}（每轮 doFinally 终态兜底）与
     * {@code SessionManageService}（会话关闭/删除）调用，幂等安全。
     * 框架 {@code SandboxLifecycleMiddleware} 的 per-call release 调 AegisSandbox.stop/shutdown
     * (no-op)，本方法才是真正释放（清占用字段 + IDLE）。</p>
     *
     * @param sessionId 会话 ID
     */
    public void releaseOnSessionEnd(String sessionId) {
        AegisSandbox sandbox = sessionSandboxes.remove(sessionId);
        if (sandbox == null) {
            return;
        }
        SandboxInstance inst = sandbox.getInstance();
        log.info("[sandbox-session] 会话结束释放沙箱: sessionId={}, instanceId={}, pod={}",
                sessionId, inst.getInstanceId(), inst.getPodName());
        allocator.release(inst);
    }

    /**
     * 查询会话当前已物化的沙箱（供 LazyAegisSandbox create 轨道物化快速路径，
     * 同会话并发工具调用复用同一实例，不触发分配；不物化、不触发分配）。
     *
     * @param sessionId 会话 ID
     * @return 已登记的沙箱，未登记返回 null
     */
    public AegisSandbox getCurrentSandbox(String sessionId) {
        return sessionSandboxes.get(sessionId);
    }
}
