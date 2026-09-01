package com.aegis.core.domain.evaluation;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.eval.EvalTriggerType;
import com.aegis.core.enums.eval.EvalStatus;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评测任务实体
 *
 * <p>评测任务（EvalTask）记录智能体评测执行的完整过程与结果，包括触发方式、执行状态、
 * 通过率、准确率、延迟与 token 消耗等，是智能体质量评估的核心数据。</p>
 *
 * <h3>评测流程</h3>
 * <ul>
 *     <li>触发：triggerType 标识触发方式（手动/自动/CI）</li>
 *     <li>执行：按 suiteId 关联的测试套件逐条执行 TestCase</li>
 *     <li>统计：totalCount 与 passedCount 聚合执行结果，accuracy 计算准确率</li>
 *     <li>报告：avgLatencyMs 与 tokenUsed 记录资源消耗，支撑成本评估</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，评测任务带 tenantId 隔离；
 * agentId 与 agentVersion 标识被评测的智能体版本，保证评测可复现。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see TestSuite
 * @see TestCase
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("eval_task")
public class EvalTask extends TenantEntity {
    /** 任务唯一标识，UUID 字符串，用于全链路追踪 */
    private String taskId;
    /** 被评测智能体 ID，关联 agent_def.id */
    private Long agentId;
    /** 智能体版本，评测时的智能体版本号，保证评测可复现 */
    private String agentVersion;
    /** 测试套件 ID，关联 test_suite.id，评测使用的测试集 */
    private Long suiteId;
    /** 触发类型：{@link EvalTriggerType#PRE_RELEASE}（版本发布前）/ {@link EvalTriggerType#MANUAL}（手动）/ {@link EvalTriggerType#SCHEDULED}（定时回归） */
    private EvalTriggerType triggerType;
    /** 任务状态：{@link EvalStatus#COMPLETED}（已完成）/ {@link EvalStatus#IN_PROGRESS}（进行中）/ {@link EvalStatus#QUEUED}（排队中） */
    private EvalStatus status;
    /** 测试用例总数，本次评测执行的 TestCase 总数 */
    private Integer totalCount;
    /** 通过数，评测通过的 TestCase 数量 */
    private Integer passedCount;
    /** 准确率，0-1 之间，passedCount / totalCount，综合评测质量指标 */
    private BigDecimal accuracy;
    /** 平均延迟，单位毫秒，所有测试用例的平均响应时间 */
    private Integer avgLatencyMs;
    /** Token 消耗，本次评测累计消耗的 token 总量 */
    private Long tokenUsed;
    /** 开始时间，评测任务开始执行的时间 */
    private LocalDateTime startTime;
    /** 结束时间，评测任务执行完成的时间 */
    private LocalDateTime endTime;
}