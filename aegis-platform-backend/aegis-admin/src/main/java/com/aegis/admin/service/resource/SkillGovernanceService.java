package com.aegis.admin.service.resource;

import com.aegis.core.domain.resource.Skill;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.dal.mapper.resource.SkillMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 技能治理循环领域服务。
 *
 * <p>负责技能的健康度计算、活跃度检测与 STALE 标记、
 * 订阅数据一致性校验等治理能力，通过定时任务 + 手动触发两种方式执行。</p>
 *
 * @author wang.zhen
 * @see Skill
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillGovernanceService {

    private final SkillMapper skillMapper;

    /** STALE 阈值天数：超过 N 天未调用的技能标记为低活跃 */
    private static final int STALE_DAYS_THRESHOLD = 30;

    /**
     * 手动触发指定技能的健康分重算（治理控制台调用）。
     *
     * @param skillId 技能ID
     * @return 重算后的健康分
     */
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal recalculateHealthScore(Long skillId) {
        Skill skill = skillMapper.selectById(skillId);
        if (skill == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal score = calculateHealthScore(skill);
        skillMapper.update(null,
                new LambdaUpdateWrapper<Skill>()
                        .eq(Skill::getId, skillId)
                        .set(Skill::getHealthScore, score));
        log.info("Skill health score recalculated: skillId={}, score={}", skillId, score);
        return score;
    }

    /**
     * 全量健康分重算（治理定时任务）。
     *
     * <p>每天凌晨 3 点执行，重算所有非删除技能的健康分。</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void recalculateAllHealthScores() {
        log.info("[Governance] Starting full health score recalculation...");
        long start = System.currentTimeMillis();

        List<Skill> allSkills = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .select(Skill::getId, Skill::getSubsCount, Skill::getLifeStatus,
                                Skill::getLastInvokedAt, Skill::getCreateTime, Skill::getHealthScore));

        int updated = 0;
        for (Skill skill : allSkills) {
            BigDecimal newScore = calculateHealthScore(skill);
            // 仅在分数变化时更新，减少写操作
            if (newScore.compareTo(skill.getHealthScore() != null ? skill.getHealthScore() : BigDecimal.ZERO) != 0) {
                skillMapper.update(null,
                        new LambdaUpdateWrapper<Skill>()
                                .eq(Skill::getId, skill.getId())
                                .set(Skill::getHealthScore, newScore));
                updated++;
            }
        }

        log.info("[Governance] Health score recalculation complete: total={}, updated={}, cost={}ms",
                allSkills.size(), updated, System.currentTimeMillis() - start);
    }

    /**
     * STALE 检测（治理定时任务）。
     *
     * <p>每天凌晨 4 点执行，标记超过 30 天未调用的 PUBLISHED 技能为低活跃。
     * 当前仅更新健康分（降低评分），后续可扩展独立 STALE 状态。</p>
     */
    @Scheduled(cron = "0 0 4 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void detectStaleSkills() {
        log.info("[Governance] Starting STALE skill detection...");
        LocalDateTime threshold = LocalDateTime.now().minusDays(STALE_DAYS_THRESHOLD);

        List<Skill> staleCandidates = skillMapper.selectList(
                new LambdaQueryWrapper<Skill>()
                        .eq(Skill::getLifeStatus, AgentLifeStatus.PUBLISHED)
                        .and(w -> w.isNull(Skill::getLastInvokedAt)
                                .or().lt(Skill::getLastInvokedAt, threshold)));

        if (staleCandidates.isEmpty()) {
            log.info("[Governance] No STALE skills detected");
            return;
        }

        // STALE 技能健康分最低 20 分（仅作为活跃度警示，不强制归档）
        for (Skill skill : staleCandidates) {
            BigDecimal current = skill.getHealthScore() != null ? skill.getHealthScore() : BigDecimal.ZERO;
            BigDecimal minScore = new BigDecimal("20.00");
            if (current.compareTo(minScore) > 0) {
                skillMapper.update(null,
                        new LambdaUpdateWrapper<Skill>()
                                .eq(Skill::getId, skill.getId())
                                .set(Skill::getHealthScore, minScore));
            }
        }

        log.info("[Governance] STALE detection complete: {} skills marked low-activity", staleCandidates.size());
    }

    /**
     * 计算技能健康分（0-100）。
     *
     * <p>评分维度：</p>
     * <ul>
     *   <li>状态分（30分）：PUBLISHED = 30，REVIEWING = 20，DRAFT = 10，ARCHIVED/REJECTED = 0</li>
     *   <li>订阅分（30分）：0 订阅 = 0 分，100+ 订阅 = 30 分，线性插值</li>
     *   <li>活跃分（40分）：7 天内调用 = 40 分，30 天内 = 20 分，30 天以上 = 5 分</li>
     * </ul>
     *
     * @param skill 技能实体
     * @return 健康分（0-100）
     */
    private BigDecimal calculateHealthScore(Skill skill) {
        double score = 0.0;

        // 1. 状态分（30 分）
        AgentLifeStatus status = skill.getLifeStatus();
        if (status == AgentLifeStatus.PUBLISHED) score += 30;
        else if (status == AgentLifeStatus.REVIEWING) score += 20;
        else if (status == AgentLifeStatus.DRAFT) score += 10;
        // ARCHIVED / REJECTED = 0

        // 2. 订阅分（30 分）：0 → 0，100+ → 30，线性插值
        int subs = skill.getSubsCount() != null ? skill.getSubsCount() : 0;
        double subsScore = Math.min(subs / 100.0 * 30.0, 30.0);
        score += subsScore;

        // 3. 活跃分（40 分）：基于最近调用时间
        LocalDateTime lastInvoked = skill.getLastInvokedAt();
        if (lastInvoked == null) {
            // 从未调用：根据创建时间判断，如果是新创建的给点基础分
            score += 10;
        } else {
            long daysSince = java.time.temporal.ChronoUnit.DAYS.between(lastInvoked, LocalDateTime.now());
            if (daysSince <= 7) score += 40;
            else if (daysSince <= 30) score += 20;
            else score += 5;
        }

        // 钳制到 0-100
        score = Math.max(0.0, Math.min(100.0, score));
        return BigDecimal.valueOf(score).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
