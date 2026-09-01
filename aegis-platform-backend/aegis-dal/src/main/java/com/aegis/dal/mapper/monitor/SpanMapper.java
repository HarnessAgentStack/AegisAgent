package com.aegis.dal.mapper.monitor;

import com.aegis.core.domain.monitor.SpanEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SpanMapper extends BaseMapper<SpanEntity> {
}