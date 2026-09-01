package com.aegis.core.dto.security;

import com.aegis.core.enums.resource.ToolPolicyAction;
import com.aegis.core.enums.resource.ToolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 工具策略创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolPolicyCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工具类型：READONLY / INTERNAL_API / WRITE / EXTERNAL_NETWORK / CODE_EXEC / HIGH_RISK */
    private ToolType toolType;

    /** 安全等级阈值，1-4 对应 L1-L4 */
    private Integer securityLevel;

    /** 处理动作：ALLOW（允许）/ APPROVE（需审批）/ REJECT（拒绝） */
    private ToolPolicyAction action;

    /** 策略描述，长度不超过 512 */
    private String description;

    /** 是否启用 */
    private Boolean enabled;
}
