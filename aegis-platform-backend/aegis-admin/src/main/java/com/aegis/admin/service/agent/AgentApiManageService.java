package com.aegis.admin.service.agent;

import com.aegis.core.domain.agent.AgentApi;
import com.aegis.dal.mapper.agent.AgentApiMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent API 管理服务：封装 AgentApi 的持久化操作，
 * 避免 web 层直接访问 Mapper。
 *
 * <p>对应架构规则 R1：web 层只能依赖 service 层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApiManageService {

    private final AgentApiMapper agentApiMapper;

    /**
     * 按智能体 ID 查询 API 配置列表。
     */
    public List<AgentApi> listByAgent(Long agentId) {
        return agentApiMapper.selectList(new LambdaQueryWrapper<AgentApi>()
                .eq(AgentApi::getAgentId, agentId)
                .orderByDesc(AgentApi::getCreateTime));
    }

    /**
     * 按 ID 查询 API 配置。
     */
    public AgentApi getById(Long id) {
        return agentApiMapper.selectById(id);
    }

    /**
     * 保存或更新 API 配置。
     */
    public void update(AgentApi api) {
        agentApiMapper.updateById(api);
        log.info("Agent API updated: id={}, agentId={}", api.getId(), api.getAgentId());
    }

    /**
     * 分页查询 API 配置。
     */
    public Page<AgentApi> page(int page, int size, Long tenantId) {
        Page<AgentApi> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<AgentApi> wrapper = new LambdaQueryWrapper<>();
        if (tenantId != null) {
            wrapper.eq(AgentApi::getTenantId, tenantId);
        }
        wrapper.orderByDesc(AgentApi::getCreateTime);
        return agentApiMapper.selectPage(pageObj, wrapper);
    }
}
