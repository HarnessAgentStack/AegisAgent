package com.aegis.runtime.integration.auth;

import com.aegis.core.domain.agent.AgentApi;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Bearer Token 鉴权服务：支持静态比对与 JWT 本地校验。
 *
 * <p>支持的模式：
 * <ul>
 *   <li>STATIC：静态 Token 比对，适合简单内部场景</li>
 *   <li>PASSTHROUGH + JWT：透传验证，本地校验 JWT 签名与过期时间</li>
 * </ul>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
public class BearerAuthService {

    /**
     * 验证 Bearer Token。
     *
     * @param token 客户端传入的 token（已去掉 "Bearer " 前缀）
     * @param api   API 配置
     * @return 验证结果
     */
    public AuthResult verify(String token, AgentApi api) {
        if (token == null || token.isBlank()) {
            return AuthResult.fail("Bearer Token 为空");
        }

        String mode = api.getBearerTokenMode();
        if (mode == null || mode.isBlank()) {
            mode = "PASSTHROUGH";
        }

        return switch (mode) {
            case "STATIC" -> verifyStatic(token, api);
            case "PASSTHROUGH" -> verifyJwt(token, api);
            default -> AuthResult.fail("未知的 Bearer Token 模式: " + mode);
        };
    }

    /**
     * 静态 Token 比对。
     */
    private AuthResult verifyStatic(String token, AgentApi api) {
        String expected = api.getBearerTokenValue();
        if (expected == null || expected.isBlank()) {
            return AuthResult.fail("API 配置为静态模式但未设置 Token 值");
        }

        if (!expected.equals(token)) {
            log.warn("Bearer static token mismatch: apiId={}", api.getId());
            return AuthResult.fail("Bearer Token 无效");
        }

        log.info("Bearer static token verified: apiId={}", api.getId());
        return AuthResult.success(new HashMap<>());
    }

    /**
     * JWT 本地校验（签名 + 过期）。
     */
    private AuthResult verifyJwt(String token, AgentApi api) {
        String secret = api.getBearerJwtSecret();
        if (secret == null || secret.isBlank()) {
            return AuthResult.fail("JWT 校验未配置签名密钥");
        }

        String algorithm = api.getBearerJwtAlgorithm();
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = "HS256";
        }

        try {
            PublicKeyOrSecret key = resolveKey(secret, algorithm);
            if (key.isSymmetric()) {
                Claims claims = Jwts.parser()
                        .verifyWith(key.getSecretKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                return buildJwtSuccess(claims);
            } else {
                Claims claims = Jwts.parser()
                        .verifyWith(key.getPublicKey())
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();

                return buildJwtSuccess(claims);
            }
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: apiId={}", api.getId());
            return AuthResult.fail("Bearer Token 已过期");
        } catch (JwtException e) {
            log.warn("JWT validation failed: apiId={}, error={}", api.getId(), e.getMessage());
            return AuthResult.fail("Bearer Token 无效: " + e.getMessage());
        } catch (Exception e) {
            log.error("JWT validation error: apiId={}", api.getId(), e);
            return AuthResult.fail("Bearer Token 校验失败");
        }
    }

    /**
     * 根据算法解析密钥。
     */
    private PublicKeyOrSecret resolveKey(String secret, String algorithm) throws Exception {
        String upperAlgo = algorithm.toUpperCase();

        if (upperAlgo.startsWith("HS")) {
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (upperAlgo.equals("HS256") && keyBytes.length < 32) {
                keyBytes = java.util.Arrays.copyOf(keyBytes, 32);
            } else if (upperAlgo.equals("HS384") && keyBytes.length < 48) {
                keyBytes = java.util.Arrays.copyOf(keyBytes, 48);
            } else if (upperAlgo.equals("HS512") && keyBytes.length < 64) {
                keyBytes = java.util.Arrays.copyOf(keyBytes, 64);
            }
            SecretKey key = Keys.hmacShaKeyFor(keyBytes);
            return PublicKeyOrSecret.symmetric(key);
        }

        if (upperAlgo.startsWith("RS") || upperAlgo.startsWith("ES") || upperAlgo.startsWith("PS")) {
            byte[] decodedBytes = Base64.getDecoder().decode(secret.trim());
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decodedBytes);
            String keyAlgo = upperAlgo.startsWith("RS") ? "RSA" :
                    upperAlgo.startsWith("ES") ? "EC" : "RSA";
            KeyFactory keyFactory = KeyFactory.getInstance(keyAlgo);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);
            return PublicKeyOrSecret.asymmetric(publicKey);
        }

        throw new IllegalArgumentException("不支持的 JWT 算法: " + algorithm);
    }

    /**
     * 构建 JWT 验证成功结果。
     */
    private AuthResult buildJwtSuccess(Claims claims) {
        Map<String, Object> claimsMap = new HashMap<>();
        claimsMap.put("sub", claims.getSubject());
        claimsMap.put("iat", claims.getIssuedAt());
        claimsMap.put("exp", claims.getExpiration());

        Instant exp = claims.getExpiration() != null ? claims.getExpiration().toInstant() : null;
        if (exp != null && exp.isBefore(Instant.now())) {
            return AuthResult.fail("Bearer Token 已过期");
        }

        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Date d) {
                claimsMap.put(entry.getKey(), d.toInstant().toString());
            } else if (val != null) {
                claimsMap.put(entry.getKey(), val.toString());
            }
        }

        return AuthResult.success(claimsMap);
    }

    /**
     * Bearer Token 验证结果。
     */
    public static class AuthResult {
        private final boolean success;
        private final String errorMessage;
        private final Map<String, Object> claims;

        private AuthResult(boolean success, String errorMessage, Map<String, Object> claims) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.claims = claims;
        }

        public static AuthResult success(Map<String, Object> claims) {
            return new AuthResult(true, null, claims);
        }

        public static AuthResult fail(String errorMessage) {
            return new AuthResult(false, errorMessage, Map.of());
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public Map<String, Object> getClaims() {
            return claims;
        }
    }

    /**
     * 密钥容器（对称/非对称）。
     */
    private static class PublicKeyOrSecret {
        private final SecretKey secretKey;
        private final PublicKey publicKey;
        private final boolean symmetric;

        private PublicKeyOrSecret(SecretKey secretKey, PublicKey publicKey, boolean symmetric) {
            this.secretKey = secretKey;
            this.publicKey = publicKey;
            this.symmetric = symmetric;
        }

        public static PublicKeyOrSecret symmetric(SecretKey key) {
            return new PublicKeyOrSecret(key, null, true);
        }

        public static PublicKeyOrSecret asymmetric(PublicKey key) {
            return new PublicKeyOrSecret(null, key, false);
        }

        public boolean isSymmetric() {
            return symmetric;
        }

        public SecretKey getSecretKey() {
            return secretKey;
        }

        public PublicKey getPublicKey() {
            return publicKey;
        }
    }
}
