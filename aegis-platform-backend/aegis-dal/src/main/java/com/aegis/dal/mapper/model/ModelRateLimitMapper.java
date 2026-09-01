package com.aegis.dal.mapper.model;

import com.aegis.core.domain.model.ModelRateLimit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型限流策略 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface ModelRateLimitMapper extends BaseMapper<ModelRateLimit> {
}
