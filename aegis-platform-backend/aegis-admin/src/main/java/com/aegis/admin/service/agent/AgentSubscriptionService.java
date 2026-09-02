package com.aegis.admin.service.agent;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.agent.AgentDef;
import com.aegis.core.domain.agent.AgentSubscription;
import com.aegis.core.dto.agent.AgentVO;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.SubscriptionStatus;
import com.aegis.dal.mapper.agent.AgentDefMapper;
import com.aegis.dal.mapper.agent.AgentSubscriptionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 智能体订阅领域服务（从 {@link AgentPublishService} 拆出，职责单一）。
 *
 * <p>仅依赖 {@link AgentSubscriptionMapper} + {@link AgentDefMapper}，无其他耦合。
 * 负责：订阅 / 退订 / 订阅状态判定 / 可订阅市场列表 / 我创建的智能体列表。
 *
 * @author wang.zhen
 * @see AgentSubscription
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSubscriptionService {

    private final AgentDefMapper agentDefMapper;
    private final AgentSubscriptionMapper agentSubscriptionMapper;

    /**
     * 查询可订阅的智能体（仅已发布的应用智能体，通用智能体默认内置不在市场展示）。
     * 返回 AgentVO 列表，包含当前用户订阅状态。
     */
    public List<AgentVO> listSubscribable(Long tenantId, Long userId) {
        List<AgentDef> defs = agentDefMapper.selectList(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getLifeStatus, AgentLifeStatus.PUBLISHED)
                .eq(AgentDef::getAgentType, AgentType.APPLICATION)
                .eq(AgentDef::getTenantId, tenantId)
                .orderByDesc(AgentDef::getSubsCount)
                .orderByDesc(AgentDef::getCreateTime));

        // 批量查询当前用户的订阅状态
        Set<Long> subscribedAgentIds = Set.of();
        if (userId != null && !defs.isEmpty()) {
            List<AgentSubscription> subs = agentSubscriptionMapper.selectList(
                    new LambdaQueryWrapper<AgentSubscription>()
                            .eq(AgentSubscription::getTenantId, tenantId)
                            .eq(AgentSubscription::getUserId, userId)
                            .eq(AgentSubscription::getStatus, SubscriptionStatus.ACTIVE));
            subscribedAgentIds = subs.stream()
                    .map(AgentSubscription::getAgentId)
                    .collect(java.util.stream.Collectors.toSet());
        }

        final Set<Long> finalSubscribedIds = subscribedAgentIds;
        return defs.stream().map(d -> AgentVO.builder()
                .id(d.getId())
                .tenantId(d.getTenantId())
                .agentCode(d.getAgentCode())
                .agentName(d.getAgentName())
                .agentType(d.getAgentType())
                .icon(d.getIcon())
                .color(d.getColor())
                .description(d.getDescription())
                .category(d.getCategory())
                .governanceTier(d.getGovernanceTier())
                .lifeStatus(d.getLifeStatus())
                .version(d.getVersion())
                .authorUserId(d.getAuthorUserId())
                .subsCount(d.getSubsCount())
                .subscribed(finalSubscribedIds.contains(d.getId()))
                .publishedTime(d.getPublishedTime())
                .createTime(d.getCreateTime())
                .build()).toList();
    }

    /**
     * 查询当前用户创建的智能体。
     */
    public List<AgentVO> listMyAgents(Long tenantId, Long userId) {
        List<AgentDef> defs = agentDefMapper.selectList(new LambdaQueryWrapper<AgentDef>()
                .eq(AgentDef::getTenantId, tenantId)
                .eq(AgentDef::getAuthorUserId, userId)
                .ne(AgentDef::getAgentType, AgentType.UNIVERSAL)
                // 所有本人创建的智能体都在"我的智能体"展示，包括 SYSTEM 类型的 PUBLISHED 态
                // （注释中的"SYSTEM 发布后从工作台移除"是市场/订阅侧的策略，
                //   listSubscribable 已有过滤，此处不应再二次过滤作者视角）
                .orderByDesc(AgentDef::getCreateTime));
        return defs.stream().map(d -> AgentVO.builder()
                .id(d.getId()).tenantId(d.getTenantId()).agentCode(d.getAgentCode())
                .agentName(d.getAgentName()).agentType(d.getAgentType())
                .icon(d.getIcon()).color(d.getColor()).description(d.getDescription())
                .category(d.getCategory()).governanceTier(d.getGovernanceTier())
                .lifeStatus(d.getLifeStatus()).version(d.getVersion())
                .authorUserId(d.getAuthorUserId()).subsCount(d.getSubsCount())
                .publishedTime(d.getPublishedTime()).createTime(d.getCreateTime())
                .build()).toList();
    }

    // ============ 订阅（落库） ============

    /**
     * 订阅智能体（仅已发布可订阅）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void subscribe(Long tenantId, Long agentId, Long userId) {
        AgentDef def = requireAgent(agentId, tenantId);
        if (def.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT, "仅已发布智能体可订阅，当前状态: " + def.getLifeStatus());
        }
        // 类型校验——仅 APPLICATION 可订阅；SYSTEM 面向业务系统调用，UNIVERSAL 默认可用无需订阅
        if (def.getAgentType() == AgentType.SYSTEM) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "系统智能体面向业务系统调用，不支持订阅。请通过 API 接口使用。");
        }
        if (def.getAgentType() == AgentType.UNIVERSAL) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "通用智能体为平台预置，默认可用，无需订阅。");
        }
        Long count = agentSubscriptionMapper.selectCount(new LambdaQueryWrapper<AgentSubscription>()
                .eq(AgentSubscription::getTenantId, tenantId)
                .eq(AgentSubscription::getAgentId, agentId)
                .eq(AgentSubscription::getUserId, userId));
        if (count != null && count > 0) {
            // 幂等：已订阅则恢复为 ACTIVE
            agentSubscriptionMapper.update(null, new LambdaUpdateWrapper<AgentSubscription>()
                    .eq(AgentSubscription::getTenantId, tenantId)
                    .eq(AgentSubscription::getAgentId, agentId)
                    .eq(AgentSubscription::getUserId, userId)
                    .set(AgentSubscription::getStatus, SubscriptionStatus.ACTIVE)
                    .set(AgentSubscription::getUnsubscribeTime, (LocalDateTime) null));
            return;
        }
        AgentSubscription sub = AgentSubscription.builder()
                .agentId(agentId).userId(userId).status(SubscriptionStatus.ACTIVE)
                .subscribeTime(LocalDateTime.now()).build();
        sub.setTenantId(tenantId);
        agentSubscriptionMapper.insert(sub);
        agentDefMapper.update(null, new LambdaUpdateWrapper<AgentDef>()
                .eq(AgentDef::getId, agentId)
                .setSql("subs_count = subs_count + 1"));
        log.info("Agent subscribed: agentId={}, userId={}", agentId, userId);
    }

    /**
     * 退订智能体。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unsubscribe(Long tenantId, Long agentId, Long userId) {
        AgentSubscription sub = agentSubscriptionMapper.selectOne(new LambdaQueryWrapper<AgentSubscription>()
                .eq(AgentSubscription::getTenantId, tenantId)
                .eq(AgentSubscription::getAgentId, agentId)
                .eq(AgentSubscription::getUserId, userId)
                .last("LIMIT 1"));
        if (sub == null) {
            return;
        }
        agentSubscriptionMapper.update(null, new LambdaUpdateWrapper<AgentSubscription>()
                .eq(AgentSubscription::getId, sub.getId())
                .set(AgentSubscription::getStatus, SubscriptionStatus.UNSUBSCRIBED)
                .set(AgentSubscription::getUnsubscribeTime, LocalDateTime.now()));
        agentDefMapper.update(null, new LambdaUpdateWrapper<AgentDef>()
                .eq(AgentDef::getId, agentId)
                .setSql("subs_count = GREATEST(subs_count - 1, 0)"));
        log.info("Agent unsubscribed: agentId={}, userId={}", agentId, userId);
    }

    /**
     * 查询当前用户是否已订阅某智能体。
     */
    public boolean isSubscribed(Long tenantId, Long agentId, Long userId) {
        Long count = agentSubscriptionMapper.selectCount(new LambdaQueryWrapper<AgentSubscription>()
                .eq(AgentSubscription::getTenantId, tenantId)
                .eq(AgentSubscription::getAgentId, agentId)
                .eq(AgentSubscription::getUserId, userId)
                .eq(AgentSubscription::getStatus, SubscriptionStatus.ACTIVE));
        return count != null && count > 0;
    }

    // ============ 内部方法 ============

    private AgentDef requireAgent(Long agentId, Long tenantId) {
        AgentDef def = agentDefMapper.selectById(agentId);
        if (def == null) {
            log.warn("Agent not found: agentId={}, tenantId={}", agentId, tenantId);
            throw new BusinessException(ResultCode.NOT_FOUND, "智能体不存在: " + agentId);
        }
        if (tenantId != null && def.getTenantId() != null && !tenantId.equals(def.getTenantId())) {
            log.warn("Tenant mismatch: agentId={}, expectedTenant={}, actualTenant={}",
                    agentId, tenantId, def.getTenantId());
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该智能体");
        }
        return def;
    }
}
