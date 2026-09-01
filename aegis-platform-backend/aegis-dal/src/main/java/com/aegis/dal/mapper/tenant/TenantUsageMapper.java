package com.aegis.dal.mapper.tenant;

import com.aegis.core.domain.tenant.TenantUsage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/**
 * 租户用量 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface TenantUsageMapper extends BaseMapper<TenantUsage> {

    /**
     * 原子递增当日/当月 Token 用量，避免 read-then-write 并发超卖。
     *
     * @param tenantId 租户ID
     * @param statDate 统计日期
     * @param delta    递增量
     * @return 影响行数
     */
    @Update("UPDATE ten_usage SET token_used_today = token_used_today + #{delta}, " +
            "token_used_this_month = token_used_this_month + #{delta} " +
            "WHERE tenant_id = #{tenantId} AND stat_date = #{statDate}")
    int incrementTokenUsage(@Param("tenantId") Long tenantId,
                            @Param("statDate") LocalDate statDate,
                            @Param("delta") long delta);
}
