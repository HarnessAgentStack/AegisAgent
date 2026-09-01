package com.aegis.core.autoconfigure;

import com.aegis.core.dto.observe.ObserveProperties;
import com.aegis.core.jwt.JwtProperties;
import com.aegis.core.web.filter.CoreTenantContextWebFilter;
import com.aegis.core.web.filter.TraceIdWebFilter;
import com.aegis.core.web.handler.CoreGlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 核心自动配置：共享基础设施统一装配。
 *
 * <p>通过 Spring Boot 自动配置机制自动注册，各服务无需手动 @Import。
 *
 * <p>MyBatis-Plus 相关 beans 已拆分到 {@link CoreMybatisAutoConfiguration}，
 * 仅当 classpath 存在 mybatis-plus 时生效。
 */
@AutoConfiguration
@EnableConfigurationProperties({ObserveProperties.class, JwtProperties.class})
public class CoreAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CoreGlobalExceptionHandler coreGlobalExceptionHandler() {
        return new CoreGlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public CoreTenantContextWebFilter coreTenantContextWebFilter() {
        return new CoreTenantContextWebFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceIdWebFilter traceIdWebFilter() {
        return new TraceIdWebFilter();
    }
}
