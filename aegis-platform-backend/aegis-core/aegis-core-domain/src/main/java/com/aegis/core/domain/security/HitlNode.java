package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.HitlTimeoutStrategy;
import com.aegis.core.enums.security.HitlAction;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * HITL 节点实体
 *
 * <p>HITL（Human-In-The-Loop）节点定义智能体执行流程中需要人工介入审批的环节，
 * 当满足触发条件时暂停执行并提交审批，确保高风险操作有人工把关。</p>
 *
 * <h3>核心机制</h3>
 * <ul>
 *     <li>触发条件：triggerCondition 定义何时需要人工审批</li>
 *     <li>审批人：approverUserId 或 approverRole 指定审批人</li>
 *     <li>SLA 约束：slaHours 限制审批时限，超时按 timeoutStrategy 处理</li>
 *     <li>允许动作：allowedActions 定义审批人可执行的动作（通过/驳回/修改）</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，HITL 节点带 tenantId 隔离；
 * 每个智能体可配置多个 HITL 节点，覆盖不同风险场景。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see HitlHistory
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sec_hitl_node")
public class HitlNode extends TenantEntity {
    /** 智能体 ID，关联 agent_def.id，节点所属智能体 */
    private Long agentId;
    /** 节点名称，长度不超过 128，标识审批环节，如"高风险工具审批"、"数据导出审批" */
    private String nodeName;
    /** 触发条件，JSON 字符串，如 {"toolSecurityLevel":">=3","dataSensitivity":"CONFIDENTIAL"} */
    private String triggerCondition;
    /** 审批人用户 ID，关联 user.id，指定具体审批人 */
    private Long approverUserId;
    /** 审批角色，当 approverUserId 为空时按角色匹配审批人，如 SECURITY_OFFICER */
    private String approverRole;
    /** SLA 时限，单位小时，审批超时时间，取值范围 1-168 */
    private Integer slaHours;
    /** 超时策略：{@link HitlTimeoutStrategy#AUTO_APPROVE}（自动通过）/ {@link HitlTimeoutStrategy#AUTO_REJECT}（自动驳回）/ {@link HitlTimeoutStrategy#ESCALATE}（升级处理） */
    private HitlTimeoutStrategy timeoutStrategy;
    /** 允许的审批操作列表（JSON 数组，元素为 {@link HitlAction} 枚举名，如 ["APPROVE","REJECT","MODIFY"]） */
    private String allowedActions;
    /** 是否启用，true 生效，false 暂停节点 */
    private Boolean enabled;
}