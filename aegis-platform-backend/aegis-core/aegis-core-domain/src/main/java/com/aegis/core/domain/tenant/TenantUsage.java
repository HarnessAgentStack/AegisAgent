package com.aegis.core.domain.tenant;

import com.aegis.core.base.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 租户用量统计实体。
 *
 * <p>实时记录租户在六大维度的配额消耗，支撑配额校验与计量计费。
 * 每日生成快照记录，按statDate分区存储，用于趋势分析与账单生成。
 * 继承 BaseEntity（平台级统计，非租户隔离）。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>每日每租户一条快照，statDate + tenantId 唯一</li>
 *   <li>当日用量实时更新，历史快照只读不可变</li>
 *   <li>用量值与TenantQuota配额对比，超限即触发配额拦截</li>
 * </ul>
 *
 * @author wang.zhen
 * @see Tenant
 * @see TenantQuota
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ten_usage")
public class TenantUsage extends BaseEntity {

    /** 租户ID，关联Tenant主键 */
    private Long tenantId;

    /** 当前智能体数量，实时统计 */
    private Integer agentCount;

    /** 当前资源数量，实时统计 */
    private Integer resourceCount;

    /** 当前并发会话数，实时统计 */
    private Integer concurrentSessionCount;

    /** 今日已用Token数，自然日0点重置 */
    private Long tokenUsedToday;

    /** 本月已用Token数，自然月1号重置 */
    private Long tokenUsedThisMonth;

    /** 当前沙箱占用数，含占用与空闲 */
    private Integer sandboxUsed;

    /** 已用存储容量（GB），精确到小数点后两位 */
    private BigDecimal storageUsedGb;

    /** 统计日期，每日一条快照，格式 yyyy-MM-dd */
    private LocalDate statDate;
}