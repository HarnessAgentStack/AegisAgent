package com.aegis.dal.mapper.resource;

import com.aegis.core.domain.resource.McpSubscription;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MCP 服务订阅关系 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface McpSubscriptionMapper extends BaseMapper<McpSubscription> {
}
