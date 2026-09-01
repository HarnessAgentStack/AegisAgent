package com.aegis.dal.mapper.agent;

import com.aegis.core.domain.agent.AgentApi;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 智能体开放 API Mapper。
 *
 * @author wang.zhen
 */
@Mapper
public interface AgentApiMapper extends BaseMapper<AgentApi> {

    /**
     * 查询指定智能体最新一条 agent_api 记录（含已逻辑删除行）。
     *
     * <p>用途：API 配置补建前探测逻辑删除残留行。agent_api 存在
     * {@code uk_agent_api_path(tenant_id, api_path)} 唯一键且 {@code api_path}
     * 由 agentCode 拼接生成，若直接 INSERT 会与残留行（deleted=1）冲突，
     * 需先查残留行并复用（恢复）而非新插。
     *
     * <p>注：BaseMapper 的查询自动追加 {@code deleted = 0}（@TableLogic），
     * 含删除行的探测必须走本原生 SQL。
     *
     * @param agentId 智能体ID
     * @return 最新一条记录（含已删除），无则 null
     */
    @Select("SELECT * FROM agent_api WHERE agent_id = #{agentId} ORDER BY id DESC LIMIT 1")
    AgentApi selectLatestIncludeDeleted(@Param("agentId") Long agentId);

    /**
     * 恢复逻辑删除行并置为指定状态（绕过 @TableLogic 的列级更新）。
     *
     * <p>复用残留行时先物理恢复（deleted=0），再用 BaseMapper 更新其余业务字段。
     *
     * @param id     agent_api 主键
     * @param status 目标状态（CommonStatus 枚举 name）
     * @return 影响行数
     */
    @Update("UPDATE agent_api SET deleted = 0, status = #{status}, update_time = NOW() WHERE id = #{id}")
    int reviveById(@Param("id") Long id, @Param("status") String status);
}
