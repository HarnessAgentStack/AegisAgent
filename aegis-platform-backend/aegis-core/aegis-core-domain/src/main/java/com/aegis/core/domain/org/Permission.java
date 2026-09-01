package com.aegis.core.domain.org;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.common.CommonStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 权限字典实体。
 *
 * <p>权限编码（permissionCode）租户内唯一，是数据驱动的角色-权限映射的基础。
 * 继承 TenantEntity，按租户隔离。平台级权限由系统预置（tenantId=0 视为平台共享）。
 *
 * @author wang.zhen
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("org_permission")
public class Permission extends TenantEntity {

    /** 权限编码，如 agent:view / tenant:manage，租户内唯一 */
    private String permissionCode;

    /** 权限名称，展示用 */
    private String permissionName;

    /** 权限类型：MENU（菜单）/ BUTTON（按钮）/ API（接口） */
    private String permissionType;

    /** 父权限ID，用于权限树展示，null 表示根节点 */
    private Long parentId;

    /** 同级排序号 */
    private Integer sort;

    /** 状态：NORMAL/DISABLED */
    private CommonStatus status;
}
