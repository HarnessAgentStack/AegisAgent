package com.aegis.runtime.integration.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

/**
 * URL 安全校验器（SSRF 防护公共组件，W-5/W-6 消除重复实现）。
 *
 * <p>集中承载 web_search/image_search/http_request/web_fetch 共用的 SSRF 校验逻辑，
 * 避免 AegisBuiltinTools 与 AegisHttpTool 各写一份导致漂移。
 *
 * <h3>内网白名单（W-5）</h3>
 * <p>仅认 AegisToolProperties.getAllowedInternalHosts() 应用配置的精确 host（不支持通配），
 * 命中白名单时放行并记审计日志。白名单只对应用配置生效，绝不接受 LLM 传入的 URL 参数。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlSafetyChecker {

    private final AegisToolProperties toolProperties;

    /**
     * 校验 URL 安全性。
     *
     * @param url 待校验 URL
     * @return 违规原因，null 表示安全
     */
    public String check(String url) {
        if (url == null || url.isEmpty()) {
            return "empty url";
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (java.net.URISyntaxException e) {
            return "invalid url format";
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return "missing url scheme";
        }
        String lowerScheme = scheme.toLowerCase();
        if (!"http".equals(lowerScheme) && !"https".equals(lowerScheme)) {
            return "protocol '" + lowerScheme + "' is not allowed, only http/https";
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "missing or empty host";
        }
        Set<String> allowed = toolProperties.getAllowedInternalHosts();
        if (allowed != null && !allowed.isEmpty() && allowed.contains(host)) {
            log.info("UrlSafetyChecker 内网 host 命中白名单放行（仅应用配置生效）: host={}", host);
            return null;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isForbiddenAddress(addr)) {
                    return "access to internal/loopback address is forbidden: "
                            + addr.getHostAddress() + " (resolved from " + host + ")"
                            + (allowed != null && !allowed.isEmpty()
                                ? "；如需访问内网服务，请在 aegis.tools.allowed-internal-hosts 配置精确 host" : "");
                }
            }
        } catch (java.net.UnknownHostException e) {
            return "unable to resolve host: " + host;
        }
        return null;
    }

    private boolean isForbiddenAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isMulticastAddress();
    }
}
