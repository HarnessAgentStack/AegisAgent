package com.aegis.admin.service.resource;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.KnowledgeBase;
import com.aegis.core.domain.resource.KbSubscription;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.dal.mapper.resource.KnowledgeBaseMapper;
import com.aegis.dal.mapper.resource.KbSubscriptionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 知识库订阅领域服务。
 *
 * <p>管理用户（USER）与智能体（AGENT）对知识库的真实订阅关系，
 * 提供订阅/取消/查询能力，并实时维护 {@link KnowledgeBase#getSubsCount()} 聚合字段。</p>
 *
 * <h3>数据隔离说明</h3>
 * <p>res_kb_subscription 表通过 tenant_id 字段实现多租户隔离，
 * MyBatis-Plus 逻辑删除（deleted=0 为有效记录）自动过滤已取消的订阅关系。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbSubscriptionService {

    private final KbSubscriptionMapper subscriptionMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 订阅知识库。
     *
     * <p>若已有 ACTIVE 订阅则直接返回，不重复创建。
     * 订阅成功后实时更新 res_knowledge_base.subs_count 聚合值。</p>
     *
     * <h3>幂等保证</h3>
     * <p>通过唯一索引 uk_kb_sub(tenant_id, kb_id, subscriber_type, subscriber_id) +
     * 应用层双重检查（SELECT → INSERT），确保并发场景下不会创建重复订阅。</p>
     *
     * @param tenantId       租户ID
     * @param kbId           知识库ID
     * @param kbCode         知识库编码（冗余字段，便于查询）
     * @param subscriberType 订阅者类型（USER / AGENT）
     * @param subscriberId   订阅者ID（用户ID或智能体ID）
     */
    @Transactional(rollbackFor = Exception.class)
    public void subscribe(Long tenantId, Long kbId, String kbCode,
                          SubscriberType subscriberType, Long subscriberId) {
        // 1. 校验知识库存在
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "知识库不存在: " + kbId);
        }

        // 订阅校验收口——非作者仅可订阅 PUBLISHED 知识库
        // 作者引用自己的草稿/审核中库由 isSubscribed/batchQuerySubscribedKbIds 的
        // "作者自动订阅"逻辑覆盖，无需显式创建订阅记录
        boolean isAuthor = kb.getAuthorUserId() != null && kb.getAuthorUserId().equals(subscriberId);
        if (!isAuthor && kb.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅可订阅已发布知识库，当前状态: " + kb.getLifeStatus());
        }

        // 2. 检查是否已有 ACTIVE 订阅（MyBatis-Plus 逻辑删除自动过滤 deleted=1 的记录）
        KbSubscription existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<KbSubscription>()
                        .eq(KbSubscription::getTenantId, tenantId)
                        .eq(KbSubscription::getKbId, kbId)
                        .eq(KbSubscription::getSubscriberType, subscriberType)
                        .eq(KbSubscription::getSubscriberId, subscriberId)
                        .last("LIMIT 1"));

        if (existing != null) {
            log.info("知识库已订阅，跳过重复创建: kbId={}, subscriberType={}, subscriberId={}",
                    kbId, subscriberType, subscriberId);
            return;
        }

        // 3. 尝试恢复软删除的订阅记录（取消订阅后重新订阅场景）
        // 唯一索引 uk_kb_sub 不区分 deleted，软删除记录仍占用索引，需恢复而非插入
        int restored = subscriptionMapper.restoreLogicDeleted(kbId, subscriberType.name(), subscriberId,
                subscriberId, LocalDateTime.now());
        if (restored > 0) {
            refreshSubsCount(kbId);
            log.info("恢复已取消的订阅: kbId={}, subscriberType={}, subscriberId={}",
                    kbId, subscriberType, subscriberId);
            return;
        }

        // 4. 创建新订阅
        KbSubscription subscription = KbSubscription.builder()
                .tenantId(tenantId)
                .kbId(kbId)
                .kbCode(kbCode != null ? kbCode : kb.getKbCode())
                .subscriberType(subscriberType)
                .subscriberId(subscriberId)
                .createBy(subscriberId)
                .createTime(LocalDateTime.now())
                .deleted(0)
                .build();

        subscriptionMapper.insert(subscription);

        // 5. 聚合更新 subs_count（仅统计 ACTIVE 订阅）
        refreshSubsCount(kbId);

        log.info("知识库订阅成功: kbId={}, subscriberType={}, subscriberId={}, tenantId={}",
                kbId, subscriberType, subscriberId, tenantId);
    }

    /**
     * 取消订阅（软删除）。
     *
     * <p>将订阅关系标记为 deleted=1（MyBatis-Plus @TableLogic 自动处理），
     * 随后重新聚合知识库订阅数。</p>
     *
     * @param tenantId       租户ID
     * @param kbId           知识库ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void unsubscribe(Long tenantId, Long kbId,
                            SubscriberType subscriberType, Long subscriberId) {
        // 1. 查找 ACTIVE 订阅记录
        KbSubscription existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<KbSubscription>()
                        .eq(KbSubscription::getTenantId, tenantId)
                        .eq(KbSubscription::getKbId, kbId)
                        .eq(KbSubscription::getSubscriberType, subscriberType)
                        .eq(KbSubscription::getSubscriberId, subscriberId)
                        .last("LIMIT 1"));

        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订阅关系不存在");
        }

        // 2. 软删除（MyBatis-Plus @TableLogic 自动将 deleted 置为 1）
        subscriptionMapper.deleteById(existing.getId());

        // 3. 重新聚合 subs_count
        refreshSubsCount(kbId);

        log.info("知识库取消订阅: kbId={}, subscriberType={}, subscriberId={}, tenantId={}",
                kbId, subscriberType, subscriberId, tenantId);
    }

    /**
     * 查询单个订阅状态。
     *
     * <p>利用 MyBatis-Plus @TableLogic 自动过滤已删除记录，
     * 返回 count > 0 即表示存在 ACTIVE 订阅。</p>
     *
     * <h3>作者自动订阅规则</h3>
     * <p>若当前用户是知识库的创建者（authorUserId），则自动视为已订阅，
     * 无需显式创建订阅记录。</p>
     *
     * @param tenantId       租户ID
     * @param kbId           知识库ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     * @return 是否已订阅
     */
    public boolean isSubscribed(Long tenantId, Long kbId,
                                SubscriberType subscriberType, Long subscriberId) {
        // 作者自动视为已订阅
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb != null && kb.getAuthorUserId() != null && kb.getAuthorUserId().equals(subscriberId)) {
            return true;
        }

        Long count = subscriptionMapper.selectCount(
                new LambdaQueryWrapper<KbSubscription>()
                        .eq(KbSubscription::getTenantId, tenantId)
                        .eq(KbSubscription::getKbId, kbId)
                        .eq(KbSubscription::getSubscriberType, subscriberType)
                        .eq(KbSubscription::getSubscriberId, subscriberId));
        return count != null && count > 0;
    }

    /**
     * 批量查询指定订阅者已订阅的知识库ID集合。
     *
     * <p>通过 IN 查询一次获取所有匹配的订阅关系，在内存中过滤指定 kbIds。
     * 当 kbIds 为空时直接查询该订阅者的全部订阅记录。</p>
     *
     * <h3>作者自动订阅规则</h3>
     * <p>若当前用户是知识库的创建者（authorUserId），则自动视为已订阅。</p>
     *
     * @param tenantId       租户ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     * @param kbIds          待检查的知识库ID列表（为空则返回全部已订阅ID）
     * @return 已订阅的知识库ID集合（不可变）
     */
    public Set<Long> batchQuerySubscribedKbIds(Long tenantId,
                                                SubscriberType subscriberType,
                                                Long subscriberId,
                                                List<Long> kbIds) {
        // 1. 查询已订阅的知识库ID
        LambdaQueryWrapper<KbSubscription> wrapper = new LambdaQueryWrapper<KbSubscription>()
                .eq(KbSubscription::getTenantId, tenantId)
                .eq(KbSubscription::getSubscriberType, subscriberType)
                .eq(KbSubscription::getSubscriberId, subscriberId)
                .select(KbSubscription::getKbId);

        if (kbIds != null && !kbIds.isEmpty()) {
            wrapper.in(KbSubscription::getKbId, kbIds);
        }

        List<KbSubscription> subscriptions = subscriptionMapper.selectList(wrapper);
        Set<Long> subscribedIds = subscriptions.stream()
                .map(KbSubscription::getKbId)
                .collect(Collectors.toSet());

        // 2. 添加创建者自动订阅的知识库ID
        List<Long> authorKbIds = findAuthorKbIds(subscriberId, kbIds);
        subscribedIds.addAll(authorKbIds);

        return subscribedIds;
    }

    /**
     * 查找指定用户创建的知识库ID列表。
     *
     * @param authorUserId 用户ID
     * @param kbIds        待检查的知识库ID列表（为空则返回该用户创建的所有知识库）
     * @return 该用户创建的知识库ID列表
     */
    private List<Long> findAuthorKbIds(Long authorUserId, List<Long> kbIds) {
        if (authorUserId == null) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getAuthorUserId, authorUserId)
                .select(KnowledgeBase::getId);

        if (kbIds != null && !kbIds.isEmpty()) {
            wrapper.in(KnowledgeBase::getId, kbIds);
        }

        List<KnowledgeBase> authorKbs = knowledgeBaseMapper.selectList(wrapper);
        return authorKbs.stream()
                .map(KnowledgeBase::getId)
                .collect(Collectors.toList());
    }

    /**
     * 查询指定订阅者的全部已订阅知识库ID。
     *
     * @param tenantId       租户ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     * @return 已订阅的知识库ID集合
     */
    public Set<Long> listAllSubscribedKbIds(Long tenantId,
                                              SubscriberType subscriberType,
                                              Long subscriberId) {
        return batchQuerySubscribedKbIds(tenantId, subscriberType, subscriberId, null);
    }

    /**
     * 刷新指定知识库的订阅数（聚合查询）。
     *
     * <p>统计 ACTIVE 订阅（MyBatis-Plus @TableLogic 自动过滤 deleted=1），
     * 更新 res_knowledge_base.subs_count 字段。</p>
     *
     * @param kbId 知识库ID
     */
    private void refreshSubsCount(Long kbId) {
        Long count = subscriptionMapper.selectCount(
                new LambdaQueryWrapper<KbSubscription>()
                        .eq(KbSubscription::getKbId, kbId));
        int subsCount = count != null ? count.intValue() : 0;

        knowledgeBaseMapper.update(null, new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, kbId)
                .set(KnowledgeBase::getSubsCount, subsCount));

        log.debug("刷新知识库订阅数: kbId={}, subsCount={}", kbId, subsCount);
    }
}
