package com.aegis.admin.service.org;

import com.aegis.admin.config.AuthProperties;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.org.Role;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;
import com.aegis.core.common.tenant.TenantContextHolder;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.jwt.JwtPayload;
import com.aegis.core.jwt.JwtProperties;
import com.aegis.core.jwt.JwtUtil;
import com.aegis.dal.mapper.org.RoleMapper;
import com.aegis.dal.mapper.org.UserMapper;
import com.aegis.dal.mapper.org.UserRoleMapper;
import com.aegis.dal.mapper.org.RolePermissionMapper;
import com.aegis.dal.mapper.tenant.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 认证领域服务。
 *
 * <p>提供用户登录验证、Token 刷新与当前用户信息查询能力。
 * 支持数据库用户验证（org_user 表 + BCrypt 密码校验），
 * 不内置任何硬编码降级账号；开发环境账号通过 DB 种子数据提供，生产环境禁止种入弱口令账号。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final TenantMapper tenantMapper;
    private final JwtProperties jwtProperties;
    private final AuthProperties authProperties;

    /** BCrypt cost 值 */
    @Value("${aegis.bcrypt.cost:10}")
    private int bcryptCost;

    /** BCrypt 密码编码器 */
    private BCryptPasswordEncoder passwordEncoder;

    @PostConstruct
    public void init() {
        this.passwordEncoder = new BCryptPasswordEncoder(bcryptCost);
    }

    /**
     * 登录验证（两级定位：tenantCode → tenantId → username）。
     *
     * <p>先按 tenantCode 查租户，再按 tenantId + username 精确定位用户，
     * 避免跨租户同名用户歧义。tenantCode 必填。
     *
     * @param tenantCode 租户编码（必填）
     * @param username   用户名
     * @param password   密码
     * @return JWT token + 用户信息
     */
    public Map<String, Object> login(String tenantCode, String username, String password) {
        log.info("[Login] 登录请求开始, tenantCode={}, username={}", tenantCode, username);
        if (tenantCode == null || tenantCode.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户标识不能为空");
        }
        if (username == null || password == null) {
            log.warn("[Login] 登录失败: 用户名或密码为空, username={}", username);
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名和密码不能为空");
        }

        // 1. 按 tenantCode 查租户
        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getTenantCode, tenantCode));
        if (tenant == null) {
            log.warn("[Login] 登录失败: 租户不存在, tenantCode={}", tenantCode);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "租户不存在或已停用");
        }
        if (tenant.getStatus() != null
                && com.aegis.core.enums.tenant.TenantStatus.FROZEN == tenant.getStatus()) {
            log.warn("[Login] 登录失败: 租户已冻结, tenantCode={}", tenantCode);
            throw new BusinessException(ResultCode.FORBIDDEN, "租户已冻结，请联系平台管理员");
        }

        // 登录是"先定位租户、再绑定上下文、再执行业务"的流程：
        // ten_tenant 表无 tenant_id 列（在 ignoreTables 里），租户插件不拦截。
        // 但后续 org_user / org_user_role / org_role_permission 均有 tenant_id 列，
        // CoreTenantLineHandler fail-closed 要求必须有租户上下文，所以这里临时绑定。
        TenantContextHolder.bind(tenant.getId());
        try {
            return doLoginInternal(tenant.getId(), username, password, tenantCode);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * 登录核心逻辑（已绑定租户上下文）。
     */
    private Map<String, Object> doLoginInternal(Long tenantId, String username, String password, String tenantCode) {
        // 2. 按 tenantId + username 精确定位用户
        log.info("[Login] 查询数据库用户, tenantId={}, username={}", tenantId, username);
        User user = userMapper.selectByTenantAndUsername(tenantId, username);

        if (user == null) {
            log.warn("[Login] 登录失败: 用户不存在, tenantId={}, username={}", tenantId, username);
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        log.info("[Login] 找到用户, userId={}, username={}, tenantId={}, status={}",
                user.getId(), username, user.getTenantId(), user.getStatus());

        // 3. 检查用户状态
        if (user.getStatus() == CommonStatus.DISABLED) {
            log.warn("[Login] 登录失败: 用户已禁用, userId={}, username={}", user.getId(), username);
            throw new BusinessException(ResultCode.FORBIDDEN, "用户已被禁用，请联系管理员");
        }

        // 4. BCrypt 密码校验
        boolean pwdMatch = passwordEncoder.matches(password, user.getPassword());
        if (!pwdMatch) {
            log.warn("[Login] 登录失败: 密码不匹配, userId={}, username={}, passwordLen={}",
                    user.getId(), username, password.length());
            throw new BusinessException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        log.info("[Login] 密码校验通过, userId={}, username={}", user.getId(), username);

        // 5. 查询用户角色（忽略租户过滤，登录为平台级操作）
        List<Long> roleIds = userRoleMapper.selectRoleIdsByUserIdIgnoreTenant(user.getId());

        List<String> roleCodes = List.of("EMPLOYEE");
        if (!roleIds.isEmpty()) {
            roleCodes = roleMapper.selectByIdsIgnoreTenant(roleIds).stream()
                    .map(Role::getRoleCode)
                    .filter(code -> code != null)
                    .collect(Collectors.toList());
            if (roleCodes.isEmpty()) {
                roleCodes = List.of("EMPLOYEE");
            }
        }

        // 6. 构建 JWT 载荷：DB 驱动权限聚合，DB 无数据回退配置兜底
        List<String> permissions = computePermissionsFromDb(roleIds);
        if (permissions == null || permissions.isEmpty()) {
            permissions = computePermissionsFromConfig(roleCodes);
        }

        JwtPayload payload = JwtPayload.builder()
                .userId(user.getId())
                .tenantId(user.getTenantId())
                .username(user.getUsername())
                .roles(roleCodes)
                .permissions(permissions)
                .build();

        // 7. 更新最后登录时间
        user.setLastLoginTime(java.time.LocalDateTime.now());
        userMapper.updateById(user);

        log.info("用户登录成功: username={}, userId={}, tenantId={}", username, user.getId(), user.getTenantId());
        return buildLoginResult(payload);
    }

    /**
     * 刷新 Token。
     *
     * @param refreshToken 刷新令牌
     * @return 新的 Access Token
     */
    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "refreshToken不能为空");
        }

        var claims = JwtUtil.parse(refreshToken, jwtProperties.getSecret());
        if (claims == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "刷新令牌无效或已过期");
        }

        JwtPayload payload = JwtUtil.toPayload(claims);
        String newAccessToken = JwtUtil.sign(payload, jwtProperties.getSecret(), jwtProperties.getAccessTokenExpire());

        Map<String, Object> result = new HashMap<>();
        result.put("token", newAccessToken);
        result.put("expiresIn", jwtProperties.getAccessTokenExpire());
        return result;
    }

    /**
     * 获取当前用户信息（查 DB 返回真实 status，禁用用户即时反映）。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param username 用户名
     * @param roles    角色列表（逗号分隔）
     * @return 用户信息
     */
    public Map<String, Object> me(Long tenantId, Long userId, String username, String roles) {
        if (tenantId != null) {
            TenantContextHolder.bind(tenantId);
        }
        try {
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("id", userId);
        userInfo.put("username", username);

        // 查 DB 取真实状态与展示字段，禁用用户即时反映
        String displayStatus = "DISABLED";
        String nickname = username;
        String avatar = null;
        String email = null;
        String phone = null;
        if (userId != null) {
            User dbUser = userMapper.selectById(userId);
            if (dbUser != null) {
                displayStatus = dbUser.getStatus() != null ? dbUser.getStatus().name() : "NORMAL";
                nickname = dbUser.getRealName() != null ? dbUser.getRealName() : dbUser.getUsername();
                avatar = dbUser.getAvatar();
                email = dbUser.getEmail();
                phone = dbUser.getPhone();
            }
        }
        userInfo.put("nickname", nickname);
        userInfo.put("avatar", avatar);
        userInfo.put("email", email);
        userInfo.put("phone", phone);
        userInfo.put("tenantId", tenantId);
        List<String> roleList = roles != null && !roles.isEmpty()
                ? List.of(roles.split(","))
                : List.of();
        userInfo.put("roles", roleList);
        userInfo.put("permissions", computePermissionsFromConfig(roleList));
        userInfo.put("status", displayStatus);
        return userInfo;
        } finally {
            if (tenantId != null) {
                TenantContextHolder.clear();
            }
        }
    }

    // ============ 个人设置 ============

    /**
     * 更新当前用户个人资料（nickname/realName/avatar/email/phone，不可改 username/tenantId/roles）。
     *
     * <p>按 userId 取当前用户（从 X-User-Id 头注入），防止越权改他人。
     * email/phone 变更时复用租户内唯一校验。
     *
     * @param userId  当前用户ID（X-User-Id 头）
     * @param nickname 昵称（对应 realName）
     * @param avatar   头像URL
     * @param email    邮箱
     * @param phone    手机号
     */
    public void updateProfile(Long userId, Long tenantId, String nickname, String avatar, String email, String phone) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }
        if (tenantId != null) {
            TenantContextHolder.bind(tenantId);
        }
        try {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (user.getStatus() == CommonStatus.DISABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "用户已被禁用");
        }

        // email 租户内唯一校验（若变更）
        if (email != null && !email.isEmpty() && !email.equals(user.getEmail())) {
            Long exists = userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, user.getTenantId())
                    .eq(User::getEmail, email)
                    .ne(User::getId, userId));
            if (exists != null && exists > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "邮箱已被占用");
            }
            user.setEmail(email);
        }
        // phone 租户内唯一校验（若变更）
        if (phone != null && !phone.isEmpty() && !phone.equals(user.getPhone())) {
            Long exists = userMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, user.getTenantId())
                    .eq(User::getPhone, phone)
                    .ne(User::getId, userId));
            if (exists != null && exists > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "手机号已被占用");
            }
            user.setPhone(phone);
        }

        if (nickname != null && !nickname.isEmpty()) {
            user.setRealName(nickname);
        }
        if (avatar != null) {
            user.setAvatar(avatar);
        }
        userMapper.updateById(user);
        log.info("用户资料更新: userId={}, nickname={}", userId, nickname);
        } finally {
            if (tenantId != null) {
                TenantContextHolder.clear();
            }
        }
    }

    /**
     * 修改密码（校验旧密码，新密码 BCrypt 加密）。
     *
     * @param userId   当前用户ID（X-User-Id 头）
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文
     */
    public void changePassword(Long userId, Long tenantId, String oldPassword, String newPassword) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        }
        if (oldPassword == null || newPassword == null || newPassword.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
        }
        if (tenantId != null) {
            TenantContextHolder.bind(tenantId);
        }
        try {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "旧密码不正确");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        log.info("用户密码修改: userId={}", userId);
        } finally {
            if (tenantId != null) {
                TenantContextHolder.clear();
            }
        }
    }

    // ============ 辅助方法 ============

    /**
     * 根据角色ID列表从 DB 聚合权限编码（数据驱动的细粒度权限）。
     *
     * <p>查询 org_role_permission 关联表与 org_permission 字典，聚合去重返回权限编码列表。
     * DB 无数据时返回空列表，由调用方回退 {@link #computePermissionsFromConfig(List)}。
     *
     * @param roleIds 角色ID列表
     * @return 权限编码列表（DB 无数据返回空列表）
     */
    private List<String> computePermissionsFromDb(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        try {
            List<String> codes = rolePermissionMapper.selectPermissionCodesByRoleIds(roleIds);
            return codes != null ? codes : List.of();
        } catch (Exception e) {
            log.warn("[AuthService] DB 权限聚合失败，将回退配置兜底: roleIds={}", roleIds, e);
            return List.of();
        }
    }

    /**
     * 根据角色列表计算用户权限集合（配置驱动兜底）。
     *
     * <p>角色-权限映射由配置 {@link AuthProperties}（aegis.auth.*）驱动，可经 Nacos 热更新；
     * 命中任一管理员角色即授予管理权限，普通员工仅拥有业务操作权限。
     * DB 无角色-权限数据时作为兜底默认值。
     *
     * @param roleCodes 角色编码列表
     * @return 权限编码列表
     */
    private List<String> computePermissionsFromConfig(List<String> roleCodes) {
        List<String> adminRoles = authProperties.getAdminRoles();
        if (roleCodes != null && adminRoles != null && !Collections.disjoint(roleCodes, adminRoles)) {
            return authProperties.getAdminPermissions();
        }
        return authProperties.getEmployeePermissions();
    }

    /** 构建登录返回结果 */
    private Map<String, Object> buildLoginResult(JwtPayload payload) {
        String accessToken = JwtUtil.sign(payload, jwtProperties.getSecret(), jwtProperties.getAccessTokenExpire());
        String refreshToken = JwtUtil.sign(payload, jwtProperties.getSecret(), jwtProperties.getRefreshTokenExpire());
        Map<String, Object> result = new HashMap<>();
        result.put("token", accessToken);
        result.put("refreshToken", refreshToken);
        result.put("expiresIn", jwtProperties.getAccessTokenExpire());
        result.put("user", buildUserInfo(payload));
        return result;
    }

    private Map<String, Object> buildUserInfo(JwtPayload payload) {
        Map<String, Object> user = new HashMap<>();
        user.put("id", payload.getUserId());
        user.put("username", payload.getUsername());
        boolean isAdmin = payload.getRoles() != null && payload.getRoles().contains("SUPER_ADMIN");
        user.put("nickname", isAdmin ? "系统管理员" : payload.getUsername());
        user.put("tenantId", payload.getTenantId());
        user.put("roles", payload.getRoles());
        user.put("permissions", payload.getPermissions());
        user.put("status", "NORMAL");
        user.put("lastLoginAt", Instant.now().toString());
        return user;
    }
}
