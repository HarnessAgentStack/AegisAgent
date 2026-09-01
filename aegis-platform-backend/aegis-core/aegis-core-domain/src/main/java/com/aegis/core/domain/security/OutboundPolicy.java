package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.OutboundPolicyType;
import com.aegis.core.enums.security.OutboundScope;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 出站策略实体
 *
 * <p>出站策略（OutboundPolicy）定义沙箱实例的网络出站访问控制规则，控制智能体
 * 可访问的外部域名、IP、端口，防止数据外泄与未授权访问。</p>
 *
 * <h3>策略类型</h3>
 * <ul>
 *     <li>WHITELIST：白名单，仅允许指定目标访问</li>
 *     <li>BLACKLIST：黑名单，禁止指定目标访问</li>
 *     <li>DOMAIN：域名级控制，按 domain 匹配</li>
 *     <li>IP：IP 级控制，按 ipCidr 匹配</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，出站策略带 tenantId 隔离；
 * applicableScope 与 scopeConfig 灵活支持按智能体、部门等维度的策略应用。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sec_outbound_policy")
public class OutboundPolicy extends TenantEntity {
    /** 策略类型：{@link OutboundPolicyType#WHITELIST_DOMAIN}（白名单域名）/ {@link OutboundPolicyType#BLACKLIST_IP}（黑名单IP） */
    private OutboundPolicyType policyType;
    /** 域名，控制访问的目标域名，如 api.example.com，支持通配符 *.example.com */
    private String domain;
    /** IP CIDR，控制访问的目标 IP 段，如 192.168.1.0/24 */
    private String ipCidr;
    /** 端口限制，允许访问的端口号，如 443、8080，多个端口逗号分隔 */
    private Integer portLimit;
    /** 适用范围：{@link OutboundScope#ALL}（全部）/ {@link OutboundScope#AGENT}（指定智能体）/ {@link OutboundScope#DEPT}（指定部门） */
    private OutboundScope applicableScope;
    /** 范围配置，JSON 数组字符串如 [1,2,3]，依据 applicableScope 关联对应对象 ID */
    private String scopeConfig;
    /** 有效时长，单位小时，策略自动失效时间，0 表示永久有效 */
    private Integer validHours;
    /** 策略描述，长度不超过 512，说明策略目的与适用场景 */
    private String description;
    /** 是否启用，true 生效，false 暂停策略 */
    private Boolean enabled;
}