package com.aegis.core.domain.sandbox;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 沙箱实例实体
 *
 * <p>沙箱实例（SandboxInstance）记录池内单个沙箱的运行状态与分配信息，
 * 是智能体代码执行的最小调度单元，支撑实例分配、回收与监控。</p>
 *
 * <h3>实例状态机（两参数驱动模型）</h3>
 * <ul>
 *     <li>OCCUPIED：占用中，已分配给会话使用</li>
 *     <li>IDLE (initialized=1)：干净空闲，标准 workspace 已初始化，runtime 可直接分配</li>
 *     <li>IDLE (initialized=0)：脏空闲，工作区有用户残留数据，等待 admin 回收（重初始化）</li>
 *     <li>ABNORMAL：异常，Pod 不可达，需 admin 修复（重建 Pod）</li>
 *     <li>DESTROYED：已销毁，Pod 已删除，终态</li>
 * </ul>
 *
 * <h3>initialized 字段的关键作用</h3>
 * <p>{@code initialized} 是区分"脏 / 标准 / 已装载"三种工作区形态的核心标志：
 * <ul>
 *     <li>0 = 脏：工作区有用户残留数据，runtime 不分配，等待 admin Reconcile 回收重初始化</li>
 *     <li>1 = 标准 workspace：干净（仅 input/output/scripts/temp 标准子目录），
 *         runtime 可直接分配（WHERE status='IDLE' AND initialized=1），资源尚未装载</li>
 *     <li>2 = 资源已装载：KB/SKILL/MCP 装载清单已物化到 Pod 工作区
 *         （由 SandboxResourceLoader 装载成功后写入），复用时配合
 *         {@link #resourceFingerprint} 判断是否可跳过装载</li>
 * </ul>
 *
 * <h3>分配/释放流程</h3>
 * <p>runtime 分配：从 IDLE(initialized=1) 池选取 → 标记 OCCUPIED<br>
 * runtime 释放：标记 IDLE(initialized=0) → 不销毁 Pod，等待 admin 回收<br>
 * admin 回收：重初始化工作区（exec 清理命令）→ 标记 IDLE(initialized=1)</p>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link BaseEntity}（平台级），但通过 tenantId 字段记录占用方租户，
 * 确保实例跨租户隔离使用；userId / agentId / sessionId 标识占用上下文。</p>
 *
 * @author wang.zhen
 * @see BaseEntity
 * @see SandboxPool
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sbx_instance")
public class SandboxInstance extends BaseEntity {
    /** 实例唯一标识，UUID 字符串，用于全链路追踪 */
    private String instanceId;
    /** 所属池 ID，关联 sandbox_pool.id */
    private Long poolId;
    /** 占用方租户 ID，记录当前分配的租户，实现跨租户隔离 */
    private Long tenantId;
    /** 实例状态：{@link SandboxInstanceStatus#OCCUPIED}（占用中）/ {@link SandboxInstanceStatus#IDLE}（空闲）/ {@link SandboxInstanceStatus#ABNORMAL}（异常） */
    private SandboxInstanceStatus status;
    /** 占用用户 ID，关联 user.id，记录实例分配给的用户 */
    private Long userId;
    /** 占用智能体 ID，关联 agent_def.id，记录实例服务的智能体 */
    private Long agentId;
    /** 占用会话 ID，关联 session.session_id，记录实例绑定的会话 */
    private String sessionId;
    /** Pod 名称，Kubernetes 中运行的容器名，由编排系统生成 */
    private String podName;
    /** 命名空间，Kubernetes 命名空间，实例运行的逻辑隔离空间 */
    private String namespace;
    /** CPU 使用率，0-1 之间，当前实例 CPU 占用比例，由监控采集 */
    private BigDecimal cpuUsage;
    /** 内存使用率，0-1 之间，当前实例内存占用比例，由监控采集 */
    private BigDecimal memUsage;
    /** 启动时间，实例创建启动的时间 */
    private LocalDateTime startTime;
    /** 运行时长，单位分钟，实例累计运行时间 */
    private Integer runtimeMinutes;
    /** 分配时间，实例被分配给会话的时间 */
    private LocalDateTime allocatedTime;
    /** 回收时间，实例被回收释放的时间 */
    private LocalDateTime recycledTime;

    /** AgentScope 沙箱快照ID，用于进程重启后恢复沙箱状态（对应 sbx_instance.snapshot_id） */
    private String snapshotId;

    /** 沙箱快照 OSS 对象键，MinIO 中存储的快照 tar 包路径（对应 sbx_instance.snapshot_oss_key） */
    private String snapshotOssKey;

    /** 隔离作用域：USER / AGENT / GLOBAL / SESSION（对应 sbx_instance.isolation_scope） */
    private String isolationScope;

    /** 沙箱槽位键，由 IsolationScope + 业务主键合成，决定 slot 复用粒度（对应 sbx_instance.slot_key） */
    private String slotKey;

    /** AgentScope SandboxManager sessionKey（跨节点 resume 用，对应 sbx_instance.agent_scope_session_key） */
    private String agentScopeSessionKey;

    /** 最新快照时间，记录最近一次快照保存的时间点（对应 sbx_instance.snapshot_time） */
    private LocalDateTime snapshotTime;

    /** 复用次数，同一 slot 被复用的次数，超限触发深度回收（对应 sbx_instance.reuse_count） */
    private Integer reuseCount;

    /**
     * 工作区初始化三态：
     * 0=脏（用户残留数据，待回收重初始化）；1=标准 workspace（干净，资源未装载）；
     * 2=资源已装载（KB/SKILL/MCP 清单已物化到 Pod 工作区）。
     */
    private Integer initialized;

    /**
     * 资源装载指纹：装载清单（资源类型+ID+版本 排序后）的 SHA-256。
     * 分配复用 OCCUPIED 实例时对比该指纹，一致则跳过装载（热复用秒级），
     * 不一致则增量重装载。对应 sbx_instance.resource_fingerprint。
     */
    private String resourceFingerprint;

    /** 创建时使用的基础镜像ID（关联 sbx_base_image） */
    private Long baseImageId;

    /** 最近回收时间（工作区重初始化时间） */
    private LocalDateTime lastRecycleTime;

    /** 最近回收策略（admin Reconcile 回收时记录：FULL_RESET/WORKSPACE_RESET/DESTROY） */
    private String recycleStrategy;

    /** 版本号（乐观并发控制），每次状态变更递增，防止并发状态更新 */
    private Integer version;

    /** 最后心跳时间（探活更新，用于超时回收判定） */
    private LocalDateTime lastHeartbeatTime;
}