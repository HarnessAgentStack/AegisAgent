package com.aegis.core.dto.org;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 部门创建请求。
 *
 * <p>由管理平面接收，租户管理员创建部门时提交。后端自动计算 deptPath 与 deptLevel。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 部门名称 */
    private String deptName;

    /** 父部门ID，根部门传0或null */
    private Long parentId;

    /** 同级排序号，升序排列 */
    private Integer sort;

    /** 部门负责人用户ID */
    private Long leaderUserId;

    /** 租户ID（由后端从请求头 X-Tenant-Id 注入，前端不传） */
    private Long tenantId;
}
