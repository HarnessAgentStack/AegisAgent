package com.aegis.admin.web.auth;

import com.aegis.admin.infrastructure.audit.Auditable;
import com.aegis.admin.service.org.AuthService;
import com.aegis.core.common.web.Result;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 认证控制器：登录、刷新Token、当前用户。
 *
 * <p>支持数据库用户验证（org_user 表 + BCrypt 密码校验），
 * 不内置任何硬编码降级账号；开发环境账号通过 DB 种子数据提供，生产环境禁止种入弱口令账号。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录接口。
     *
     * @param req 登录参数 {username, password, tenantCode}
     * @return JWT token + 用户信息
     */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        return Result.success(authService.login(req.getTenantCode(), req.getUsername(), req.getPassword()));
    }

    /**
     * 刷新Token。
     */
    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@Valid @RequestBody RefreshRequest req) {
        return Result.success(authService.refresh(req.getRefreshToken()));
    }

    /**
     * 获取当前用户信息。
     */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(@RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId,
                                           @RequestHeader(value = "X-User-Id", required = false) Long userId,
                                           @RequestHeader(value = "X-Username", required = false) String username,
                                           @RequestHeader(value = "X-Roles", required = false) String roles) {
        return Result.success(authService.me(tenantId, userId, username, roles));
    }

    /**
     * 退出登录。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Token 失效通过客户端清除 localStorage 实现
        // 服务端可通过 Redis 黑名单增强（后续迭代）
        log.info("User logged out");
        return Result.success(null);
    }

    /**
     * 更新当前用户个人资料（nickname/avatar/email/phone，不可改 username/tenantId/roles）。
     */
    @PutMapping("/profile")
    @Auditable(operation = "UPDATE_PROFILE", resourceType = "USER")
    public Result<Void> updateProfile(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody UpdateProfileRequest req) {
        authService.updateProfile(userId, req.getNickname(), req.getAvatar(), req.getEmail(), req.getPhone());
        return Result.success(null);
    }

    /**
     * 修改密码（校验旧密码，新密码 BCrypt 加密）。
     */
    @PostMapping("/change-password")
    @Auditable(operation = "CHANGE_PASSWORD", resourceType = "USER")
    public Result<Void> changePassword(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(userId, req.getOldPassword(), req.getNewPassword());
        return Result.success(null);
    }

    // ============ 请求DTO ============

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
        private String tenantCode;
    }

    @Data
    public static class RefreshRequest {
        private String refreshToken;
    }

    @Data
    public static class UpdateProfileRequest {
        private String nickname;
        private String avatar;
        private String email;
        private String phone;
    }

    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }
}
