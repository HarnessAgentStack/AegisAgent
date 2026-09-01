package com.aegis.core.dto.org;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import com.aegis.core.domain.org.User;
import com.aegis.core.domain.tenant.Tenant;

/**
 * 用户创建请求。
 *
 * <p>由管理平面接收，租户管理员创建用户时提交。密码经 BCrypt 加密后存储。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户名，租户内唯一，登录凭证，创建后不可修改 */
    private String username;

    /** 明文密码，后端 BCrypt 加密存储 */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 工号，租户内唯一 */
    private String empNo;

    /** 邮箱，租户内唯一 */
    private String email;

    /** 手机号，租户内唯一 */
    private String phone;

    /** 主部门ID */
    private Long deptId;

    /** 角色ID列表 */
    private List<Long> roleIds;

    /** 租户ID（由后端从请求头 X-Tenant-Id 注入，前端不传） */
    private Long tenantId;

    /** 创建者用户ID（由后端从请求头 X-User-Id 注入，前端不传） */
    private Long createUserId;
}
