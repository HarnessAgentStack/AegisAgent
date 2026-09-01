package com.aegis.runtime.service.workspace;

import com.aegis.core.domain.workspace.AgentWorkspaceMaterial;
import com.aegis.dal.mapper.workspace.AgentWorkspaceMaterialMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作区物化记录领域服务。
 *
 * <p>收口 {@link AgentWorkspaceMaterialMapper} 的数据访问，供 {@code WorkspaceMaterializer}
 * 和 {@code BindingSyncMiddleware} 调用，避免 integration 层直接持有 DAL Mapper。
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceMaterialService {

    private final AgentWorkspaceMaterialMapper workspaceMaterialMapper;

    /**
     * 按 agentId 查询全部物化记录（含不同 userId）。
     *
     * <p>用于 {@code BindingSyncMiddleware} 在多用户场景下检测指纹变更。
     *
     * @param agentId 智能体ID
     * @return 物化记录列表
     */
    public List<AgentWorkspaceMaterial> listByAgentId(long agentId) {
        return workspaceMaterialMapper.selectList(
                new LambdaQueryWrapper<AgentWorkspaceMaterial>()
                        .eq(AgentWorkspaceMaterial::getAgentId, agentId));
    }

    /**
     * 按 agentId + userId 查询单条物化记录（用于 upsert 判断）。
     *
     * @param agentId 智能体ID
     * @param userId  用户ID
     * @return 物化记录，不存在时返回 null
     */
    public AgentWorkspaceMaterial findByAgentAndUser(long agentId, long userId) {
        return workspaceMaterialMapper.selectOne(
                new LambdaQueryWrapper<AgentWorkspaceMaterial>()
                        .eq(AgentWorkspaceMaterial::getAgentId, agentId)
                        .eq(AgentWorkspaceMaterial::getUserId, userId));
    }

    /**
     * 插入物化记录。
     *
     * @param record 物化记录
     */
    public void insert(AgentWorkspaceMaterial record) {
        workspaceMaterialMapper.insert(record);
    }

    /**
     * 更新物化记录。
     *
     * @param record 物化记录
     */
    public void updateById(AgentWorkspaceMaterial record) {
        workspaceMaterialMapper.updateById(record);
    }
}
