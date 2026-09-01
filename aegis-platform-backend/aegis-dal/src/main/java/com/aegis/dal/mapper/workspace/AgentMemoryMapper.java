package com.aegis.dal.mapper.workspace;

import com.aegis.core.domain.agent.AgentMemory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 智能体记忆 Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AgentMemoryMapper extends BaseMapper<AgentMemory> {

    /**
     * Upsert 语义插入记忆（INSERT ... ON DUPLICATE KEY UPDATE）。
     *
     * <p>当唯一键 {@code uk_agent_memory_key} (tenant_id, agent_id, user_id, memory_type, memory_key)
     * 冲突时，直接更新 memory_value，避免并发场景下的重复插入报错。
     *
     * @param id           主键ID（雪花ID）
     * @param tenantId     租户ID
     * @param agentId      智能体ID
     * @param userId       用户ID
     * @param memoryType   记忆类型
     * @param memoryKey    记忆键
     * @param memoryValue  记忆值（JSON 格式）
     * @param editable     是否可编辑
     * @param source       来源
     * @param createBy     创建人ID
     * @return 受影响的行数
     */
    @Update("INSERT INTO agent_memory (id, tenant_id, agent_id, user_id, memory_type, memory_key, memory_value, editable, source, create_by) " +
            "VALUES (#{id}, #{tenantId}, #{agentId}, #{userId}, #{memoryType}, #{memoryKey}, #{memoryValue}, #{editable}, #{source}, #{createBy}) " +
            "ON DUPLICATE KEY UPDATE memory_value = VALUES(memory_value), update_time = CURRENT_TIMESTAMP")
    int insertOrUpdate(@Param("id") Long id,
                       @Param("tenantId") Long tenantId,
                       @Param("agentId") Long agentId,
                       @Param("userId") Long userId,
                       @Param("memoryType") String memoryType,
                       @Param("memoryKey") String memoryKey,
                       @Param("memoryValue") String memoryValue,
                       @Param("editable") Boolean editable,
                       @Param("source") String source,
                       @Param("createBy") Long createBy);
}
