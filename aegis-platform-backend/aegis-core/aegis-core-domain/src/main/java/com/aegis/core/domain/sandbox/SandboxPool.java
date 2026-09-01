package com.aegis.core.domain.sandbox;

import com.aegis.core.base.BaseEntity;
import com.aegis.core.enums.sandbox.SandboxPoolType;
import com.aegis.core.enums.sandbox.NetworkPolicy;
import com.aegis.core.enums.monitor.PoolStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 沙箱池实体
 *
 * <p>沙箱池（SandboxPool）是平台级运行时资源池配置实体，定义不同场景下的沙箱规格、
 * 容量、伸缩策略与资源限制，为智能体代码执行提供隔离的运行环境。</p>
 *
 * <h3>两参数驱动模型（核心）</h3>
 * <p>池的生命周期管理围绕 {@code minInstances} / {@code maxInstances} 两个核心参数自动执行：
 * <ul>
 *     <li>{@code minInstances}：预热基准，始终保持 ≥ 此数量的干净 IDLE 实例可供分配</li>
 *     <li>{@code maxInstances}：容量上限，总实例数不超过此值，超出时自动缩容</li>
 *     <li>{@code idleTimeoutMin}：空闲超时，IDLE(脏)实例超过此时间触发工作区重初始化</li>
 * </ul>
 * 不再存储 total_count / used_count（由 sbx_instance 实时统计），不再需要策略表。
 *
 * <h3>池类型（分类标签，不驱动回收策略）</h3>
 * <ul>
 *     <li>LIGHT：轻量池，低资源消耗，适用于简单脚本与查询</li>
 *     <li>STANDARD：标准池，中等资源，适用于常规代码执行</li>
 *     <li>HEAVY：强力池，高资源，适用于计算密集任务</li>
 *     <li>ISOLATED：隔离池，强隔离，适用于高风险代码</li>
 *     <li>DEBUG：调试池，用于开发调试场景</li>
 * </ul>
 *
 * <h3>资源管控</h3>
 * <p>通过 cpuLimit / memLimitMb / diskLimitGb 限制单实例资源，
 * networkPolicy 控制网络访问策略，镜像版本由 {@code sbx_base_image.tag} 统一管理。</p>
 *
 * @author wang.zhen
 * @see BaseEntity
 * @see SandboxInstance
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sbx_pool")
public class SandboxPool extends BaseEntity {
    /** 租户ID（0=系统共享池，>0=租户私有池） */
    private Long tenantId;
    /** 池编码（租户内唯一） */
    private String poolCode;
    /** K8s 命名空间名（按租户隔离：aegis-sbx-t{tenantId}-{type}） */
    private String namespace;
    /** 基础镜像ID（关联 sbx_base_image，镜像版本由 base_image.tag 决定） */
    private Long baseImageId;
    /** 池名称，长度不超过 128，如"轻量脚本池"、"强力计算池" */
    private String poolName;
    /** 沙箱池类型：{@link SandboxPoolType#LIGHT}（通用轻量）/ {@link SandboxPoolType#STANDARD}（标准执行）/ {@link SandboxPoolType#HEAVY}（重型计算）/ {@link SandboxPoolType#ISOLATED}（高安全隔离）/ {@link SandboxPoolType#DEBUG}（临时调试）等 */
    private SandboxPoolType poolType;
    /** 适用场景，长度不超过 512，说明该池适用的智能体场景 */
    private String applicableScene;

    // ===== 两参数驱动（核心生命周期管理参数） =====

    /** 最小实例数：始终保持的干净 IDLE 实例数（预热基准，Reconcile 保证 ≥ 此值） */
    private Integer minInstances;
    /** 最大实例数：总实例数上限（缩容阈值，Reconcile 保证 ≤ 此值） */
    private Integer maxInstances;
    /** 空闲超时（分钟）：IDLE(脏)实例超过此时间触发工作区重初始化（回收） */
    private Integer idleTimeoutMin;

    // ===== 资源规格 =====

    /** 网络策略：{@link NetworkPolicy#ISOLATED}（完全隔离）/ {@link NetworkPolicy#RESTRICTED}（限制出站）/ {@link NetworkPolicy#NO_EXTERNAL}（禁止外网）/ {@link NetworkPolicy#OPEN}（允许联网），控制实例网络权限 */
    private NetworkPolicy networkPolicy;
    /** CPU 限制，单实例最大 CPU 核数，如 0.5、1、2 */
    private String cpuLimit;
    /** 内存限制，单位 MB，单实例最大内存 */
    private Integer memLimitMb;
    /** 磁盘限制，单位 GB，单实例最大磁盘空间 */
    private Integer diskLimitGb;

    /** 状态：{@link PoolStatus#ENABLED}（启用）/ {@link PoolStatus#DISABLED}（禁用）/ {@link PoolStatus#MAINTAINING}（维护中），管理员控制池可用性 */
    private PoolStatus status;

    /** 上次 Reconcile 时间（用于分布式幂等控制） */
    private java.time.LocalDateTime lastReconcileTime;
}