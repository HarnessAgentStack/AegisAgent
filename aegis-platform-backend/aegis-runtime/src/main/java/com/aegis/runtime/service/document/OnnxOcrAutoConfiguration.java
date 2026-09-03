package com.aegis.runtime.service.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * ONNX OCR 自动配置。
 *
 * <p>启用条件：{@code aegis.ocr.onnx.enabled=true}（默认 true）。
 * {@link OnnxOcrClient} 本身是 {@code @Component}，此处仅负责绑定配置属性。</p>
 *
 * @author wang.zhen
 */
@Configuration
@EnableConfigurationProperties(OnnxOcrProperties.class)
@ConditionalOnProperty(name = "aegis.ocr.onnx.enabled", havingValue = "true", matchIfMissing = true)
public class OnnxOcrAutoConfiguration {
    // OnnxOcrClient 已 @Component，无需显式 @Bean 定义
    // 本类仅注册 OnnxOcrProperties 的配置绑定
}
