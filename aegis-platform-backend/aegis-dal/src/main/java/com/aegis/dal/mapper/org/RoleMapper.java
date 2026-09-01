package com.aegis.dal.mapper.org;

import com.aegis.core.domain.org.Role;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 角色 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * 按角色ID批量查询角色（忽略租户行级过滤）。
     *
     * <p>登录/认证为平台级操作：加载用户角色时不受当前上下文租户限制，
     * 需全租户范围内读取角色定义，避免登录流程被租户过滤拦截。
     *
     * @param roleIds 角色ID集合
     * @return 角色列表
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>" +
            "SELECT * FROM org_role WHERE id IN " +
            "<foreach collection='roleIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<Role> selectByIdsIgnoreTenant(@Param("roleIds") Collection<Long> roleIds);
}