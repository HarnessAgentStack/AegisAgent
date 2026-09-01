package com.aegis.core.spi;
import com.aegis.core.domain.sandbox.SandboxInstance;
import com.aegis.core.domain.security.OutboundPolicy;

/**
 * 沙箱后端协议。
 *
 * <p>抽象代码执行沙箱的统一协议，屏蔽底层实现差异（K8s Pod / Firecracker / gVisor / Docker）。
 * 支持按租户命名空间创建/销毁/快照沙箱实例，为智能体工具调用提供隔离的代码执行环境。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>租户命名空间：沙箱按 tenant-{id} 命名空间隔离，资源配额（CPU/内存/时长）按租户限制</li>
 *   <li>快照恢复：支持会话级快照，会话恢复时从快照还原执行上下文</li>
 *   <li>生命周期：创建→运行→销毁，超时自动回收，异常实例由 SandboxHealthMonitor 标记并触发回收</li>
 *   <li>网络隔离：默认出站受 {@code OutboundPolicy} 约束，按需放行白名单</li>
 * </ul>
 *
 * <h3>关联组件</h3>
 * <ul>
 *   <li>{@code SandboxHealthMonitor} - 沙箱健康监控，对接本协议进行实例探活</li>
 *   <li>{@link com.aegis.core.domain.sandbox.SandboxInstance} - 沙箱实例领域模型</li>
 * </ul>
 *
 * <p>本协议为同步契约，保持 aegis-core 不引入响应式框架。
 *
 * @author wang.zhen
 */
public interface ISandboxBackend {

    /**
     * 创建沙箱实例。
     *
     * @param tenantId  租户ID
     * @param image     沙箱镜像（如 python:3.11-slim）
     * @param cpu       CPU 配额（核）
     * @param memoryMb  内存配额（MB）
     * @return 沙箱实例ID
     */
    String create(Long tenantId, String image, double cpu, int memoryMb);

    /**
     * 在指定池命名空间内创建沙箱 Pod（池内动态扩容）。
     *
     * <p>当 IDLE 池为空且未达 max_instances 时，runtime 在目标池的命名空间内
     * 按池配置（镜像/资源限额/标签）创建新 Pod，确保新实例归属池、可被
     * admin Reconcile 统一纳管（回收还原、预热补充、缩容销毁）。
     *
     * <p>默认实现退化为 {@link #create}（非 K8s 后端无命名空间概念）。
     *
     * @param tenantId  租户ID（占用方租户）
     * @param namespace 池命名空间（来自 sbx_pool.namespace）
     * @param image     池关联镜像完整引用（来自 sbx_base_image）
     * @param cpu       CPU 配额（核，来自 sbx_pool.cpu_limit）
     * @param memoryMb  内存配额（MB，来自 sbx_pool.mem_limit_mb）
     * @param labels    Pod 标签（tenant/pool 等，用于池归属标识）
     * @return 沙箱实例ID（K8s 后端为 {@code namespace/podName}）
     */
    default String createInPool(Long tenantId, String namespace, String image,
                                 double cpu, int memoryMb, java.util.Map<String, String> labels) {
        return create(tenantId, image, cpu, memoryMb);
    }

    /**
     * 销毁沙箱实例。
     *
     * @param tenantId    租户ID
     * @param instanceId  沙箱实例ID
     * @return true 表示销毁成功
     */
    boolean destroy(Long tenantId, String instanceId);

    /**
     * 创建沙箱快照（用于会话恢复）。
     *
     * @param tenantId   租户ID
     * @param instanceId 沙箱实例ID
     * @return 快照ID
     */
    String snapshot(Long tenantId, String instanceId);

    /**
     * 从快照恢复沙箱实例。
     *
     * @param tenantId   租户ID
     * @param snapshotId 快照ID
     * @return 恢复后的沙箱实例ID
     */
    String restore(Long tenantId, String snapshotId);

    /**
     * 在沙箱内执行命令。
     *
     * @param tenantId   租户ID
     * @param instanceId 沙箱实例ID
     * @param command    执行命令
     * @param timeoutSec 超时时间（秒）
     * @return 执行结果（stdout/stderr/exitCode）
     */
    ExecResult exec(Long tenantId, String instanceId, String command, long timeoutSec);

    /**
     * 探活沙箱实例（检查是否存活）。
     *
     * <p>默认实现通过 {@code exec("echo ready", 10)} 间接探活，
     * K8s 等后端可覆写为更高效的 Pod Phase 检查。
     *
     * @param tenantId   租户ID
     * @param instanceId 沙箱实例ID
     * @return true 表示存活
     */
    default boolean probeAlive(Long tenantId, String instanceId) {
        try {
            ExecResult result = exec(tenantId, instanceId, "echo ready", 10);
            return result != null && result.exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 探活沙箱实例（通过 podName 和 namespace）。
     *
     * <p>支持直接使用 podName 和 namespace 进行探活，避免 instanceId 格式不匹配的问题。
     *
     * @param tenantId  租户ID
     * @param podName   Pod 名称
     * @param namespace 命名空间
     * @return true 表示存活
     */
    default boolean probeAlive(Long tenantId, String podName, String namespace) {
        try {
            ExecResult result = exec(tenantId, namespace + "/" + podName, "echo ready", 10);
            return result != null && result.exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 命令执行结果。 */
    class ExecResult {
        /** 标准输出 */
        public String stdout;
        /** 标准错误 */
        public String stderr;
        /** 退出码，0 表示成功 */
        public int exitCode;
    }
}
