package com.aegis.dal.mapper.org;

import com.aegis.core.domain.org.UserRole;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 用户角色关联 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * 查询用户关联的角色ID列表（忽略租户行级过滤）。
     *
     * <p>登录/认证为平台级操作：查角色时同样不受当前上下文租户限制，
     * 需全租户范围内读取用户角色关联，避免登录流程被租户过滤拦截。
     *
     * @param userId 用户ID
     * @return 角色ID列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT role_id FROM org_user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsByUserIdIgnoreTenant(@Param("userId") Long userId);

    /**
     * 物理删除用户直接授予的角色关联。
     *
     * <p>UserRole 为纯关联表，重新分配时物理删除，避免逻辑删除残留导致表膨胀。
     *
     * @param tenantId 租户ID
     * @param userId   用户ID
     */
    @Delete("DELETE FROM org_user_role WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND source = 'DIRECT'")
    int physicalDeleteDirectByUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}