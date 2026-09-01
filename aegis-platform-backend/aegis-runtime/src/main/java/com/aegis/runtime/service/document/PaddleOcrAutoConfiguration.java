package com.aegis.runtime.service.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * PaddleOCR 自动配置。
 *
 * <p>启用条件：{@code aegis.ocr.paddle.enabled=true}（默认 true，即只要配置类被加载就启用）。
 * 当本地开发环境未启动 PaddleOCR 容器时，将此设为 false 即可完全跳过 OCR 客户端初始化，
 * 直接走 vision LLM 降级路径。</p>
 *
 * @author wang.zhen
 */
@Configuration
@EnableConfigurationProperties(PaddleOcrProperties.class)
public class PaddleOcrAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "aegis.ocr.paddle.enabled", havingValue = "true", matchIfMissing = true)
    public PaddleOcrClient paddleOcrClient(RestTemplate restTemplate, PaddleOcrProperties properties) {
        return new PaddleOcrClient(restTemplate, properties);
    }
}
