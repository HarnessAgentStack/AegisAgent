package com.aegis.core.domain.model;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.model.RateLimitAction;
import com.aegis.core.enums.model.RateLimitScope;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 模型限流策略实体
 *
 * <p>模型限流策略（ModelRateLimit）定义租户内按对象（用户/部门/智能体）与层级的
 * QPS 限制，防止单一对象过度消耗模型资源，保障平台整体稳定性。</p>
 *
 * <h3>限流机制</h3>
 * <ul>
 *     <li>分层限流：lightQps / standardQps / strongQps 分别限制各层级模型速率</li>
 *     <li>总量限制：totalQps 限制该对象所有模型总速率，防止跨层级叠加</li>
 *     <li>实时统计：usedQps 实时记录当前已用速率，由系统监控更新</li>
 *     <li>超限动作：action 定义超限时的处理策略（拒绝/排队/降级）</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，各租户独立配置限流策略；
 * scope 与 scopeTargetId 灵活支持用户、部门、智能体等多维度限流。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("model_rate_limit")
public class ModelRateLimit extends TenantEntity {
    /** 限流作用域：{@link RateLimitScope#PLATFORM}（全平台）、{@link RateLimitScope#DEPT}（部门）、{@link RateLimitScope#USER}（个人），标识限流对象类型 */
    private RateLimitScope scope;
    /** 限流对象 ID，依据 scope 关联对应表主键，如 user.id、department.id */
    private Long scopeTargetId;
    /** 轻量模型 QPS 限制，light 层级最大请求速率 */
    private Integer lightQps;
    /** 标准模型 QPS 限制，standard 层级最大请求速率 */
    private Integer standardQps;
    /** 强力模型 QPS 限制，strong 层级最大请求速率 */
    private Integer strongQps;
    /** 总 QPS 限制，所有层级模型合计最大请求速率 */
    private Integer totalQps;
    /** 已用 QPS，当前实时请求速率，由系统监控统计更新 */
    private Integer usedQps;
    /** 超限动作：{@link RateLimitAction#ALERT}（告警）、{@link RateLimitAction#LIMIT}（限流）、{@link RateLimitAction#PASS}（放行），超限时的处理策略 */
    private RateLimitAction action;
}