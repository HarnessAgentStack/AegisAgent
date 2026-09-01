package com.aegis.admin.service.resource;

import com.aegis.core.common.error.BusinessException;
import com.aegis.core.common.web.ResultCode;
import com.aegis.core.domain.resource.Skill;
import com.aegis.core.domain.resource.SkillSubscription;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.resource.SubscriberType;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.aegis.dal.mapper.resource.SkillSubscriptionMapper;
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
 * 技能订阅领域服务。
 *
 * <p>管理用户（USER）与智能体（AGENT）对技能的真实订阅关系，
 * 提供订阅/取消/查询能力，并实时维护 {@link Skill#getSubsCount()} 聚合字段。</p>
 *
 * @author wang.zhen
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillSubscriptionService {

    private final SkillSubscriptionMapper subscriptionMapper;
    private final SkillMapper skillMapper;
    private final ResourceChangePublisher resourceChangePublisher;

    /**
     * 订阅技能。
     *
     * <p>若已有 ACTIVE 订阅则直接返回，不重复创建。
     * 订阅成功后实时更新 res_skill.subs_count 聚合值。</p>
     *
     * @param tenantId          租户ID
     * @param skillId           技能ID
     * @param skillCode         技能编码（冗余字段，便于查询）
     * @param subscriberType    订阅者类型
     * @param subscriberId      订阅者ID
     * @param subscribedVersion  锁定版本，NULL 表示跟随 active_version
     */
    @Transactional(rollbackFor = Exception.class)
    public void subscribe(Long tenantId, Long skillId, String skillCode,
                          SubscriberType subscriberType, Long subscriberId,
                          String subscribedVersion) {
        // 订阅者身份校验，禁止匿名订阅
        if (subscriberId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "订阅失败：用户身份缺失（userId 为空）");
        }

        // 1. 校验技能存在
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "技能不存在: " + skillId);
        }

        // 跨租户订阅校验（技能必须属于订阅者租户）
        if (tenantId != null && skill.getTenantId() != null
                && !tenantId.equals(skill.getTenantId())) {
            log.warn("跨租户订阅被拒绝: skillId={}, skillTenantId={}, subscriberTenantId={}, subscriberId={}",
                    skillId, skill.getTenantId(), tenantId, subscriberId);
            throw new BusinessException(ResultCode.FORBIDDEN, "无权订阅其他租户的技能");
        }

        // 技能状态校验（仅已发布可订阅）
        if (skill.getLifeStatus() != AgentLifeStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "仅可订阅已发布技能，当前状态: " + skill.getLifeStatus());
        }

        // 防自订阅（USER 类型，作者订阅自己的技能直接拒绝，避免 subs_count 虚高）
        if (subscriberType == SubscriberType.USER
                && skill.getAuthorUserId() != null
                && skill.getAuthorUserId().equals(subscriberId)) {
            throw new BusinessException(ResultCode.CONFLICT, "不能订阅自己创建的技能");
        }

        // 2. 检查是否已有 ACTIVE 订阅（MyBatis-Plus 逻辑删除自动过滤 deleted=1）
        SkillSubscription existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<SkillSubscription>()
                        .eq(SkillSubscription::getTenantId, tenantId)
                        .eq(SkillSubscription::getSkillId, skillId)
                        .eq(SkillSubscription::getSubscriberType, subscriberType)
                        .eq(SkillSubscription::getSubscriberId, subscriberId)
                        .last("LIMIT 1"));

        if (existing != null) {
            log.info("技能已订阅，跳过重复创建: skillId={}, subscriberType={}, subscriberId={}",
                    skillId, subscriberType, subscriberId);
            return;
        }

        // 3. 创建新订阅
        SkillSubscription subscription = SkillSubscription.builder()
                .tenantId(tenantId)
                .skillId(skillId)
                .skillCode(skillCode != null ? skillCode : skill.getSkillCode())
                .subscriberType(subscriberType)
                .subscriberId(subscriberId)
                .subscribedVersion(subscribedVersion)
                .createBy(subscriberId)
                .createTime(LocalDateTime.now())
                .deleted(0)
                .build();

        subscriptionMapper.insert(subscription);

        // 4. 聚合更新 subs_count
        refreshSubsCount(skillId);

        log.info("技能订阅成功: skillId={}, subscriberType={}, subscriberId={}, tenantId={}",
                skillId, subscriberType, subscriberId, tenantId);

        resourceChangePublisher.publishSkillSubscriptionChanged(tenantId, subscriberId, "SUBSCRIBE");
    }

    /**
     * 取消订阅（软删除）。
     *
     * <p>将订阅关系标记为 deleted=1（MyBatis-Plus @TableLogic 自动处理），
     * 随后重新聚合技能订阅数。</p>
     *
     * @param tenantId       租户ID
     * @param skillId        技能ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void unsubscribe(Long tenantId, Long skillId,
                            SubscriberType subscriberType, Long subscriberId) {
        // 1. 查找 ACTIVE 订阅记录
        SkillSubscription existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<SkillSubscription>()
                        .eq(SkillSubscription::getTenantId, tenantId)
                        .eq(SkillSubscription::getSkillId, skillId)
                        .eq(SkillSubscription::getSubscriberType, subscriberType)
                        .eq(SkillSubscription::getSubscriberId, subscriberId)
                        .last("LIMIT 1"));

        if (existing == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订阅关系不存在");
        }

        // 2. 软删除
        subscriptionMapper.deleteById(existing.getId());

        // 3. 重新聚合 subs_count
        refreshSubsCount(skillId);

        log.info("技能取消订阅: skillId={}, subscriberType={}, subscriberId={}, tenantId={}",
                skillId, subscriberType, subscriberId, tenantId);

        resourceChangePublisher.publishSkillSubscriptionChanged(tenantId, subscriberId, "UNSUBSCRIBE");
    }

    /**
     * 查询单个订阅状态。
     *
     * <p>利用 MyBatis-Plus @TableLogic 自动过滤已删除记录，
     * 返回 count > 0 即表示存在 ACTIVE 订阅。</p>
     *
     * @param tenantId       租户ID
     * @param skillId        技能ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     * @return 是否已订阅
     */
    public boolean isSubscribed(Long tenantId, Long skillId,
                                SubscriberType subscriberType, Long subscriberId) {
        Long count = subscriptionMapper.selectCount(
                new LambdaQueryWrapper<SkillSubscription>()
                        .eq(SkillSubscription::getTenantId, tenantId)
                        .eq(SkillSubscription::getSkillId, skillId)
                        .eq(SkillSubscription::getSubscriberType, subscriberType)
                        .eq(SkillSubscription::getSubscriberId, subscriberId));
        return count != null && count > 0;
    }

    /**
     * 批量查询指定订阅者已订阅的技能ID集合。
     *
     * @param tenantId       租户ID
     * @param subscriberType 订阅者类型
     * @param subscriberId   订阅者ID
     * @param skillIds       待检查的技能ID列表（为空则返回全部已订阅ID）
     * @return 已订阅的技能ID集合
     */
    public Set<Long> batchQuerySubscribedSkillIds(Long tenantId,
                                                   SubscriberType subscriberType,
                                                   Long subscriberId,
                                                   List<Long> skillIds) {
        LambdaQueryWrapper<SkillSubscription> wrapper = new LambdaQueryWrapper<SkillSubscription>()
                .eq(SkillSubscription::getTenantId, tenantId)
                .eq(SkillSubscription::getSubscriberType, subscriberType)
                .eq(SkillSubscription::getSubscriberId, subscriberId)
                .select(SkillSubscription::getSkillId);

        if (skillIds != null && !skillIds.isEmpty()) {
            wrapper.in(SkillSubscription::getSkillId, skillIds);
        }

        List<SkillSubscription> subscriptions = subscriptionMapper.selectList(wrapper);
        if (subscriptions.isEmpty()) {
            return Collections.emptySet();
        }

        return subscriptions.stream()
                .map(SkillSubscription::getSkillId)
                .collect(Collectors.toSet());
    }

    /**
     * 查询指定订阅者的全部已订阅技能ID。
     */
    public Set<Long> listAllSubscribedSkillIds(Long tenantId,
                                                 SubscriberType subscriberType,
                                                 Long subscriberId) {
        return batchQuerySubscribedSkillIds(tenantId, subscriberType, subscriberId, null);
    }

    /**
     * 刷新指定技能的订阅数（聚合查询）。
     *
     * <p>统计 ACTIVE 订阅（MyBatis-Plus @TableLogic 自动过滤 deleted=1），
     * 更新 res_skill.subs_count 字段。</p>
     *
     * @param skillId 技能ID
     */
    private void refreshSubsCount(Long skillId) {
        Long count = subscriptionMapper.selectCount(
                new LambdaQueryWrapper<SkillSubscription>()
                        .eq(SkillSubscription::getSkillId, skillId));
        int subsCount = count != null ? count.intValue() : 0;

        skillMapper.update(null, new LambdaUpdateWrapper<Skill>()
                .eq(Skill::getId, skillId)
                .set(Skill::getSubsCount, subsCount));

        log.debug("刷新技能订阅数: skillId={}, subsCount={}", skillId, subsCount);
    }
}