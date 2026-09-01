package com.aegis.dal.mapper.tenant;

import com.aegis.core.domain.tenant.TenantQuota;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户配额 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface TenantQuotaMapper extends BaseMapper<TenantQuota> {
}
