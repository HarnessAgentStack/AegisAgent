package com.aegis.core.domain.org;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.tenant.RoleType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 角色实体，权限控制的核心载体。
 *
 * <p>区分平台角色（系统级操作权限）与资源角色（资源级访问权限）。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>roleCode 租户内唯一，角色编码</li>
 *   <li>平台角色由系统预置，资源角色由资源所有者定义</li>
 * </ul>
 *
 * @author wang.zhen
 * @see UserRole
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("org_role")
public class Role extends TenantEntity {

    /** 角色编码，租户内唯一，程序引用标识，创建后不可修改 */
    private String roleCode;

    /** 角色名称，展示用 */
    private String roleName;

    /** 角色类型：{@link RoleType#PLATFORM}（平台角色，系统操作权限）、{@link RoleType#RESOURCE}（资源角色，资源访问权限） */
    private RoleType roleType;

    /** 角色描述，说明角色职责与权限范围 */
    private String description;

    /** 同级排序号，升序排列 */
    private Integer sort;

    /** 角色状态：{@link CommonStatus#NORMAL}（正常）、{@link CommonStatus#DISABLED}（禁用），禁用后关联用户失去该角色权限 */
    private CommonStatus status;
}
