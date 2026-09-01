package com.aegis.core.jwt;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/**
 * JWT 配置属性。
 *
 * <p>统一承载 JWT 签名密钥与 Token 有效期，替代各模块散落的 {@code @Value} 注入。
 * 密钥通过 Nacos/环境变量外部化，启动时强制校验非空且长度 ≥ 32 字节（HS256 安全下限），
 * 杜绝硬编码默认密钥进入生产环境。
 *
 * <h3>配置项</h3>
 * <ul>
 *   <li>{@code aegis.jwt.secret}：HMAC-SHA256 签名密钥，UTF-8 字节长度必须 ≥ 32</li>
 *   <li>{@code aegis.jwt.access-token-expire}：Access Token 有效期（秒），默认 7200</li>
 *   <li>{@code aegis.jwt.refresh-token-expire}：Refresh Token 有效期（秒），默认 604800</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Data
@ConfigurationProperties(prefix = "aegis.jwt")
public class JwtProperties {

    /** HMAC-SHA256 签名密钥（UTF-8 字节长度 ≥ 32）；禁止使用默认值，必须外部化注入 */
    private String secret;

    /** Access Token 有效期（秒） */
    private long accessTokenExpire = 7200L;

    /** Refresh Token 有效期（秒） */
    private long refreshTokenExpire = 604800L;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT 密钥未配置: 请通过配置项 aegis.jwt.secret (Nacos/环境变量) 注入，禁止使用默认值");
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < 32) {
            throw new IllegalStateException(
                    "JWT 密钥长度不足: 当前 " + length + " 字节，HS256 要求 ≥ 32 字节；请配置 aegis.jwt.secret");
        }
    }
}
