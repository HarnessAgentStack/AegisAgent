package com.aegis.dal.mapper.agent;

import com.aegis.core.domain.agent.AgentApiKey;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AgentApiKeyMapper extends BaseMapper<AgentApiKey> {

    @Select("SELECT * FROM agent_api_key WHERE api_id = #{apiId} ORDER BY create_time DESC")
    List<AgentApiKey> listByApiId(@Param("apiId") Long apiId);

    @Select("SELECT * FROM agent_api_key WHERE api_key_hash = #{hash} AND status = 'ACTIVE' LIMIT 1")
    AgentApiKey findActiveByHash(@Param("hash") String hash);
}
