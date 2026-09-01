package com.aegis.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 网关配置类。
 */
@Configuration
public class GatewayConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public AegisGatewayProperties aegisGatewayProperties() {
        AegisGatewayProperties props = new AegisGatewayProperties();
        // 默认白名单
        props.setWhitelist(List.of(
            "/api/admin/auth/**",
            "/actuator/**"
        ));
        return props;
    }
}
