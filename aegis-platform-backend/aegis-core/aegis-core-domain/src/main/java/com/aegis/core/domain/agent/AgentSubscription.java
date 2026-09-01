package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.SubscriptionStatus;
import com.aegis.core.enums.common.Visibility;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * 智能体订阅关系实体。
 *
 * <p>采用"可见即可订阅"设计：智能体发布时通过 {@link Visibility} 控制可见范围，
 * 可见范围内的用户直接订阅，无需审批。订阅状态仅在 ACTIVE/UNSUBSCRIBED 间流转。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>同一用户同一智能体仅一条订阅记录</li>
 *   <li>订阅即时生效，无审批环节</li>
 *   <li>退订后可重新订阅</li>
 * </ul>
 *
 * @author wang.zhen
 * @see AgentDef
 * @see Visibility
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_subscription")
public class AgentSubscription extends TenantEntity {

    /** 智能体ID，关联AgentDef主键 */
    private Long agentId;

    /** 订阅用户ID，关联User主键 */
    private Long userId;

    /** 订阅状态：{@link SubscriptionStatus#ACTIVE}（已订阅）、{@link SubscriptionStatus#UNSUBSCRIBED}（已退订） */
    private SubscriptionStatus status;

    /** 订阅时间，发起订阅时记录 */
    private LocalDateTime subscribeTime;

    /** 退订时间，用户退订时记录 */
    private LocalDateTime unsubscribeTime;
}
