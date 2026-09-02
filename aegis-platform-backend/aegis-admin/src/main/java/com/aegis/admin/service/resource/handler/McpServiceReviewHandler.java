package com.aegis.admin.service.resource.handler;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.McpService;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.dal.mapper.resource.McpServiceMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MCP 服务资源审核 SPI 实现。状态校验允许 null（自注册场景）；版本递增始终使用
 * {@link ResourceReviewHandler#bumpVersion}（沿用历史语义，忽略 newVersion 入参）；
 * 作者字段固定为 null；驳回回退状态为 {@link AgentLifeStatus#REJECTED}。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpServiceReviewHandler implements ResourceReviewHandler {

    private final McpServiceMapper mcpServiceMapper;

    @Override
    public ResourceType supportedType() {
        return ResourceType.MCP_SERVICE;
    }

    @Override
    public ResourceReviewInfo loadResourceInfo(Long resourceId) {
        McpService service = mcpServiceMapper.selectById(resourceId);
        if (service == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "MCP服务不存在: " + resourceId);
        }
        if (service.getLifeStatus() != AgentLifeStatus.DRAFT
                && service.getLifeStatus() != AgentLifeStatus.REJECTED
                && service.getLifeStatus() != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "MCP服务当前状态不可提交审核: " + service.getLifeStatus());
        }
        Integer securityLevel = service.getSecurityLevel() != null
                ? service.getSecurityLevel().ordinal() + 1 : null;
        return new ResourceReviewInfo(
                service.getMcpName(),
                service.getVersion(),
                securityLevel,
                null,
                null);
    }

    @Override
    public void updateLifeStatus(Long resourceId, AgentLifeStatus lifeStatus,
                                 String newVersion, LocalDateTime publishedTime) {
        McpService service = mcpServiceMapper.selectById(resourceId);
        if (service == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "MCP服务不存在: " + resourceId);
        }
        String version = ResourceReviewHandler.bumpVersion(service.getVersion(), lifeStatus);
        mcpServiceMapper.update(null, new LambdaUpdateWrapper<McpService>()
                .eq(McpService::getId, resourceId)
                .set(McpService::getLifeStatus, lifeStatus)
                .set(McpService::getVersion, version)
                .set(publishedTime != null, McpService::getPublishedTime, publishedTime));
        log.info("MCP服务状态更新: id={}, lifeStatus={}, version={}", resourceId, lifeStatus, version);
    }

    @Override
    public AgentLifeStatus rejectStatus() {
        return AgentLifeStatus.REJECTED;
    }
}
