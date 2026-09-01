package com.aegis.dal.mapper.tenant;

import com.aegis.core.domain.tenant.Tenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
