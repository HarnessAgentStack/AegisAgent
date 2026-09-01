package com.aegis.dal.mapper.agent;

import com.aegis.core.domain.agent.AgentSubscription;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能体订阅关系 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AgentSubscriptionMapper extends BaseMapper<AgentSubscription> {
}
