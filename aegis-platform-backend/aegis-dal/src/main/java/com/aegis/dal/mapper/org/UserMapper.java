package com.aegis.dal.mapper.org;

import com.aegis.core.domain.org.User;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按用户名查询用户（忽略租户行级过滤）。
     *
     * <p>登录/认证为平台级操作：用户名全局唯一（跨租户唯一约束），
     * 登录时尚未确定用户所属租户，因此必须全租户范围内定位用户，
     * 再用查询到的用户所属租户构建 JWT。若沿用租户过滤（当前上下文为空时
     * 会 fallback 到 tenantId=0），会导致跨租户登录失败。
     *
     * @param username 用户名
     * @return 匹配的用户，不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM org_user WHERE username = #{username} LIMIT 1")
    User selectByUsernameIgnoreTenant(@Param("username") String username);

    /**
     * 按租户ID + 用户名查询用户（忽略租户行级过滤，用于登录两级定位）。
     *
     * <p>登录时先按 tenantCode 解析出 tenantId，再按 tenantId + username 精确定位用户，
     * 避免跨租户同名用户歧义。
     *
     * @param tenantId 租户ID
     * @param username 用户名
     * @return 匹配的用户，不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT * FROM org_user WHERE tenant_id = #{tenantId} AND username = #{username} LIMIT 1")
    User selectByTenantAndUsername(@Param("tenantId") Long tenantId, @Param("username") String username);
}