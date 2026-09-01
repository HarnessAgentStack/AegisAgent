package com.aegis.runtime.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime 专有配置（原 CoreBeanConfig 中 runtime 特有的 Bean）。
 */
@Configuration
public class RuntimeBeanConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
