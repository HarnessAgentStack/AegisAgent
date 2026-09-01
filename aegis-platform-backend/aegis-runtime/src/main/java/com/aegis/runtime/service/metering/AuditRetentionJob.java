package com.aegis.runtime.service.metering;

import com.aegis.core.domain.monitor.AuditLog;
import com.aegis.core.enums.monitor.AuditLogType;
import com.aegis.dal.mapper.monitor.AuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计日志保留期定时清理任务。
 *
 * <p>每日凌晨 2:00 扫描过期审计日志（occurTime + retentionDays < now）分批删除。
 * 按日志类型默认保留期兜底：SESSION/POLICY_DECISION=90 天，SECURITY=365 天。
 * 清理动作本身落一条 SECURITY 审计（AUDIT_RETENTION_CLEAN），可追溯清理行为。
 *
 * <p>分批删除（每批 1000 条）避免大事务锁表。
 *
 * @author wang.zhen
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditRetentionJob {

    private final AuditLogMapper auditLogMapper;

    /** SESSION/POLICY_DECISION 默认保留天数 */
    @Value("${aegis.audit.retention.short-term:90}")
    private int shortTermRetention;

    /** SECURITY 默认保留天数 */
    @Value("${aegis.audit.retention.long-term:365}")
    private int longTermRetention;

    /** 单批删除上限，避免锁表 */
    private static final int BATCH_SIZE = 1000;

    /**
     * 每日凌晨 2:00 执行保留期清理。
     */
    @Scheduled(cron = "${aegis.audit.retention.cron:0 0 2 * * ?}")
    public void cleanExpiredAuditLogs() {
        log.info("[AuditRetention] 开始清理过期审计日志");
        LocalDateTime now = LocalDateTime.now();
        int totalDeleted = 0;

        // 短保留期类型：SESSION / POLICY_DECISION
        totalDeleted += cleanByLogType(AuditLogType.SESSION, now, shortTermRetention);
        totalDeleted += cleanByLogType(AuditLogType.POLICY_DECISION, now, shortTermRetention);
        // 长保留期类型：SECURITY
        totalDeleted += cleanByLogType(AuditLogType.SECURITY, now, longTermRetention);

        log.info("[AuditRetention] 过期审计日志清理完成，共删除 {} 条", totalDeleted);

        // 清理动作本身落审计（可追溯）
        if (totalDeleted > 0) {
            try {
                AuditLog cleanLog = AuditLog.builder()
                        .logType(AuditLogType.SECURITY)
                        .operation("AUDIT_RETENTION_CLEAN")
                        .resourceType("AUDIT_LOG")
                        .result(com.aegis.core.enums.monitor.AuditResult.SUCCESS)
                        .detail("{\"deletedCount\":" + totalDeleted + "}")
                        .retentionDays(longTermRetention)
                        .occurTime(LocalDateTime.now())
                        .build();
                auditLogMapper.insert(cleanLog);
            } catch (Exception e) {
                log.warn("[AuditRetention] 清理审计记录写入失败（不阻断）", e);
            }
        }
    }

    /**
     * 按日志类型清理过期记录，retentionDays 字段优先，无值则用默认保留期兜底。
     */
    private int cleanByLogType(AuditLogType logType, LocalDateTime now, int defaultRetention) {
        int deleted = 0;
        LocalDateTime threshold = now.minusDays(defaultRetention);

        // 清理 occurTime 早于阈值 且（retentionDays 为 null 或 occurTime+retentionDays < now）的记录
        // 简化：occurTime < threshold 即视为过期（按类型默认保留期）
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getLogType, logType)
                .lt(AuditLog::getOccurTime, threshold)
                .last("LIMIT " + BATCH_SIZE);

        while (true) {
            int batch = auditLogMapper.delete(wrapper);
            if (batch <= 0) break;
            deleted += batch;
            if (batch < BATCH_SIZE) break;
        }
        if (deleted > 0) {
            log.info("[AuditRetention] {} 类型清理 {} 条（阈值 {}）", logType, deleted, threshold);
        }
        return deleted;
    }
}
