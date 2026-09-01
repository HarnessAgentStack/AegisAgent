package com.aegis.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import com.aegis.core.event.ConfigChangedEvent;

/**
 * 网关配置属性。
 *
 * <p>承载网关运行期的可调参数：动态路由规则、路径白名单等。
 * 通过 {@code @ConfigurationProperties} 绑定 Nacos 配置中心，支持运行期热更新。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code routes}：动态路由规则（服务名/路径前缀/是否启用 session 亲和）</li>
 *   <li>{@code whitelist}：免认证路径白名单（如登录、健康检查、SSE 探活）</li>
 * </ul>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>属性绑定 Nacos dataId = aegis-gateway.yaml，变更后通过 {@code ConfigChangedEvent} 触发热更新</li>
 *   <li>白名单变更无需重启，实时生效</li>
 * </ul>
 *
 * @author wang.zhen
 * @see com.aegis.gateway.filter.AuthFilter
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "aegis.gateway")
public class AegisGatewayProperties {

    /** 动态路由规则列表 */
    private List<RouteRule> routes;

    /** 免认证路径白名单（Ant 风格，如 /api/auth/**） */
    private List<String> whitelist;

    /** 动态路由规则。 */
    @Data
    public static class RouteRule {
        /** 目标服务名（Nacos 注册） */
        private String serviceName;
        /** 路径前缀（如 /api/runtime/**） */
        private String pathPrefix;
        /** 是否启用 session 哈希亲和（运行平面会话粘性） */
        private boolean sessionAffinity;
    }
}
