package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.HitlAction;
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
 * HITL 历史实体
 *
 * <p>HITL 历史（HitlHistory）记录每次人工审批的操作详情，包括审批人、动作、
 * 详情与时间，是审批流程审计与追溯的核心数据。</p>
 *
 * <h3>审批动作</h3>
 * <ul>
 *     <li>APPROVE：通过，允许智能体继续执行</li>
 *     <li>REJECT：驳回，终止智能体执行</li>
 *     <li>MODIFY：修改，审批人调整参数后通过</li>
 *     <li>TIMEOUT：超时，按节点 timeoutStrategy 自动处理</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，审批历史带 tenantId 隔离；
 * nodeId / agentId / sessionId 实现审批上下文关联。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 * @see HitlNode
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sec_hitl_history")
public class HitlHistory extends TenantEntity {
    /** HITL 节点 ID，关联 hitl_node.id，触发的审批节点 */
    private Long nodeId;
    /** 智能体 ID，关联 agent_def.id */
    private Long agentId;
    /** 会话 ID，关联 session.session_id，审批所在会话 */
    private String sessionId;
    /** 审批动作：{@link HitlAction#APPROVE}（通过）/ {@link HitlAction#REJECT}（拒绝）/ {@link HitlAction#MODIFY}（修改）/ {@link HitlAction#TIMEOUT}（超时） */
    private HitlAction action;
    /** 操作人用户 ID，关联 user.id，执行审批的用户 */
    private Long operatorUserId;
    /** 操作人姓名，冗余存储便于审计列表展示 */
    private String operatorName;
    /** 操作详情，JSON 字符串，记录审批意见、修改内容等 */
    private String detail;
    /** 发生时间，审批操作实际发生的时间 */
    private LocalDateTime occurTime;
}