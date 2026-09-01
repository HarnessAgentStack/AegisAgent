package com.aegis.core.dto.org;

import com.aegis.core.enums.tenant.RoleType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 角色创建请求。
 *
 * <p>由管理平面接收，租户管理员创建资源角色时提交。平台角色由系统预置，不可通过本接口创建。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 角色编码，租户内唯一，创建后不可修改 */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 角色类型：PLATFORM（平台角色，系统预置）/ RESOURCE（资源角色，可创建） */
    private RoleType roleType;

    /** 角色描述 */
    private String description;

    /** 同级排序号，升序排列 */
    private Integer sort;

    /** 租户ID（由后端从请求头 X-Tenant-Id 注入，前端不传） */
    private Long tenantId;
}
