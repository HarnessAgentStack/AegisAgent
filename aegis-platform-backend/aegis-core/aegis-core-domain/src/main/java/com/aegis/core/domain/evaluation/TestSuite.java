package com.aegis.core.domain.evaluation;

import com.aegis.core.base.TenantEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 测试套件实体
 *
 * <p>测试套件（TestSuite）是测试用例的集合，按智能体维度组织测试用例，
 * 支持版本管理与复用，为智能体评测提供标准化的测试集。</p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *     <li>用例组织：按智能体聚合测试用例，caseCount 统计用例数量</li>
 *     <li>版本管理：version 支持测试套件的版本演进与回溯</li>
 *     <li>复用共享：测试套件可被多次评测引用，保证评测一致性</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，测试套件带 tenantId 隔离；
 * agentId 标识套件服务的智能体，一个智能体可有多个测试套件。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see TestCase
 * @see EvalTask
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("eval_test_suite")
public class TestSuite extends TenantEntity {
    /** 套件名称，长度不超过 128，标识测试套件用途，如"客服智能体回归套件" */
    private String suiteName;
    /** 智能体 ID，关联 agent_def.id，套件服务的智能体 */
    private Long agentId;
    /** 用例数量，套件内测试用例总数，由系统自动统计 */
    private Integer caseCount;
    /** 版本号，语义化版本如 1.0.0，支持套件的版本演进与回溯 */
    private String version;
    /** 套件描述，长度不超过 512，说明套件覆盖场景与测试目标 */
    private String description;
    /** 更新时间，套件最近一次修改的时间 */
    private LocalDateTime updatedTime;
}