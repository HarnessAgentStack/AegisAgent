package com.aegis.core.jwt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JWT 载荷，包含用户身份与租户信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtPayload {
    private Long userId;
    private Long tenantId;
    private String username;
    private List<String> roles;
    private List<String> permissions;
}
