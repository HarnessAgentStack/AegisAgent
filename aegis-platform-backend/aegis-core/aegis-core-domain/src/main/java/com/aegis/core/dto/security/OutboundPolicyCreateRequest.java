package com.aegis.core.dto.security;

import com.aegis.core.enums.security.OutboundPolicyType;
import com.aegis.core.enums.security.OutboundScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 出站策略创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboundPolicyCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 策略类型：WHITELIST_DOMAIN / BLACKLIST_IP */
    private OutboundPolicyType policyType;

    /** 域名，如 api.example.com，支持通配符 *.example.com */
    private String domain;

    /** IP CIDR，如 192.168.1.0/24 */
    private String ipCidr;

    /** 端口限制，如 443、8080 */
    private Integer portLimit;

    /** 适用范围：ALL / AGENT / DEPT */
    private OutboundScope applicableScope;

    /** 范围配置，JSON 数组字符串如 [1,2,3] */
    private String scopeConfig;

    /** 有效时长，单位小时，0 表示永久有效 */
    private Integer validHours;

    /** 策略描述 */
    private String description;

    /** 是否启用 */
    private Boolean enabled;
}
