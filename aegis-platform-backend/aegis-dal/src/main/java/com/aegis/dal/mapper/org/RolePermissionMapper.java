package com.aegis.dal.mapper.org;

import com.aegis.core.domain.org.RolePermission;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色-权限关联 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermission> {

    /**
     * 按角色ID列表批量查询权限编码（忽略租户行级过滤，登录为平台级操作）。
     *
     * @param roleIds 角色ID列表
     * @return 权限编码列表（去重由调用方处理）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>" +
            "SELECT DISTINCT p.permission_code FROM org_role_permission rp " +
            "INNER JOIN org_permission p ON rp.permission_id = p.id " +
            "WHERE rp.role_id IN " +
            "<foreach item='id' collection='roleIds' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<String> selectPermissionCodesByRoleIds(@Param("roleIds") List<Long> roleIds);

    /**
     * 物理删除角色的全部权限关联。
     *
     * <p>RolePermission 是纯关联表，无需保留历史，且存在唯一索引
     * {@code uk_role_perm(tenant_id, role_id, permission_id)}，
     * 先软删除会导致后续 INSERT 唯一冲突。
     *
     * @param tenantId 租户ID
     * @param roleId   角色ID
     */
    @Delete("DELETE FROM org_role_permission WHERE tenant_id = #{tenantId} AND role_id = #{roleId}")
    int physicalDeleteByRole(@Param("tenantId") Long tenantId, @Param("roleId") Long roleId);
}
