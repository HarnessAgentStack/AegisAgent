package com.aegis.core.dto.security;

import com.aegis.core.enums.resource.ToolPolicyAction;
import com.aegis.core.enums.resource.ToolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工具策略视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 策略ID */
    private Long id;

    /** 租户ID */
    private Long tenantId;

    /** 工具类型 */
    private ToolType toolType;

    /** 安全等级阈值，1-4 对应 L1-L4 */
    private Integer securityLevel;

    /** 处理动作：ALLOW / APPROVE / REJECT */
    private ToolPolicyAction action;

    /** 策略描述 */
    private String description;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
