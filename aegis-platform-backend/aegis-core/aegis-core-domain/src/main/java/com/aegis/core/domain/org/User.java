package com.aegis.core.domain.org;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.common.CommonStatus;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户实体，平台用户主体。
 *
 * <p>租户内用户主体，关联主部门（deptId）。
 * 用户通过 UserRole 关联角色，角色决定其平台操作与资源访问权限。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>username 租户内唯一，登录凭证</li>
 *   <li>password 存储BCrypt哈希，不可逆向</li>
 * </ul>
 *
 * <h3>关联实体</h3>
 * <ul>
 *   <li>{@link Department} - 主部门</li>
 *   <li>{@link UserRole} - 角色关联</li>
 *   <li>{@link Role} - 角色定义</li>
 * </ul>
 *
 * @author wang.zhen
 * @see Department
 * @see UserRole
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("org_user")
public class User extends TenantEntity {

    /** 用户名，租户内唯一，登录凭证，创建后不可修改 */
    private String username;

    /** 密码哈希，BCrypt加密存储，不可逆向 */
    private String password;

    /** 真实姓名，展示用 */
    private String realName;

    /** 工号，租户内唯一，用于与HR系统对接 */
    private String empNo;

    /** 邮箱，用于通知与密码找回，租户内唯一 */
    private String email;

    /** 手机号，用于MFA与紧急通知，租户内唯一 */
    private String phone;

    /** 头像URL */
    private String avatar;

    /** 用户状态：{@link CommonStatus#NORMAL}（正常）、{@link CommonStatus#DISABLED}（禁用），禁用后无法登录 */
    private CommonStatus status;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 最后登录IP，用于安全审计 */
    private String lastLoginIp;

    /** 主部门ID，关联Department主键，用户归属的主部门 */
    private Long deptId;

    /**
     * 用户角色列表（非持久化字段，查询时关联填充）。
     * <p>来源：org_user_role JOIN org_role，取 DIRECT 类型直接授予的角色
     */
    @TableField(exist = false)
    private List<Role> roles;
}
