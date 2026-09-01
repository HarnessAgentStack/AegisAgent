package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.MemoryType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 智能体记忆实体，跨会话持久化用户上下文。
 *
 * <p>区分用户画像/任务摘要/关键事实三类，在会话中自动提取并持久化。
 * 跨会话检索复用，提升交互个性化与上下文连续性。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>agentId + userId + memoryType + memoryKey 唯一</li>
 *   <li>用户画像每用户每智能体一条，持续更新</li>
 *   <li>关键事实按memoryKey去重，最新值覆盖旧值</li>
 * </ul>
 *
 * @author wang.zhen
 * @see AgentDef
 * @see com.aegis.core.enums.agent.MemoryType
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_memory")
public class AgentMemory extends TenantEntity {

    /** 智能体ID，关联AgentDef主键 */
    private Long agentId;

    /** 用户ID，关联User主键，记忆按用户隔离 */
    private Long userId;

    /** 记忆类型：{@link MemoryType#USER_PROFILE}（用户画像）、{@link MemoryType#TASK_SUMMARY}（任务摘要）、{@link MemoryType#KEY_FACT}（关键事实） */
    private MemoryType memoryType;

    /** 记忆键，结构化标识，如 profile.role / fact.project_name */
    private String memoryKey;

    /** 记忆值，JSON格式存储结构化内容 */
    private String memoryValue;

    /** 是否用户可编辑：true-用户可修改 false-系统管理不可手动改 */
    private Boolean editable;

    /** 记忆来源：auto-自动提取 manual-手动录入 import-外部导入 */
    private String source;
}