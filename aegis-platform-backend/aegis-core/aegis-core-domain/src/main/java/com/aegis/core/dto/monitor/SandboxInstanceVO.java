package com.aegis.core.dto.monitor;

import com.aegis.core.enums.sandbox.SandboxInstanceStatus;
import com.aegis.core.enums.sandbox.SandboxPoolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 沙箱实例视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SandboxInstanceVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private String instanceId;

    /** 所属池ID */
    private Long poolId;

    /** 池类型 */
    private SandboxPoolType poolType;

    /** 租户ID */
    private Long tenantId;

    /** 状态 */
    private SandboxInstanceStatus status;

    /** 占用用户ID */
    private Long userId;

    /** 占用智能体ID */
    private Long agentId;

    /** 占用会话ID */
    private String sessionId;

    /** 启动时间 */
    private LocalDateTime startTime;

    /** 分配时间 */
    private LocalDateTime allocatedTime;

    /** 回收时间 */
    private LocalDateTime recycledTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
