package com.aegis.core.domain.org;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 角色-权限关联实体。
 *
 * <p>角色与权限的多对多关联，(tenantId, roleId, permissionId) 唯一。
 * 登录时按用户角色聚合本表得到 permissions 写入 JWT。
 *
 * @author wang.zhen
 * @see Role
 * @see Permission
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("org_role_permission")
public class RolePermission extends TenantEntity {

    /** 角色ID，关联 Role 主键 */
    private Long roleId;

    /** 权限ID，关联 Permission 主键 */
    private Long permissionId;
}
