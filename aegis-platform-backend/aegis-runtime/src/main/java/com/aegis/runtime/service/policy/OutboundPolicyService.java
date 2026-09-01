package com.aegis.runtime.service.policy;

import com.aegis.core.domain.security.OutboundPolicy;
import com.aegis.core.enums.security.OutboundPolicyType;
import com.aegis.core.enums.security.OutboundScope;
import com.aegis.dal.mapper.security.OutboundPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站策略检查器。
 *
 * <p>校验智能体发起的外部网络请求是否符合出站策略：
 * <ul>
 *   <li>WHITELIST_DOMAIN：仅允许白名单域名访问，支持通配符 *.example.com</li>
 *   <li>BLACKLIST_IP：禁止访问黑名单 IP</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboundPolicyService {

    private final OutboundPolicyMapper outboundPolicyMapper;

    /**
     * 检查出站请求是否允许。
     *
     * @param tenantId 租户ID
     * @param agentId  智能体ID
     * @param url      请求 URL
     * @return true 表示允许，false 表示拒绝
     */
    public boolean check(Long tenantId, Long agentId, String url) {
        if (url == null || url.isEmpty()) {
            // P0 SEC-03 修复：空 URL 拒绝（原 return true 放行）
            return false;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            log.warn("OutboundPolicy invalid URL: url={}", url);
            return false;
        }
        // P0 SEC-03 修复：仅允许 http/https scheme
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            log.warn("OutboundPolicy blocked (non-http scheme): scheme={}, url={}", scheme, url);
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            // P0 SEC-03 修复：host 为空拒绝（原 return true 放行，file:///etc/passwd 可绕过）
            return false;
        }

        // P0 SEC-03 修复：DNS 解析后校验 IP 不在私有/回环段（与 AegisHttpTool 一致）
        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    log.warn("OutboundPolicy blocked (internal address): host={}, ip={}",
                            host, addr.getHostAddress());
                    return false;
                }
            }
        } catch (java.net.UnknownHostException e) {
            log.warn("OutboundPolicy blocked (unresolved host): host={}", host);
            return false;
        }

        // P1 SEC-06 修复：原查询仅按 enabled=true 过滤，无 tenantId 隔离，
        // 导致租户间出站策略互相干扰。现增加 tenantId 过滤条件。
        List<OutboundPolicy> policies = outboundPolicyMapper.selectList(
                new LambdaQueryWrapper<OutboundPolicy>()
                        .eq(tenantId != null, OutboundPolicy::getTenantId, tenantId)
                        .eq(OutboundPolicy::getEnabled, true));
        if (policies == null || policies.isEmpty()) {
            return true;
        }

        for (OutboundPolicy policy : policies) {
            if (!isApplicable(policy, agentId)) {
                continue;
            }
            if (isExpired(policy)) {
                continue;
            }

            if (policy.getPolicyType() == OutboundPolicyType.WHITELIST_DOMAIN) {
                if (matchesDomain(host, policy.getDomain())) {
                    return true;
                }
            } else if (policy.getPolicyType() == OutboundPolicyType.BLACKLIST_IP) {
                if (matchesIp(host, policy.getIpCidr())) {
                    log.warn("OutboundPolicy blocked (blacklist IP): host={}, ipCidr={}",
                            host, policy.getIpCidr());
                    return false;
                }
            }
        }

        // 如果存在 WHITELIST_DOMAIN 类型策略但未匹配到，则拒绝
        boolean hasWhitelist = policies.stream()
                .filter(p -> isApplicable(p, agentId) && !isExpired(p))
                .anyMatch(p -> p.getPolicyType() == OutboundPolicyType.WHITELIST_DOMAIN);
        if (hasWhitelist) {
            log.warn("OutboundPolicy blocked (not in whitelist): host={}", host);
            return false;
        }

        return true;
    }

    /**
     * 检查策略是否适用于当前智能体。
     */
    private boolean isApplicable(OutboundPolicy policy, Long agentId) {
        if (policy.getApplicableScope() == null
                || policy.getApplicableScope() == OutboundScope.ALL) {
            return true;
        }
        if (policy.getScopeConfig() == null || policy.getScopeConfig().isEmpty()) {
            return true;
        }
        if (policy.getApplicableScope() == OutboundScope.AGENT && agentId != null) {
            return policy.getScopeConfig().contains(String.valueOf(agentId));
        }
        // DEPT 范围的检查需要部门信息，此处默认放行
        return true;
    }

    /**
     * 检查策略是否已过期。
     */
    private boolean isExpired(OutboundPolicy policy) {
        if (policy.getValidHours() == null || policy.getValidHours() <= 0) {
            return false;
        }
        LocalDateTime createTime = policy.getCreateTime();
        if (createTime == null) {
            return false;
        }
        LocalDateTime expireTime = createTime.plusHours(policy.getValidHours());
        return LocalDateTime.now().isAfter(expireTime);
    }

    /**
     * 域名匹配（支持通配符 *.example.com）。
     */
    private boolean matchesDomain(String host, String domain) {
        if (domain == null || domain.isEmpty()) {
            return false;
        }
        if (domain.startsWith("*.")) {
            String suffix = domain.substring(2);
            return host.equals(suffix) || host.endsWith("." + suffix);
        }
        return host.equals(domain);
    }

    /**
     * IP 匹配（P0 SEC-03 修复：DNS 解析 + InetAddress 精确比较）。
     *
     * <p>原实现用字符串比较域名与 IP CIDR，永远不会匹配。修复后先解析 host 为 IP，
     * 再与 ipCidr 做精确或 CIDR 前缀匹配。
     */
    private boolean matchesIp(String host, String ipCidr) {
        if (ipCidr == null || ipCidr.isEmpty()) {
            return false;
        }
        // 解析 ipCidr 的网络地址
        String network = ipCidr.contains("/") ? ipCidr.split("/")[0] : ipCidr;
        try {
            java.net.InetAddress networkAddr = java.net.InetAddress.getByName(network);
            // 解析 host 为所有 IP
            java.net.InetAddress[] hostAddrs = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress hostAddr : hostAddrs) {
                // 无 CIDR 前缀时做精确 IP 比较
                if (!ipCidr.contains("/")) {
                    if (hostAddr.equals(networkAddr)) {
                        return true;
                    }
                    continue;
                }
                // 含 CIDR 时做前缀匹配（按字节比较前 prefixBits 位）
                int prefixBits = Integer.parseInt(ipCidr.split("/")[1]);
                if (isInCidr(hostAddr, networkAddr, prefixBits)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("matchesIp 解析失败: host={}, ipCidr={}", host, ipCidr);
        }
        return false;
    }

    /**
     * 判断 hostAddr 是否在 networkAddr/prefixBits 指定的 CIDR 范围内。
     */
    private boolean isInCidr(java.net.InetAddress hostAddr, java.net.InetAddress networkAddr, int prefixBits) {
        byte[] hostBytes = hostAddr.getAddress();
        byte[] netBytes = networkAddr.getAddress();
        if (hostBytes.length != netBytes.length) {
            return false;
        }
        int fullBytes = prefixBits / 8;
        int remainderBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (hostBytes[i] != netBytes[i]) {
                return false;
            }
        }
        if (remainderBits > 0 && fullBytes < netBytes.length) {
            int mask = 0xFF << (8 - remainderBits);
            if ((hostBytes[fullBytes] & mask) != (netBytes[fullBytes] & mask)) {
                return false;
            }
        }
        return true;
    }
}
