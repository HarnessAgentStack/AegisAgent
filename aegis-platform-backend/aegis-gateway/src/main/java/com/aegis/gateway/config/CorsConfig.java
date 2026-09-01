package com.aegis.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 跨域配置（P1-4 修复）。
 *
 * <p>网关层统一 CORS 配置，作为平台唯一跨域处理入口，下游服务无需重复配置。
 *
 * <h3>P1-4 安全加固</h3>
 * <p>原实现 {@code setAllowedOriginPatterns("*")} + {@code setAllowCredentials(true)} 是
 * Spring 合法组合但任意 Origin 均被放行（响应头回显 Origin + 携带 credentials）→ 任意网站
 * 可携带凭证跨域访问 API。现改为<b>配置驱动的精确 Origin 白名单</b>（环境变量 / Nacos 注入）：
 * <ul>
 *   <li>{@code aegis.gateway.cors.allowed-origins}：精确域名列表，逗号分隔（生产必配）</li>
 *   <li>本地开发默认 {@code http://localhost:*}（dev 占位，生产由 prod 配置覆盖）</li>
 *   <li>白名单为空且非 dev profile → <b>fail-closed</b>：拒绝全部跨域（仅同源可用）</li>
 * </ul>
 *
 * <p>不做网关→DB 读取（gateway 无 DB 依赖，CORS 每请求校验若查 DB 引入耦合与延迟）；
 * Origin 白名单通过配置注入，与 P0-1 凭据环境变量化模式一致。DB 白名单入库方案如后续需动态管理，
 * 由 admin 维护后经 Nacos 配置热推至 gateway（配置中心路径），不在 gateway 直连 DB。
 *
 * @author wang.zhen
 */
@Slf4j
@Configuration
public class CorsConfig implements ApplicationListener<ApplicationReadyEvent> {

    /**
     * 允许的 Origin 模式列表（精确域名或 {@code http://localhost:*} 等 pattern）。
     * <p>通过 {@code aegis.gateway.cors.allowed-origins} 注入，逗号分隔；默认本地开发占位。
     */
    @Value("${aegis.gateway.cors.allowed-origins:http://localhost:*}")
    private String allowedOriginsRaw;

    /**
     * 是否启用 fail-closed 模式：白名单为空时拒绝全部跨域。
     * <p>生产环境应保持 true（默认）。本地开发如需临时放行可显式置 false（不推荐）。
     */
    @Value("${aegis.gateway.cors.fail-closed:true}")
    private boolean failClosed;

    @Bean
    public CorsWebFilter corsWebFilter() {
        List<String> origins = parseOrigins(allowedOriginsRaw);

        CorsConfiguration config = new CorsConfiguration();
        // P1-4：精确白名单替代通配 *（与 allowCredentials=true 组合安全）
        if (!origins.isEmpty()) {
            config.setAllowedOriginPatterns(origins);
        } else if (failClosed) {
            // fail-closed：无白名单时不注册任何 allowed origin → 全部跨域请求被拒
            config.setAllowedOriginPatterns(Collections.emptyList());
            log.warn("[CORS] fail-closed 模式：未配置 aegis.gateway.cors.allowed-origins，拒绝全部跨域请求");
        }
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter((CorsConfigurationSource) source);
    }

    /**
     * 解析逗号分隔的 Origin 列表，去空白与空项。
     */
    private List<String> parseOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 启动就绪时打印 CORS 白名单状态，便于运维核对生产配置是否注入。
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<String> origins = parseOrigins(allowedOriginsRaw);
        if (origins.isEmpty() && failClosed) {
            log.error("[CORS] 启动告警：未配置 allowed-origins 且 fail-closed=true，平台将拒绝所有跨域请求。"
                    + "生产环境请配置 aegis.gateway.cors.allowed-origins=https://your-frontend.example.com");
        } else {
            log.info("[CORS] 白名单已加载: origins={}, failClosed={}", origins, failClosed);
        }
    }
}
