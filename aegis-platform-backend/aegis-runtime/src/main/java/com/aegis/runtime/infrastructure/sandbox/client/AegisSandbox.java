package com.aegis.runtime.infrastructure.sandbox.client;

import com.aegis.core.spi.ISandboxBackend;
import com.aegis.runtime.service.sandbox.AegisSandboxCoordinator;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.AbstractBaseSandbox;
import io.agentscope.harness.agent.sandbox.ExecResult;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;

/**
 * Aegis 沙箱实现：将 AgentScope {@link io.agentscope.harness.agent.sandbox.Sandbox} 契约
 * 桥接到 Aegis {@link ISandboxBackend} SPI。
 *
 * <p>继承 {@link AbstractBaseSandbox}，实现 6 个抽象方法，全部委托给
 * {@link ISandboxBackend}（Docker/Process 后端）。
 *
 * <h3>★ runtime 只释放不回收</h3>
 * <p>shutdown() 通过 {@link AegisSandboxCoordinator#releaseSlot} 释放沙箱：
 * 标记 IDLE，不销毁容器。回收由 admin 的 SandboxReconcileScheduler 统一执行。
 *
 * <h3>tar 归档处理</h3>
 * <p>由于 {@link ISandboxBackend#exec} 返回 String 而非二进制流，
 * {@code doPersistWorkspace} / {@code doHydrateWorkspace} 通过 base64 编码传递 tar 二进制数据。
 * P1 阶段工作区 tar 普遍 < 5MB，base64 编码后 < 7MB，在 exec stdout 限制内可接受。
 *
 * @author wang.zhen
 */
@Slf4j
public class AegisSandbox extends AbstractBaseSandbox {

    private static final int EXEC_TIMEOUT_SEC = 120;
    private static final int PROBE_TIMEOUT_SEC = 10;

    private final ISandboxBackend sandboxBackend;
    private final AegisSandboxState state;
    private final AegisSandboxCoordinator coordinator;
    private final MinioSnapshotClient minioClient;

    /** P0-08: MinIO 中转传输的 bucket 名称 */
    private static final String WORKSPACE_BUCKET = "aegis-workspace-transit";

    /** P0-08: 单次传输大小阈值，超过则使用 MinIO 中转 */
    private static final int BASE64_THRESHOLD = 900_000;

    /**
     * 向后兼容构造函数（coordinator 为 null，不执行统一回收）。
     */
    public AegisSandbox(AegisSandboxState state, ISandboxBackend sandboxBackend) {
        this(state, sandboxBackend, null, null);
    }

    /**
     * P0 构造函数：注入 coordinator 实现统一回收。
     */
    public AegisSandbox(AegisSandboxState state, ISandboxBackend sandboxBackend,
                        AegisSandboxCoordinator coordinator) {
        this(state, sandboxBackend, coordinator, null);
    }

    /**
     * P0-08 构造函数：同时注入 MinioSnapshotClient 实现大工作区中转传输。
     */
    public AegisSandbox(AegisSandboxState state, ISandboxBackend sandboxBackend,
                        AegisSandboxCoordinator coordinator, MinioSnapshotClient minioClient) {
        super(state);
        this.state = state;
        this.sandboxBackend = sandboxBackend;
        this.coordinator = coordinator;
        this.minioClient = minioClient;
    }

    /**
     * 获取有效的沙箱执行标识。
     *
     * <p>K8s 模式下返回 {@code namespace/podName}；
     * Process 本地模式（无 K8s 资源）下回退为 instanceId，确保本地开发可用。
     *
     * @return 有效的沙箱执行标识
     * @throws IllegalStateException 如果连 instanceId 都不存在（极端异常）
     */
    private String requireValidK8sResourceId() {
        String k8sResourceId = state.getK8sResourceId();
        if (k8sResourceId != null && !k8sResourceId.isEmpty()) {
            return k8sResourceId;
        }
        // Process 本地模式：回退为 instanceId
        String instanceId = state.getInstanceId();
        if (instanceId != null && !instanceId.isEmpty()) {
            log.debug("Process 模式回退：使用 instanceId 作为沙箱标识: {}", instanceId);
            return instanceId;
        }
        throw new IllegalStateException(
                "沙箱状态无效：缺少 K8s 资源标识和 instanceId，无法执行命令");
    }

