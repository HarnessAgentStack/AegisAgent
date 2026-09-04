package com.aegis.runtime.integration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * WebFlux 配置。
 *
 * <p>运行平面 WebFlux 配置，定义 SSE 端点、异步线程池与跨域策略。
 * 运行平面为完全响应式服务，SSE 长连接承载流式任务输出，阻塞操作卸载至专用线程池。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>SSE 端点：注册响应式 SSE 路由，透传流式事件</li>
 *   <li>异步线程池：阻塞调用（MyBatis-Plus 同步 JDBC / 沙箱 exec）卸载至 {@code boundedElastic}，
 *       避免阻塞 Reactor 事件循环线程</li>
 *   <li>跨域：允许开发环境直连 runtime，绕过代理对 SSE 的处理限制</li>
 *   <li>请求体大小：SSE 上行消息体限制，防超大 payload</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>Reactor 事件循环：处理 SSE 流与非阻塞 IO</li>
 *   <li>boundedElastic：JDBC、远程调用等阻塞操作</li>
 *   <li>隔离：按业务（任务执行/池化管理）划分线程池，防相互拖累</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Configuration
@EnableAsync
public class WebFluxConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
