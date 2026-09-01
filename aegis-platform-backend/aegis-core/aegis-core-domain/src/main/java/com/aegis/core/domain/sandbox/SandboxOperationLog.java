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

/**
 * 沙箱操作日志实体。
 *
 * <p>记录沙箱实例的所有状态变更操作，提供完整的审计追踪能力。
 * 每次实例状态变更（分配、释放、回收、销毁等）均写入一条日志记录。
 *
 * <h3>日志内容</h3>
 * <ul>
 *   <li>操作类型：ALLOCATE / RELEASE / RECLAIM / DESTROY / REPAIR / HEARTBEAT 等</li>
 *   <li>状态变更：from_status → to_status</li>
 *   <li>操作主体：userId / agentId / sessionId / tenantId</li>
 *   <li>关联实例：instanceId / slotKey</li>
 *   <li>操作结果：成功/失败 + 错误码/错误消息</li>
 * </ul>
 *
 * <h3>日志保留策略</h3>
 * <p>操作日志为追加写入，不修改不删除。保留期建议 90 天，超期可归档到冷存储。</p>
 *
 * @author wang.zhen
 * @see SandboxInstance
 * @see SandboxInstanceStatus
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sbx_operation_log")
public class SandboxOperationLog extends BaseEntity {

    /** 沙箱实例 ID */
    private String instanceId;

    /** 所属池 ID */
    private Long poolId;

    /** 租户 ID */
    private Long tenantId;

    /** 操作类型：ALLOCATE / RELEASE / RECLAIM / DESTROY / REPAIR / HEARTBEAT / FORCE_DESTROY */
    private String operationType;

    /** 操作来源：RUNTIME / ADMIN / SYSTEM */
    private String source;

    /** 状态变更前 */
    private SandboxInstanceStatus fromStatus;

    /** 状态变更后 */
    private SandboxInstanceStatus toStatus;

    /** 操作用户 ID */
    private Long userId;

    /** 操作 Agent ID */
    private Long agentId;

    /** 操作会话 ID */
    private String sessionId;

    /** 槽位键 */
    private String slotKey;

    /** 操作是否成功：1=成功 0=失败 */
    private Integer success;

    /** 错误码（失败时填写） */
    private String errorCode;

    /** 错误消息（失败时填写） */
    private String errorMessage;

    /** 操作详情 JSON（如配额、配置参数等） */
    private String detailJson;
}
