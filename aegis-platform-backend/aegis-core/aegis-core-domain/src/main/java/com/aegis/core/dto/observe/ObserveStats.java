package com.aegis.core.dto.observe;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 可观测统计结果。
 *
 * <p>包含链路总量、成功率、平均耗时、P95 耗时、总 Token 消耗
 * 以及失败原因分布等核心指标。</p>
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObserveStats implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 链路总数 */
    private Long totalTraces;

    /** 成功率（百分比，0~100） */
    private Double successRate;

    /** 平均耗时（毫秒） */
    private Double avgDurationMs;

    /** P95 耗时（毫秒） */
    private Double p95DurationMs;

    /** 总 Token 数 */
    private Long totalTokens;

    /** 失败原因分布（key: 错误信息, value: 出现次数） */
    private Map<String, Long> failureDistribution;
}