package com.aegis.core.domain.org;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.common.CommonStatus;
import com.aegis.core.enums.common.SyncSource;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 部门实体，树形组织架构节点。
 *
 * <p>租户内树形组织结构（最多5级），通过 parentId 构建层级，deptPath 记录完整路径。
 * 支持矩阵式组织（用户可归属多个部门，通过 UserRole 资源授权实现）。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>deptPath 格式 /root/parent/self/，便于祖先与后代查询</li>
 *   <li>deptLevel 取值1-5，根部门为1</li>
 *   <li>删除部门需校验子部门与关联用户，推荐软删除</li>
 * </ul>
 *
 * @author wang.zhen
 * @see User
 * @see UserRole
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("org_department")
public class Department extends TenantEntity {

    /** 部门名称，租户内可重复，同级建议唯一 */
    private String deptName;

    /** 父部门ID，根部门为0或null */
    private Long parentId;

    /** 部门完整路径，格式 /root/parent/self/，用于祖先/后代查询 */
    private String deptPath;

    /** 部门层级，取值1-5，根部门为1 */
    private Integer deptLevel;

    /** 同级排序号，升序排列 */
    private Integer sort;

    /** 部门负责人用户ID，关联User主键 */
    private Long leaderUserId;

    /** 部门状态：{@link CommonStatus#NORMAL}（正常）、{@link CommonStatus#DISABLED}（禁用），禁用后不在组织树展示 */
    private CommonStatus status;

    /** 同步来源：{@link SyncSource#HR}（HR同步）、{@link SyncSource#OA}（OA同步）、{@link SyncSource#LDAP}（LDAP同步）、{@link SyncSource#MANUAL}（手动创建），标识数据来源 */
    private SyncSource syncSource;
}
