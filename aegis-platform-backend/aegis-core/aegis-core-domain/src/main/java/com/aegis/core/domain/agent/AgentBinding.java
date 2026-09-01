package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.BindingType;
import com.aegis.core.enums.resource.ResourceType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 智能体资源绑定实体，关联SKILL/MCP/知识库/数据集。
 *
 * <p>区分固定绑定（创建者配置，版本锁定）与动态加载（运行时按用户权限加载）。
 * 通用智能体使用动态加载，应用智能体使用固定绑定。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>固定绑定锁定resourceVersion，资源更新不影响已绑定智能体</li>
 *   <li>动态绑定时resourceVersion为latest，运行时解析最新版本</li>
 *   <li>同一智能体同一资源不可重复绑定</li>
 * </ul>
 *
 * @author wang.zhen
 * @see AgentDef
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_binding")
public class AgentBinding extends TenantEntity {

    /** 智能体ID，关联AgentDef主键 */
    private Long agentId;

    /** 智能体版本号，绑定归属的版本 */
    private String agentVersion;

    /** 资源类型：{@link ResourceType#SKILL}/{@link ResourceType#KNOWLEDGE_BASE}/{@link ResourceType#MCP}/{@link ResourceType#TOOL}/{@link ResourceType#DATASET} */
    private ResourceType resourceType;

    /** 资源ID，关联具体资源主键 */
    private Long resourceId;

    /** 资源版本，固定绑定为具体版本号，动态绑定为latest */
    private String resourceVersion;

    /** 绑定类型：{@link BindingType#FIXED}（固定绑定，版本锁定）、{@link BindingType#DYNAMIC}（动态加载，运行时解析） */
    private BindingType bindingType;

    /** 是否启用：true-生效 false-停用，临时禁用不删除绑定 */
    private Boolean enabled;
}