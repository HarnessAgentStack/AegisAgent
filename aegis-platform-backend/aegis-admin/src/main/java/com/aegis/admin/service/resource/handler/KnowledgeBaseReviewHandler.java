package com.aegis.admin.service.resource.handler;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.ResourceType;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 知识库资源审核 SPI 实现。驳回回退状态为 {@link AgentLifeStatus#DRAFT}。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseReviewHandler implements ResourceReviewHandler {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public ResourceType supportedType() {
        return ResourceType.KNOWLEDGE_BASE;
    }

    @Override
    public ResourceReviewInfo loadResourceInfo(Long resourceId) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(resourceId);
        if (kb == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在: " + resourceId);
        }
        if (kb.getLifeStatus() != AgentLifeStatus.DRAFT
                && kb.getLifeStatus() != AgentLifeStatus.REJECTED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "知识库当前状态不可提交审核: " + kb.getLifeStatus());
        }
        Integer securityLevel = kb.getSecurityLevel() != null
                ? kb.getSecurityLevel().ordinal() + 1 : null;
        return new ResourceReviewInfo(
                kb.getKbName(),
                kb.getVersion(),
                securityLevel,
                kb.getAuthorUserId(),
                kb.getAuthorDeptId());
    }

    @Override
    public void updateLifeStatus(Long resourceId, AgentLifeStatus lifeStatus,
                                 String newVersion, LocalDateTime publishedTime) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(resourceId);
        if (kb == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在: " + resourceId);
        }
        String version = newVersion != null ? newVersion
                : ResourceReviewHandler.bumpVersion(kb.getVersion(), lifeStatus);
        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, resourceId)
                .set(KnowledgeBase::getLifeStatus, lifeStatus)
                .set(KnowledgeBase::getVersion, version)
                .set(publishedTime != null, KnowledgeBase::getPublishedTime, publishedTime));
    }

    @Override
    public AgentLifeStatus rejectStatus() {
        return AgentLifeStatus.DRAFT;
    }
}
