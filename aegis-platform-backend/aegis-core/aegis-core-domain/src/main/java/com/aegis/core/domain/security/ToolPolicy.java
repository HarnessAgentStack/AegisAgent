package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.resource.ToolType;
import com.aegis.core.enums.resource.ToolPolicyAction;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具策略实体
 *
 * <p>工具策略（ToolPolicy）定义按工具类型与安全等级的工具访问控制规则，
 * 控制智能体可调用哪些类型与安全等级的工具，防止越权调用高风险工具。</p>
 *
 * <h3>策略机制</h3>
 * <ul>
 *     <li>工具类型：toolType 限定策略适用的工具类型</li>
 *     <li>安全等级：securityLevel 限定工具安全等级阈值</li>
 *     <li>处理动作：action 定义超限工具的处理方式（允许/拒绝/审批）</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，工具策略带 tenantId 隔离；
 * 各租户可根据安全要求自定义工具访问策略。</p>
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
@TableName("sec_tool_policy")
public class ToolPolicy extends TenantEntity {
    /** 工具类型：{@link ToolType#READONLY}（只读查询）/ {@link ToolType#INTERNAL_API}（内部接口）/ {@link ToolType#WRITE}（写入）/ {@link ToolType#EXTERNAL_NETWORK}（外网访问）/ {@link ToolType#CODE_EXEC}（代码执行）/ {@link ToolType#HIGH_RISK}（高风险），策略适用的工具类型 */
    private ToolType toolType;
    /** 安全等级阈值，1-4 对应 L1-L4，控制该类型工具允许的最高安全等级 */
    private Integer securityLevel;
    /** 处理动作：{@link ToolPolicyAction#ALLOW}（允许）/ {@link ToolPolicyAction#APPROVE}（需审批）/ {@link ToolPolicyAction#REJECT}（拒绝） */
    private ToolPolicyAction action;
    /** 策略描述，长度不超过 512，说明策略目的与适用场景 */
    private String description;
    /** 是否启用，true 生效，false 暂停策略 */
    private Boolean enabled;
}