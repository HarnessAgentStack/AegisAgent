package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.MemoryStrategy;
import com.aegis.core.enums.model.ModelTier;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

/**
 * 智能体配置实体，承载提示词与模型参数。
 *
 * <p>按版本管理，每次发布生成新版本配置快照，会话锁定具体版本保证一致性。
 * 安全与治理由 {@link AgentDef#governanceTier} 治理档位统一驱动，本实体不再持有
 * 护栏级别 / 规划模式 / 对外 API 发布等已上移或移除的字段。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>同一智能体可有多个版本配置，version唯一</li>
 *   <li>会话创建时锁定版本，运行中不可切换</li>
 *   <li>enabledTools为工具ID列表的JSON数组</li>
 * </ul>
 *
 *  @author wang.zhen
 * @see AgentDef
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_config")
public class AgentConfig extends TenantEntity {

    /** 智能体ID，关联AgentDef主键 */
    private Long agentId;

    /** 配置版本号，与AgentDef.version对应 */
    private String version;

    /** 系统提示词，定义智能体角色与行为约束 */
    private String systemPrompt;

    /** 模型档位：{@link ModelTier#LIGHT}（轻量）、{@link ModelTier#STANDARD}（标准）、{@link ModelTier#STRONG}（强力） */
    private ModelTier modelTier;

    /** 温度参数，0-2，值越高输出越发散，0为确定性输出 */
    private BigDecimal temperature;

    /** 记忆策略：{@link MemoryStrategy#SESSION_LEVEL}（会话级）、{@link MemoryStrategy#LONG_TERM}（长期，用户归档），控制记忆写入与检索 */
    private MemoryStrategy memoryStrategy;

    /** 最大对话轮数，超限后提示新建会话 */
    private Integer maxTurns;

    /** 启用工具ID列表，JSON数组格式（如 ["t1","t2"]），空表示不启用任何工具 */
    private String enabledTools;
}
