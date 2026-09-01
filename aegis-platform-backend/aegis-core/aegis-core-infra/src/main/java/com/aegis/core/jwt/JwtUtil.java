package com.aegis.core.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * JWT 工具类：签发、解析、验证。
 */
public final class JwtUtil {

    private JwtUtil() {}

    /** 签发 JWT */
    public static String sign(JwtPayload payload, String secret, long expireSeconds) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(payload.getUserId()))
                .claim("tenantId", payload.getTenantId())
                .claim("username", payload.getUsername())
                .claim("roles", payload.getRoles())
                .claim("permissions", payload.getPermissions())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(key)
                .compact();
    }

    /** 解析 JWT，返回 Claims；签名错误或过期返回 null */
    public static Claims parse(String token, String secret) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            return null; // 过期
        } catch (JwtException e) {
            return null; // 签名错误/格式错误
        }
    }

    /** 从 Claims 构建 JwtPayload */
    @SuppressWarnings("unchecked")
    public static JwtPayload toPayload(Claims claims) {
        return JwtPayload.builder()
                .userId(Long.parseLong(claims.getSubject()))
                .tenantId(claims.get("tenantId", Long.class))
                .username(claims.get("username", String.class))
                .roles(claims.get("roles", List.class))
                .permissions(claims.get("permissions", List.class))
                .build();
    }
}
