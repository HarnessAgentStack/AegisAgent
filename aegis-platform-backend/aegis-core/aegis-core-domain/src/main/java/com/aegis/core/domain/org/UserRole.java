package com.aegis.core.domain.org;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.PermissionSource;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 用户角色关联实体。
 *
 * <p>用户与角色的多对多关联，开源版仅支持直接授予（DIRECT）。
 * 部门继承与资源授权为后续规划，相关字段已移除避免误导。
 * 继承 TenantEntity，按租户隔离。
 *
 * @author wang.zhen
 * @see User
 * @see Role
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("org_user_role")
public class UserRole extends TenantEntity {

    /** 用户ID，关联User主键 */
    private Long userId;

    /** 角色ID，关联Role主键 */
    private Long roleId;

    /** 授权来源：{@link PermissionSource#DIRECT}（直接授予） */
    private PermissionSource source;
}
