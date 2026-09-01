package com.aegis.dal.mapper.agent;

import com.aegis.core.domain.agent.AgentBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 智能体资源绑定 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AgentBindingMapper extends BaseMapper<AgentBinding> {

    /**
     * 物理删除指定智能体的全部绑定（绕过 @TableLogic 逻辑删除）。
     * 业务场景：AgentPublishService.update 在整体替换 bindings 时，
     * 逻辑删除（SET deleted=1）会导致唯一索引冲突——旧记录仍在表中，
     * 新 INSERT 的 (tenant_id, agent_id, resource_type, resource_id) 与
     * deleted=1 的旧记录命中同一唯一索引。
     *
     * @param tenantId 租户ID
     * @param agentId  智能体ID
     * @return 删除行数
     */
    @Delete("DELETE FROM agent_binding WHERE tenant_id = #{tenantId} AND agent_id = #{agentId}")
    int physicalDeleteByAgent(@Param("tenantId") Long tenantId, @Param("agentId") Long agentId);
}
