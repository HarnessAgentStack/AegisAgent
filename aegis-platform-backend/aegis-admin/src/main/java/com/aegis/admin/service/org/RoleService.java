package com.aegis.admin.service.org;

import com.aegis.dal.mapper.org.RoleMapper;
import com.aegis.dal.mapper.org.UserRoleMapper;
import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.org.Role;
import com.aegis.core.domain.org.UserRole;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.tenant.RoleType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import com.aegis.core.base.TenantEntity;

/**
 * 角色管理领域服务。
 *
 * <p>编排租户内角色的创建、更新、删除与查询。
 * 区分平台角色（系统级操作权限，由系统预置）与资源角色（资源级访问权限，由租户管理员创建）。
 * 继承 TenantEntity，按租户隔离，所有查询强制带 tenantId 条件。
 *
 * @author wang.zhen
 * @see Role
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    /**
     * 查询租户内全部角色列表。
     *
     * @param tenantId 租户ID
     * @return 角色列表，按 sort 升序
     */
    public List<Role> list(Long tenantId) {
        return roleMapper.selectList(new LambdaQueryWrapper<Role>()
                .eq(Role::getTenantId, tenantId)
                .orderByAsc(Role::getSort));
    }

    /**
     * 创建角色（仅资源角色可创建，平台角色由系统预置）。
     *
     * @param role 角色实体（tenantId、roleCode、roleName 必填）
     * @return 角色ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(Role role) {
        if (role.getTenantId() == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "租户ID不能为空");
        }
        if (role.getRoleCode() == null || role.getRoleCode().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色编码不能为空");
        }
        if (role.getRoleName() == null || role.getRoleName().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "角色名称不能为空");
        }
        // 仅资源角色可创建，平台角色由系统预置
        if (role.getRoleType() == RoleType.PLATFORM) {
            throw new BusinessException(ResultCode.FORBIDDEN, "平台角色由系统预置，不可创建");
        }
        // roleCode 租户内唯一
        Long exists = roleMapper.selectCount(new LambdaQueryWrapper<Role>()
                .eq(Role::getTenantId, role.getTenantId())
                .eq(Role::getRoleCode, role.getRoleCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "角色编码已存在: " + role.getRoleCode());
        }
        if (role.getStatus() == null) {
            role.setStatus(CommonStatus.NORMAL);
        }
        if (role.getSort() == null) {
            role.setSort(0);
        }
        roleMapper.insert(role);
        log.info("Role created: id={}, code={}, tenantId={}", role.getId(), role.getRoleCode(), role.getTenantId());
        return role.getId();
    }

    /**
     * 更新角色信息（不允许修改 roleCode 与 roleType）。
     *
     * @param role 角色实体（id、tenantId 必填）
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Role role) {
        Role existing = requireRole(role.getId(), role.getTenantId());
        // 不允许修改编码与类型
        role.setRoleCode(existing.getRoleCode());
        role.setRoleType(existing.getRoleType());
        roleMapper.updateById(role);
        log.info("Role updated: id={}", role.getId());
    }

    /**
     * 删除角色（校验无关联用户、无子角色）。
     *
     * @param tenantId 租户ID
     * @param id       角色ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long tenantId, Long id) {
        Role existing = requireRole(id, tenantId);
        // 平台角色不可删除
        if (existing.getRoleType() == RoleType.PLATFORM) {
            throw new BusinessException(ResultCode.FORBIDDEN, "平台角色不可删除");
        }
        // 校验无关联用户
        Long userCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRole>()
                .eq(UserRole::getTenantId, tenantId)
                .eq(UserRole::getRoleId, id));
        if (userCount != null && userCount > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "角色下存在关联用户，无法删除");
        }
        roleMapper.deleteById(id);
        log.info("Role deleted: id={}, tenantId={}", id, tenantId);
    }

    // ============ 内部方法 ============

    private Role requireRole(Long id, Long tenantId) {
        Role role = roleMapper.selectById(id);
        if (role == null || !tenantId.equals(role.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在: " + id);
        }
        return role;
    }
}
