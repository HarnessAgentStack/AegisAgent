package com.aegis.admin.service.org;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.org.Permission;
import com.aegis.core.domain.org.Role;
import com.aegis.core.domain.org.RolePermission;
import com.aegis.dal.mapper.org.PermissionMapper;
import com.aegis.dal.mapper.org.RoleMapper;
import com.aegis.dal.mapper.org.RolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色-权限领域服务。
 *
 * <p>提供权限字典查询（树形）与角色权限分配（按角色 ID 查/赋权）能力，
 * 支撑数据驱动的细粒度 RBAC。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RolePermissionService {

    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleMapper roleMapper;

    /**
     * 查询全部权限（扁平列表，前端组装树）。
     *
     * @param tenantId 租户ID（含平台共享权限 tenantId=0）
     * @return 权限列表
     */
    public List<Permission> listPermissions(Long tenantId) {
        LambdaQueryWrapper<Permission> wrapper = new LambdaQueryWrapper<Permission>()
                .eq(Permission::getStatus, com.aegis.core.enums.common.CommonStatus.NORMAL)
                .and(w -> w.eq(Permission::getTenantId, 0L)
                        .or().eq(Permission::getTenantId, tenantId))
                .orderByAsc(Permission::getSort)
                .orderByAsc(Permission::getId);
        return permissionMapper.selectList(wrapper);
    }

    /**
     * 查询角色已分配的权限ID列表。
     *
     * @param tenantId 租户ID
     * @param roleId   角色ID
     * @return 权限ID列表
     */
    public List<Long> getRolePermissionIds(Long tenantId, Long roleId) {
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .eq(RolePermission::getTenantId, tenantId)
                        .eq(RolePermission::getRoleId, roleId))
                .stream()
                .map(RolePermission::getPermissionId)
                .collect(Collectors.toList());
    }

    /**
     * 分配角色权限（先删后增）。
     *
     * @param tenantId     租户ID
     * @param roleId       角色ID
     * @param permissionIds 权限ID列表
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignRolePermissions(Long tenantId, Long roleId, List<Long> permissionIds) {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getTenantId, tenantId)
                .eq(Role::getId, roleId));
        if (role == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "角色不存在: " + roleId);
        }
        // 先物理删除该角色的全部权限关联（必须物理删除，否则唯一索引冲突）
        rolePermissionMapper.physicalDeleteByRole(tenantId, roleId);
        // 批量插入新关联
        if (permissionIds != null && !permissionIds.isEmpty()) {
            for (Long permId : permissionIds) {
                RolePermission rp = RolePermission.builder()
                        .roleId(roleId)
                        .permissionId(permId)
                        .build();
                rp.setTenantId(tenantId);
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("角色权限分配: tenantId={}, roleId={}, permCount={}",
                tenantId, roleId, permissionIds != null ? permissionIds.size() : 0);
    }
}
