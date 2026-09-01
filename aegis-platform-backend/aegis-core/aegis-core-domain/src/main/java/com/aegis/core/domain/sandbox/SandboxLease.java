package com.aegis.core.domain.sandbox;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 沙箱租约实体。
 *
 * <p>沙箱租约（SandboxLease）记录沙箱实例的租借信息，用于实现基于会话的
 * 沙箱槽位级别租约管理，支撑租约过期、释放和续租等核心生命周期操作。</p>
 *
 * <h3>租约生命周期</h3>
 * <ul>
 *     <li>ACTIVE：生效中，租约正常持有</li>
 *     <li>EXPIRED：已过期，租约超时未释放</li>
 *     <li>RELEASED：已释放，租约主动归还</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sbx_lease")
public class SandboxLease {

    /** 主键ID，自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租约唯一标识，UUID 字符串，用于全链路追踪 */
    private String leaseId;

    /** 实例ID，关联 sbx_instance.instance_id */
    private String instanceId;

    /** 持有租约的会话ID */
    private String sessionId;

    /** 槽位键，用于标识租约对应的槽位 */
    private String slotKey;

    /** 租约过期时间 */
    private LocalDateTime expireAt;

    /** 租约状态：ACTIVE / EXPIRED / RELEASED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}