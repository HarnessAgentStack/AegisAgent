package com.aegis.dal.mapper.security;

import com.aegis.core.domain.security.OutboundPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 出站策略 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface OutboundPolicyMapper extends BaseMapper<OutboundPolicy> {
}
