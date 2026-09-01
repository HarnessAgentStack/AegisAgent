package com.aegis.core.dto.tenant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租户用量统计视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantUsageVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用量记录ID */
    private Long id;

    /** 租户ID */
    private Long tenantId;

    /** 当前智能体数量 */
    private Integer agentCount;

    /** 当前资源数量 */
    private Integer resourceCount;

    /** 当前并发会话数 */
    private Integer concurrentSessionCount;

    /** 今日已用 Token 数 */
    private Long tokenUsedToday;

    /** 本月已用 Token 数 */
    private Long tokenUsedThisMonth;

    /** 当前沙箱占用数 */
    private Integer sandboxUsed;

    /** 已用存储容量（GB） */
    private BigDecimal storageUsedGb;

    /** 统计日期 */
    private LocalDate statDate;

    /** 创建时间 */
    private LocalDateTime createTime;
}
