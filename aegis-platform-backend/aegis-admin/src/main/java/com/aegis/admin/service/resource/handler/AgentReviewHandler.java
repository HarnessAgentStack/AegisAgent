package com.aegis.admin.service.resource.handler;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentConfig;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.dal.mapper.agent.AgentConfigMapper;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 智能体资源审核 SPI 实现。发布时复制当前 {@link AgentConfig} 为新版本快照；
 * 驳回回退状态为 {@link AgentLifeStatus#REJECTED}。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentReviewHandler implements ResourceReviewHandler {

    private final AgentDefMapper agentDefMapper;
    private final AgentConfigMapper agentConfigMapper;

    @Override
    public ResourceType supportedType() {
        return ResourceType.AGENT;
    }

    @Override
    public ResourceReviewInfo loadResourceInfo(Long resourceId) {
        AgentDef agent = agentDefMapper.selectById(resourceId);
        if (agent == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + resourceId);
        }
        if (agent.getLifeStatus() != AgentLifeStatus.DRAFT
                && agent.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "智能体当前状态不可提交审核: " + agent.getLifeStatus());
        }
        Integer securityLevel = agent.getGovernanceTier() != null
                ? agent.getGovernanceTier().ordinal() + 1 : null;
        return new ResourceReviewInfo(
                agent.getAgentName(),
                agent.getVersion(),
                securityLevel,
                agent.getAuthorUserId(),
                agent.getAuthorDeptId());
    }

    @Override
    public void updateLifeStatus(Long resourceId, AgentLifeStatus lifeStatus,
                                 String newVersion, LocalDateTime publishedTime) {
        AgentDef agent = agentDefMapper.selectById(resourceId);
        if (agent == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + resourceId);
        }
        String version = newVersion != null ? newVersion
                : ResourceReviewHandler.bumpVersion(agent.getVersion(), lifeStatus);
        // 审核通过时复制当前配置为新版本快照
        if (lifeStatus == AgentLifeStatus.PUBLISHED) {
            AgentConfig currentCfg = agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                    .eq(AgentConfig::getAgentId, resourceId)
                    .eq(AgentConfig::getVersion, agent.getVersion())
                    .last("LIMIT 1"));
            if (currentCfg != null) {
                currentCfg.setId(null);
                currentCfg.setVersion(version);
                agentConfigMapper.insert(currentCfg);
            }
        }
        agentDefMapper.update(null, new LambdaUpdateWrapper<AgentDef>()
                .eq(AgentDef::getId, resourceId)
                .set(AgentDef::getLifeStatus, lifeStatus)
                .set(AgentDef::getVersion, version)
                .set(publishedTime != null, AgentDef::getPublishedTime, publishedTime));
    }

    @Override
    public AgentLifeStatus rejectStatus() {
        return AgentLifeStatus.REJECTED;
    }
}
