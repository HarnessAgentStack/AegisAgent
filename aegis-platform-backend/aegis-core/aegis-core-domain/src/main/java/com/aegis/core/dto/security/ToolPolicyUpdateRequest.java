package com.aegis.core.dto.security;

import com.aegis.core.enums.resource.ToolPolicyAction;
import com.aegis.core.enums.resource.ToolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工具策略更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicyUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

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
}
