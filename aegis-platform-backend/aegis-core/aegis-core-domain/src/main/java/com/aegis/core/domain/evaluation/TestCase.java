package com.aegis.core.domain.evaluation;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.InputType;
import com.aegis.core.enums.eval.EvalMethod;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 测试用例实体
 *
 * <p>测试用例（TestCase）是智能体评测的最小执行单元，定义输入内容、期望输出与评估方法，
 * 由 EvalTask 逐条执行并比对结果，支撑智能体质量的量化评估。</p>
 *
 * <h3>核心要素</h3>
 * <ul>
 *     <li>输入：inputType 标识输入类型，inputContent 定义具体输入内容</li>
 *     <li>期望：expectedOutput 定义期望输出，expectedTool 定义期望调用的工具</li>
 *     <li>评估：evalMethod 定义结果比对方法，如精确匹配、语义相似、包含判断</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，测试用例带 tenantId 隔离；
 * suiteId 关联所属测试套件，一个套件可包含多个测试用例。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see TestSuite
 * @see EvalTask
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("eval_test_case")
public class TestCase extends TenantEntity {
    /** 所属测试套件 ID，关联 test_suite.id */
    private Long suiteId;
    /** 用例唯一标识，套件内唯一，用于用例检索与引用 */
    private String caseId;
    /** 输入类型：{@link InputType#TEXT}（文本）/ {@link InputType#FILE}（文件） */
    private InputType inputType;
    /** 输入内容，依据 inputType 不同结构不同，如纯文本或 JSON 多模态描述 */
    private String inputContent;
    /** 期望输出，用于与实际输出比对，可以是文本或 JSON 结构 */
    private String expectedOutput;
    /** 期望工具，JSON 数组字符串，期望智能体调用的工具列表，如 ["tool_code_1"] */
    private String expectedTool;
    /** 评估方法：{@link EvalMethod#EXACT_MATCH}（精确匹配）/ {@link EvalMethod#KEYWORD}（关键词包含）/ {@link EvalMethod#LLM_SCORE}（LLM评分） */
    private EvalMethod evalMethod;
}