    @Override
    protected ExecResult doExec(RuntimeContext runtimeContext, String command, int timeoutSeconds)
            throws Exception {
        String k8sResourceId = requireValidK8sResourceId();

        // ★ Pod 存活检查：执行前验证 Pod 是否存在
        if (!isPodAlive(k8sResourceId)) {
            log.warn("Pod 不存在，执行命令前检测到: instanceId={}, k8sResourceId={}, command={}",
                    state.getInstanceId(), k8sResourceId, command.length() > 50 ? command.substring(0, 50) + "..." : command);
            throw new WorkspaceUnavailableException(
                    "Pod 不存在，无法执行命令: instanceId=" + state.getInstanceId());
        }

        log.debug("沙箱执行命令: instanceId={}, k8sResourceId={}, command={}",
                state.getInstanceId(), k8sResourceId, command.length() > 100 ? command.substring(0, 100) + "..." : command);
        ISandboxBackend.ExecResult raw = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, command, timeoutSeconds);
        return new ExecResult(raw.exitCode, raw.stdout, raw.stderr, false);
    }

    /**
     * P5-2：创建会话隔离的工作区根目录及标准子目录。
     *
     * <p>工作区根路径已按 sessionId + strategy 派生（见
     * {@link AegisSandboxState#resolveWorkspaceRoot()}），在该路径下
     * 创建 input/output/scripts/temp 四个标准子目录，确保并发会话文件隔离。
     *
     * <p>★ 优雅降级：如果 Pod 不存在（K8s 重启后丢失），跳过创建并标记状态为不可用，
     * 让上层有机会重新创建沙箱。
     */
    @Override
    protected void doSetupWorkspace() throws Exception {
        String root = getWorkspaceRoot();
        String k8sResourceId = requireValidK8sResourceId();

        if (!isPodAlive(k8sResourceId)) {
            log.warn("Pod 不存在，跳过工作区创建: instanceId={}, k8sResourceId={}",
                    state.getInstanceId(), k8sResourceId);
            throw new WorkspaceUnavailableException(
                    "Pod 不存在，需要重新创建沙箱: instanceId=" + state.getInstanceId());
        }

        String cmd = "mkdir -p " + root
                + " " + root + "/input"
                + " " + root + "/output"
                + " " + root + "/scripts"
                + " " + root + "/temp";
        ISandboxBackend.ExecResult result = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, cmd, PROBE_TIMEOUT_SEC);
        if (result.exitCode != 0) {
            throw new RuntimeException("创建工作区目录失败: " + result.stderr);
        }
        log.debug("工作区目录创建完成: instanceId={}, root={}", state.getInstanceId(), root);
    }

    /**
     * P0-08: 将容器内工作区打包为 tar 归档。
     *
     * <p>小工作区（base64 < 900KB）直接通过 base64 stdout 传输。
     * 大工作区通过 MinIO 中转传输，避免 exec stdout 1MB 截断导致数据丢失。
     *
     * <p>流程：
     * <ol>
     *   <li>小工作区：{@code tar -cf - -C /workspace . | base64 -w 0} → 解码 stdout</li>
     *   <li>大工作区：tar 打包后通过 cat | curl 上传 MinIO → 从 MinIO 下载</li>
     * </ol>
     *
     * <p>★ 优雅降级：如果 Pod 不存在，返回空的 InputStream。
     */
    @Override
    protected InputStream doPersistWorkspace() throws Exception {
        String k8sResourceId = requireValidK8sResourceId();

        if (!isPodAlive(k8sResourceId)) {
            log.warn("Pod 不存在，返回空工作区: instanceId={}, k8sResourceId={}",
                    state.getInstanceId(), k8sResourceId);
            return new ByteArrayInputStream(new byte[0]);
        }

        // 先尝试 base64 直传（小工作区）
        String cmd = "tar -cf - -C " + getWorkspaceRoot() + " . 2>/dev/null | base64 -w 0";
        ISandboxBackend.ExecResult result = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, cmd, EXEC_TIMEOUT_SEC);
        if (result.exitCode != 0) {
            throw new RuntimeException("打包工作区失败: " + result.stderr);
        }

        String base64Str = result.stdout.trim();
        // P0 SNP-01 修复：检测截断标记，若被截断则走 MinIO 中转
        if (base64Str.contains("truncated")) {
            log.warn("工作区 base64 输出被截断，切换 MinIO 中转: instanceId={}", state.getInstanceId());
            return persistWorkspaceViaMinio();
        }
        if (base64Str.length() < BASE64_THRESHOLD) {
            byte[] tarBytes = Base64.getDecoder().decode(base64Str);
            log.debug("工作区打包完成（base64 直传）: instanceId={}, size={}KB",
                    state.getInstanceId(), tarBytes.length / 1024);
            return new ByteArrayInputStream(tarBytes);
        }

        // P0-08: 大工作区走 MinIO 中转
        log.info("P0-08: 工作区较大 ({}KB base64)，切换 MinIO 中转传输: instanceId={}",
                base64Str.length() / 1024, state.getInstanceId());
        return persistWorkspaceViaMinio();
    }

    /**
     * P0-08: 通过 MinIO 中转传输大工作区。
     *
     * <p>流程：
     * <ol>
     *   <li>容器内 tar 打包到 /tmp/workspace.tar</li>
     *   <li>通过 exec cat + base64 分块写入 MinIO（或直接上传）</li>
     *   <li>从 MinIO 下载 tar 字节流</li>
     * </ol>
     */
    private InputStream persistWorkspaceViaMinio() throws Exception {
        String k8sResourceId = requireValidK8sResourceId();
        if (minioClient == null) {
            // P0 SNP-01 修复：MinIO 不可用时检测截断，避免解码非法 base64 导致数据损坏
            log.warn("P0-08: MinioSnapshotClient 不可用，退化为 base64 直传: instanceId={}",
                    state.getInstanceId());
            String cmd = "tar -cf - -C " + getWorkspaceRoot() + " . 2>/dev/null | base64 -w 0";
            ISandboxBackend.ExecResult result = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, cmd, EXEC_TIMEOUT_SEC);
            String b64 = result.stdout != null ? result.stdout.trim() : "";
            // 检测是否被 exec stdout 截断
            if (b64.contains("truncated") || b64.length() >= 1_000_000) {
                throw new RuntimeException(
                        "P0 SNP-01: 工作区过大且 MinIO 不可用，base64 输出被截断，无法安全持久化: instanceId="
                                + state.getInstanceId() + ", b64Len=" + b64.length());
            }
            byte[] tarBytes = Base64.getDecoder().decode(b64);
            return new ByteArrayInputStream(tarBytes);
        }

        // 容器内打包到文件
        String packCmd = "tar -cf /tmp/aegis-workspace.tar -C " + getWorkspaceRoot() + " . 2>/dev/null && wc -c < /tmp/aegis-workspace.tar";
        ISandboxBackend.ExecResult packResult = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, packCmd, EXEC_TIMEOUT_SEC);
        if (packResult.exitCode != 0) {
            throw new RuntimeException("P0-08: MinIO 中转打包失败: " + packResult.stderr);
        }

        // 分块读取并通过 base64 上传到 MinIO
        String objectKey = "workspace-" + state.getInstanceId() + "-" + System.currentTimeMillis() + ".tar";
        int chunkSize = 700_000; // 每块 700KB base64
        int offset = 0;
        StringBuilder assembled = new StringBuilder();

        while (true) {
            String readCmd = "dd if=/tmp/aegis-workspace.tar bs=1 skip=" + offset
                    + " count=" + chunkSize + " 2>/dev/null | base64 -w 0";
            ISandboxBackend.ExecResult chunkResult = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, readCmd, EXEC_TIMEOUT_SEC);
            if (chunkResult.exitCode != 0 || chunkResult.stdout == null || chunkResult.stdout.trim().isEmpty()) {
                break;
            }
            assembled.append(chunkResult.stdout.trim());
            if (chunkResult.stdout.trim().length() < chunkSize) {
                break;
            }
            offset += chunkSize;
        }

        // 清理容器内临时文件
        try {
            sandboxBackend.exec(
                state.getTenantId(), k8sResourceId,
                    "rm -f /tmp/aegis-workspace.tar", 10);
        } catch (Exception e) {
            log.warn("清理沙箱临时文件失败: instanceId={}, error={}", state.getInstanceId(), e.getMessage());
        }

        byte[] tarBytes = Base64.getDecoder().decode(assembled.toString());
        log.info("P0-08: MinIO 中转传输完成: instanceId={}, size={}KB",
                state.getInstanceId(), tarBytes.length / 1024);
        return new ByteArrayInputStream(tarBytes);
    }

    /**
     * P0-08: 将 tar 归档解包到容器内工作区（分块写入防截断）。
     *
     * <p>大工作区（base64 > 900KB）采用分块写入策略：
     * <ol>
     *   <li>每块 700KB base64，通过 exec 写入容器内 /tmp/workspace.tar.b64</li>
     *   <li>追加模式写入（echo >> file）</li>
     *   <li>最终 base64 -d | tar -xf 解包</li>
     * </ol>
     */
    @Override
    protected void doHydrateWorkspace(InputStream archive) throws Exception {
        String k8sResourceId = requireValidK8sResourceId();
        byte[] tarBytes = archive.readAllBytes();
        String base64 = Base64.getEncoder().encodeToString(tarBytes);

        if (base64.length() < BASE64_THRESHOLD) {
            // 小工作区：直接 base64 解码解包
            // P1 SNP-02 修复：ISandboxBackend.exec 不支持 stdin 传入，使用 printf 替代 echo
            // 已确认 base64 字符集（A-Z a-z 0-9 + / =）不含 shell 元字符，风险可控
            String cmd = "printf '%s' '" + base64 + "' | base64 -d | tar -xf - -C " + getWorkspaceRoot();
            ISandboxBackend.ExecResult result = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, cmd, EXEC_TIMEOUT_SEC);
            if (result.exitCode != 0) {
                throw new RuntimeException("解包工作区失败: " + result.stderr);
            }
            log.debug("工作区解包完成（直传）: instanceId={}, size={}KB",
                    state.getInstanceId(), tarBytes.length / 1024);
            return;
        }

        // P0-08: 大工作区分块写入
        log.info("P0-08: 大工作区分块写入 ({}KB base64): instanceId={}",
                base64.length() / 1024, state.getInstanceId());

        String tmpFile = "/tmp/aegis-hydrate.b64";
        // 清理旧文件
        sandboxBackend.exec(
                state.getTenantId(), k8sResourceId,
                "rm -f " + tmpFile, 5);

        int chunkSize = 700_000;
        int offset = 0;
        while (offset < base64.length()) {
            int end = Math.min(offset + chunkSize, base64.length());
            String chunk = base64.substring(offset, end);
            // 首块用 > 创建，后续块用 >> 追加
            // P1 SNP-02 修复：使用 printf 替代 echo -n，行为更可移植且不追加换行
            String redir = (offset == 0) ? ">" : ">>";
            String cmd = "printf '%s' '" + chunk + "' " + redir + " " + tmpFile;
            ISandboxBackend.ExecResult result = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, cmd, EXEC_TIMEOUT_SEC);
            if (result.exitCode != 0) {
                throw new RuntimeException("P0-08: 分块写入失败 offset=" + offset + ": " + result.stderr);
            }
            offset = end;
        }

        // 解码并解包
        String unpackCmd = "base64 -d " + tmpFile + " | tar -xf - -C " + getWorkspaceRoot()
                + " && rm -f " + tmpFile;
        ISandboxBackend.ExecResult result = sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, unpackCmd, EXEC_TIMEOUT_SEC);
        if (result.exitCode != 0) {
            throw new RuntimeException("P0-08: 解包工作区失败: " + result.stderr);
        }
        log.info("P0-08: 工作区分块解包完成: instanceId={}, size={}KB",
                state.getInstanceId(), tarBytes.length / 1024);
    }

    /**
     * 销毁工作区（清理容器内 /workspace 下所有内容）。
     */
    @Override
    protected void doDestroyWorkspace() throws Exception {
        String k8sResourceId = requireValidK8sResourceId();
        String cmd = "rm -rf " + getWorkspaceRoot() + "/*";
        sandboxBackend.exec(
                state.getTenantId(), k8sResourceId, cmd, PROBE_TIMEOUT_SEC);
        log.debug("工作区已清理: instanceId={}", state.getInstanceId());
    }

    @Override
    protected String getWorkspaceRoot() {
        return state.resolveWorkspaceRoot();
    }

    /**
     * 释放沙箱（★ 不销毁容器，不执行回收，不在每轮回复后释放槽位）。
     *
     * <p>AgentScope 框架在每轮回复后会调用 {@link AbstractBaseSandbox#close()} -> shutdown()，
     * 但多轮对话中沙箱应保持 OCCUPIED 状态供跨轮次复用。
     *
     * <h3>释放时机</h3>
     * <ul>
     *   <li>coordinator != null：shutdown() 为空操作，槽位释放由
     *       {@link com.aegis.runtime.integration.agent.AegisAgentInstanceManager#closeAgent}
     *       在 Agent 实例驱逐时统一调用 {@link AegisSandboxCoordinator#releaseSlot} 执行</li>
     *   <li>coordinator == null（降级路径）：直接销毁容器（向后兼容）</li>
     * </ul>
     * 回收（销毁容器/重建 Pod）由 admin 的 SandboxReconcileScheduler 统一执行。
     */
    @Override
    public void shutdown() throws Exception {
        if (state.getInstanceId() == null || !state.isContainerOwned()) {
            return;
        }

        // ★ coordinator 模式：不在每轮回复后释放槽位，保持 OCCUPIED 供跨轮次复用
        // 槽位释放由 AegisAgentInstanceManager.closeAgent() 在 Agent 驱逐时统一执行
        if (coordinator != null && state.getTenantId() != null) {
            log.debug("沙箱 shutdown 跳过释放（保持 OCCUPIED 供跨轮次复用）: instanceId={}",
                    state.getInstanceId());
            return;
        }

        // 降级路径：coordinator 为 null 时直接销毁（向后兼容）
        String k8sResourceId = state.getK8sResourceId();
        if (k8sResourceId == null || k8sResourceId.isEmpty()) {
            log.warn("降级路径: K8s 资源标识无效，跳过快照和销毁: instanceId={}", state.getInstanceId());
            return;
        }
        try {
            String snapshotId = sandboxBackend.snapshot(state.getTenantId(), k8sResourceId);
            log.info("降级路径强制快照成功: instanceId={}, snapshotId={}",
                    state.getInstanceId(), snapshotId);
        } catch (Exception e) {
            log.warn("降级路径快照失败，仍继续销毁: instanceId={}",
                    state.getInstanceId(), e);
        }
        try {
            sandboxBackend.destroy(state.getTenantId(), k8sResourceId);
            log.info("沙箱容器已销毁（降级路径）: instanceId={}", state.getInstanceId());
        } catch (Exception e) {
            log.warn("销毁沙箱容器失败（可能已销毁）: instanceId={}", state.getInstanceId(), e);
        }
    }

    /**
     * 检查 Pod 是否存活。
     *
     * <p>通过 probeAlive 接口探测 K8s Pod 是否存在且可访问。
     * 用于在执行操作前检查 Pod 状态，避免对已销毁的 Pod 执行操作导致异常。
     *
     * @param k8sResourceId K8s 资源标识（namespace/podName）
     * @return true 表示 Pod 存活，false 表示 Pod 不存在或不可访问
     */
    private boolean isPodAlive(String k8sResourceId) {
        try {
            if (state.getTenantId() == null || k8sResourceId == null) {
                return false;
            }
            return sandboxBackend.probeAlive(state.getTenantId(), k8sResourceId);
        } catch (Exception e) {
            log.debug("Pod 探活异常: k8sResourceId={}, error={}", k8sResourceId, e.getMessage());
            return false;
        }
    }

    /**
     * 工作区不可用异常。
     *
     * <p>当 Pod 不存在或不可访问时抛出此异常，提示上层需要重新创建沙箱。
     */
    public static class WorkspaceUnavailableException extends Exception {
        public WorkspaceUnavailableException(String message) {
            super(message);
        }
    }
}