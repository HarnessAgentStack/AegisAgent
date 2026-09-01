package com.aegis.core.dto.security;

import com.aegis.core.enums.security.OutboundPolicyType;
import com.aegis.core.enums.security.OutboundScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 出站策略更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboundPolicyUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 策略类型 */
    private OutboundPolicyType policyType;

    /** 域名 */
    private String domain;

    /** IP CIDR */
    private String ipCidr;

    /** 端口限制 */
    private Integer portLimit;

    /** 适用范围 */
    private OutboundScope applicableScope;

    /** 范围配置 */
    private String scopeConfig;

    /** 有效时长，单位小时 */
    private Integer validHours;

    /** 策略描述 */
    private String description;

    /** 是否启用 */
    private Boolean enabled;
}
