package com.aegis.core.web.config;

import com.aegis.core.web.resolver.ContextArgumentResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.core.JsonGenerator;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import com.aegis.core.web.annotation.DeptId;
import com.aegis.core.web.annotation.TenantId;
import com.aegis.core.web.annotation.UserId;

/**
 * Core WebFlux config: register ContextArgumentResolver + Jackson Long→String serializer.
 *
 * <p>Supports @TenantId / @UserId / @DeptId annotations to eliminate
 * @RequestHeader + TenantContextHolder.bind boilerplate.
 *
 * <p>Jackson 3 (Spring Boot 4) global Long→String serialization: 19-digit snowflake IDs
 * (range 2^63) exceed JS Number.MAX_SAFE_INTEGER (2^53), serialize as String to prevent
 * truncation on the frontend.
 *
 * @author wang.zhen
 */
@Configuration
public class CoreWebFluxConfig implements WebFluxConfigurer {

    @Bean
    public ContextArgumentResolver contextArgumentResolver() {
        return new ContextArgumentResolver();
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(contextArgumentResolver());
    }

    /**
     * Long → String serializer for Jackson 3.x.
     *
     * <p>Handles both {@code Long} wrapper and {@code long} primitive.
     * Snowflake IDs (19 digits) exceed JS Number precision, must serialize as string.
     */
    public static class LongAsStringSerializer extends StdSerializer<Long> {
        public LongAsStringSerializer() {
            super(Long.class);
        }
        @Override
        public void serialize(Long value, JsonGenerator gen, SerializationContext context) throws JacksonException {
            if (value == null) {
                gen.writeNull();
            } else {
                gen.writeString(value.toString());
            }
        }
    }

    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("LongAsStringModule");
            module.addSerializer(Long.class, new LongAsStringSerializer());
            module.addSerializer(Long.TYPE, new LongAsStringSerializer());
            builder.addModule(module);
        };
    }
}