package com.aegis.runtime.service.sandbox;

import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.runtime.integration.sandbox.AegisSandbox;
import com.aegis.runtime.integration.sandbox.AegisSandboxClient;
import com.aegis.runtime.integration.sandbox.AegisSandboxClientOptionsExt;
import com.aegis.runtime.integration.sandbox.AegisSandboxState;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 沙箱会话持有器（周期 3，per-session 粘性持有）。
 *
 * <p>核心语义：首次沙箱能力工具触发 {@code AegisSandboxClient.create}（惰性会话分配）；
 * 会话内持有复用（非 per-call）；会话结束触发 {@code release}（OCCUPIED→IDLE 复用不杀 Pod）。
 * 纯对话会话永不分配 = 真零容器。</p>
 *
 * <h3>职责分工</h3>
 * <ul>
 *   <li>本类：会话级缓存 Sandbox 实例，避免 per-call 重复 acquire</li>
 *   <li>{@link AegisSandboxAllocator}：admin 池分配权威（四级退化 + slotKey 隔离）</li>
 *   <li>{@link AegisSandboxClient}：框架 {@code SandboxClient} 适配（create/resume 委托 allocator）</li>
 *   <li>框架 {@code SandboxLifecycleMiddleware}：per-call acquire/release（AegisSandbox.stop/shutdown no-op 保护 Pod）</li>
 * </ul>
 *
 * <h3>与框架 per-call 的协同</h3>
 * <p>框架 {@code SandboxLifecycleMiddleware} 在每次 agent.call 时 acquire/release。
 * 本类的 {@code acquireIfNeeded} 在工具执行前先查会话缓存，命中则直接返回缓存的 AegisSandbox，
 * 跳过框架 create；未命中（首次沙箱工具）才调 {@code AegisSandboxClient.create}。
 * 会话结束 {@code releaseOnSessionEnd} 调 {@code AegisSandboxAllocator.release}（IDLE 复用不杀 Pod）。</p>
 *
 * <p>注：当前灰度 {@code aegis.sandbox.framework-drive.enabled=false} 默认关闭，
 * 本类缓存不激活（走 RemoteFS 现状）；灰度开启后由 {@code AegisExecuteTool} 等沙箱工具调用本类。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxSessionHolder {

    private final AegisSandboxClient aegisSandboxClient;
    private final AegisSandboxAllocator allocator;
    private final SandboxSnapshotSpec snapshotSpec;

    /** 会话级缓存：sessionId → AegisSandbox（首次沙箱工具触发后缓存，会话结束清理） */
    private final ConcurrentHashMap<String, AegisSandbox> sessionSandboxes = new ConcurrentHashMap<>();

    /**
     * 按需获取沙箱：首次需沙箱才 create，会话内复用。
     *
     * <p>纯对话会话永不调用本方法（由 {@link SandboxTrigger} 判定白名单工具才触发），
     * 因此纯对话真零容器。</p>
     *
     * @param sessionId 会话 ID
     * @param tenantId  租户 ID
     * @param userId    用户 ID
     * @param agentId   智能体 ID
     * @param agentType 智能体类型（UNIVERSAL/APPLICATION/SYSTEM）
     * @return 已 start 的 AegisSandbox（exec 可直接用）
     */
    public AegisSandbox acquireIfNeeded(String sessionId, Long tenantId, Long userId,
                                         Long agentId, String agentType) {
        return sessionSandboxes.computeIfAbsent(sessionId, sid -> {
            log.info("[sandbox-session] 首次沙箱分配: sessionId={}, agentType={}, agentId={}",
                    sid, agentType, agentId);
            AegisSandboxClientOptionsExt options = new AegisSandboxClientOptionsExt(
                    agentType, tenantId, userId, agentId, sid);
            try {
                Sandbox sandbox = aegisSandboxClient.create(
                        new WorkspaceSpec(), snapshotSpec, options);
                sandbox.start();
                AegisSandbox aegis = (AegisSandbox) sandbox;
                log.info("[sandbox-session] 沙箱已分配并启动: sessionId={}, instanceId={}, pod={}",
                        sid, aegis.getInstance().getInstanceId(), aegis.getInstance().getPodName());
                return aegis;
            } catch (Exception e) {
                log.error("[sandbox-session] 沙箱分配失败: sessionId={}, error={}",
                        sid, e.getMessage(), e);
                throw new RuntimeException("沙箱分配失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 会话结束释放沙箱（OCCUPIED→IDLE，复用不杀 Pod）。
     *
     * <p>由 {@code TaskExecutionService} 在会话结束/驱逐时调用。
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
     * 查询会话当前沙箱实例（供调试/观测用，不触发分配）。
     *
     * @param sessionId 会话 ID
     * @return 沙箱实例，未分配返回 null
     */
    public SandboxInstance getCurrent(String sessionId) {
        AegisSandbox sandbox = sessionSandboxes.get(sessionId);
        return sandbox != null ? sandbox.getInstance() : null;
    }
}
