package com.aegis.dal.mapper.org;

import com.aegis.core.domain.org.Permission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 权限字典 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
