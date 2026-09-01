package com.aegis.admin.service.org;

import com.aegis.dal.mapper.org.RoleMapper;
import com.aegis.dal.mapper.org.UserMapper;
import com.aegis.dal.mapper.org.UserRoleMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.org.Role;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.org.UserRole;
import com.aegis.core.dto.org.UserCreateRequest;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.security.PermissionSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.aegis.core.base.TenantEntity;

/**
 * 用户管理领域服务。
 *
 * <p>编排租户内用户的创建、更新、禁用/启用、角色分配与查询。
 * 密码采用 BCrypt（cost=10）加密存储，不可逆向。
 * 继承 TenantEntity，按租户隔离，所有查询强制带 tenantId 条件。
 *
 * @author wang.zhen
 * @see User
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;

    /** BCrypt 密码编码器，cost=10，线程安全 */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    /**
     * 分页查询用户。
     *
     * @param tenantId 租户ID
     * @param keyword  关键词（可选，匹配用户名/真实姓名/工号）
     * @param deptId   部门ID（可选，过滤指定部门用户）
     * @param page     页码
     * @param size     每页条数
     * @return 分页结果
     */
    public Page<User> page(Long tenantId, String keyword, Long deptId, int page, int size) {
        Page<User> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, tenantId)
                .eq(deptId != null, User::getDeptId, deptId)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(User::getUsername, keyword)
                        .or().like(User::getRealName, keyword)
                        .or().like(User::getEmpNo, keyword))
                .orderByDesc(User::getCreateTime);
        Page<User> result = userMapper.selectPage(pageObj, wrapper);
        // 批量填充用户角色
        if (result.getRecords() != null && !result.getRecords().isEmpty()) {
            fillUserRoles(result.getRecords(), tenantId);
        }
        return result;
    }

    /**
     * 查询用户详情。
     *
     * @param tenantId 租户ID
     * @param id       用户ID
     * @return 用户实体
     */
    public User detail(Long tenantId, Long id) {
        User user = requireUser(id, tenantId);
        fillUserRoles(java.util.Collections.singletonList(user), tenantId);
        return user;
    }

    /**
     * 创建用户（BCrypt 加密密码，可选分配角色）。
     *
     * @param req 创建请求
     * @return 用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(UserCreateRequest req) {
        if (req.getTenantId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (req.getUsername() == null || req.getUsername().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "用户名不能为空");
        }
        if (req.getPassword() == null || req.getPassword().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "密码不能为空");
        }
        // username 租户内唯一
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getTenantId, req.getTenantId())
                .eq(User::getUsername, req.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "用户名已存在: " + req.getUsername());
        }
        // empNo 租户内唯一（若提供）
        if (req.getEmpNo() != null && !req.getEmpNo().isEmpty()) {
            Long empExists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, req.getTenantId())
                    .eq(User::getEmpNo, req.getEmpNo()));
            if (empExists != null && empExists > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "工号已存在: " + req.getEmpNo());
            }
        }
        // email 租户内唯一（若提供）
        if (req.getEmail() != null && !req.getEmail().isEmpty()) {
            Long emailExists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, req.getTenantId())
                    .eq(User::getEmail, req.getEmail()));
            if (emailExists != null && emailExists > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "邮箱已存在: " + req.getEmail());
            }
        }
        // phone 租户内唯一（若提供）
        if (req.getPhone() != null && !req.getPhone().isEmpty()) {
            Long phoneExists = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getTenantId, req.getTenantId())
                    .eq(User::getPhone, req.getPhone()));
            if (phoneExists != null && phoneExists > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "手机号已存在: " + req.getPhone());
            }
        }

        User user = User.builder()
                .username(req.getUsername())
                .password(passwordEncoder.encode(req.getPassword()))
                .realName(req.getRealName())
                .empNo(req.getEmpNo())
                .email(req.getEmail())
                .phone(req.getPhone())
                .deptId(req.getDeptId())
                .status(CommonStatus.NORMAL)
                .build();
        user.setTenantId(req.getTenantId());
        userMapper.insert(user);
        log.info("User created: id={}, username={}, tenantId={}", user.getId(), user.getUsername(), user.getTenantId());

        // 分配角色（若提供）
        if (req.getRoleIds() != null && !req.getRoleIds().isEmpty()) {
            assignRoles(req.getTenantId(), user.getId(), req.getRoleIds());
        }

        return user.getId();
    }

    /**
     * 更新用户信息（不允许修改 username 与 password）。
     *
     * @param user 用户实体（id、tenantId 必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(User user) {
        User existing = requireUser(user.getId(), user.getTenantId());
        // 不允许修改用户名与密码
        user.setUsername(existing.getUsername());
        user.setPassword(existing.getPassword());
        userMapper.updateById(user);
        log.info("User updated: id={}", user.getId());
    }

    /**
     * 分配角色（先删后增，校验角色归属）。
     *
     * <p>清除用户已有的直接授予角色关联，重新批量插入。部门继承与资源授权不受影响。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     * @param roleIds  角色ID列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long tenantId, Long userId, List<Long> roleIds) {
        requireUser(userId, tenantId);
        // 校验角色归属本租户
        if (roleIds != null && !roleIds.isEmpty()) {
            Long validCount = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                    .eq(Role::getTenantId, tenantId)
                    .in(Role::getId, roleIds));
            if (validCount == null || validCount != roleIds.size()) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "存在不属于本租户的角色");
            }
        }
        // 先物理删除该用户直接授予的角色关联（纯关联表，避免逻辑删除残留）
        userRoleMapper.physicalDeleteDirectByUser(tenantId, userId);
        // 批量插入新的角色关联
        if (roleIds != null && !roleIds.isEmpty()) {
            for (Long roleId : roleIds) {
                UserRole ur = UserRole.builder()
                        .userId(userId)
                        .roleId(roleId)
                        .source(PermissionSource.DIRECT)
                        .build();
                ur.setTenantId(tenantId);
                userRoleMapper.insert(ur);
            }
        }
        log.info("User roles assigned: tenantId={}, userId={}, roleCount={}",
                tenantId, userId, roleIds != null ? roleIds.size() : 0);
    }

    /**
     * 禁用用户（status → DISABLED）。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void disable(Long tenantId, Long userId) {
        requireUser(userId, tenantId);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .eq(User::getTenantId, tenantId)
                .set(User::getStatus, CommonStatus.DISABLED));
        log.info("User disabled: tenantId={}, userId={}", tenantId, userId);
    }

    /**
     * 启用用户（status → NORMAL）。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void enable(Long tenantId, Long userId) {
        requireUser(userId, tenantId);
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getId, userId)
                .eq(User::getTenantId, tenantId)
                .set(User::getStatus, CommonStatus.NORMAL));
        log.info("User enabled: tenantId={}, userId={}", tenantId, userId);
    }

    // ============ 内部方法 ============

    private User requireUser(Long id, Long tenantId) {
        User user = userMapper.selectById(id);
        if (user == null || !tenantId.equals(user.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户不存在: " + id);
        }
        return user;
    }

    /**
     * 批量填充用户的直接授予角色（DIRECT 类型）。
     *
     * <p>先查 org_user_role 拿到 userId → roleId 映射，再批量查 org_role，
     * 组装为 Map[userId → List[Role]] 后回填到每个 User 的 roles 字段。
     */
    private void fillUserRoles(List<User> users, Long tenantId) {
        if (users == null || users.isEmpty()) return;
        List<Long> userIds = users.stream().map(User::getId).collect(Collectors.toList());
        // 1. 查 DIRECT 类型的用户-角色关联
        List<UserRole> userRoles = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getSource, PermissionSource.DIRECT)
                .in(UserRole::getUserId, userIds));
        if (userRoles.isEmpty()) return;
        // 2. 提取 roleIds 批量查 Role
        List<Long> roleIds = userRoles.stream()
                .map(UserRole::getRoleId).distinct().collect(Collectors.toList());
        List<Role> roles = roleMapper.selectBatchIds(roleIds);
        Map<Long, Role> roleMap = roles.stream()
                .collect(Collectors.toMap(Role::getId, r -> r, (a, b) -> a));
        // 3. 组装 userId → List<Role>
        Map<Long, List<Role>> userRoleMap = userRoles.stream()
                .filter(ur -> roleMap.containsKey(ur.getRoleId()))
                .collect(Collectors.groupingBy(
                        UserRole::getUserId,
                        Collectors.mapping(ur -> roleMap.get(ur.getRoleId()), Collectors.toList())));
        // 4. 回填
        users.forEach(u -> u.setRoles(userRoleMap.getOrDefault(u.getId(), java.util.Collections.emptyList())));
    }
}
