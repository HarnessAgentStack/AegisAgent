package com.aegis.core.domain.agent;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.agent.AgentType;
import com.aegis.core.enums.agent.GovernanceTier;
import com.aegis.core.enums.common.Visibility;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 智能体定义实体，智能体域核心聚合根。
 *
 * <p>以「类型」({@link AgentType}) 作为唯一主判别器，区分通用智能体（平台预置单例）、
 * 应用智能体（业务人员创建）与系统智能体（面向业务系统）。安全与治理以单一
 * {@link GovernanceTier} 治理档位表达。
 * 继承 TenantEntity，按租户隔离。
 *
 * <h3>设计约束</h3>
 * <ul>
 *   <li>智能体编码（agentCode）租户内唯一</li>
 *   <li>生命周期状态受审核流程驱动：DRAFT → REVIEWING → PUBLISHED → ARCHIVED（REJECTED 可重新提交）</li>
 *   <li>通用智能体（UNIVERSAL）每租户唯一，由平台预置与维护默认配置</li>
 *   <li>治理档位（governanceTier）决定沙箱隔离、工具管控、内容过滤、人审与审计粒度</li>
 * </ul>
 *
 *  @author wang.zhen
 * @see AgentConfig
 * @see AgentBinding
 * @see AgentSubscription
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_def")
public class AgentDef extends TenantEntity {

    /** 智能体编码，租户内唯一，创建后不可修改 */
    private String agentCode;

    /** 智能体名称，展示用 */
    private String agentName;

    /** 智能体类型：{@link AgentType#UNIVERSAL}（通用智能体，平台唯一）/ {@link AgentType#APPLICATION}（应用智能体）/ {@link AgentType#SYSTEM}（系统智能体） */
    private AgentType agentType;

    /** 智能体图标URL */
    private String icon;

    /** 主题色，十六进制色值 */
    private String color;

    /** 智能体描述，介绍智能体能力与适用场景 */
    private String description;

    /** 智能体分类，市场检索用 */
    private String category;

    /**
     * 治理档位：{@link GovernanceTier#STANDARD}（标准）/ {@link GovernanceTier#ENHANCED}（增强）/ {@link GovernanceTier#STRICT}（严格）。
     */
    private GovernanceTier governanceTier;

    /** 生命周期状态：{@link AgentLifeStatus#DRAFT}→{@link AgentLifeStatus#REVIEWING}→{@link AgentLifeStatus#PUBLISHED}→{@link AgentLifeStatus#ARCHIVED} */
    private AgentLifeStatus lifeStatus;

    /** 当前版本号，每次发布递增 */
    private String version;

    /** 创建者用户ID，关联User主键。序列化为字符串防止前端精度丢失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorUserId;

    /** 创建者部门ID，关联Department主键。序列化为字符串防止前端精度丢失。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long authorDeptId;

    /** 订阅数，缓存统计，用于客观排序（发布时间 / 使用量） */
    private Integer subsCount;

    /** 发布时间，审核通过时记录 */
    private LocalDateTime publishedTime;

    /** 发布可见范围：当前仅支持 {@link com.aegis.core.enums.common.Visibility#TENANT}（本租户可见，默认）。 */
    private com.aegis.core.enums.common.Visibility visibility;

    /** 归档时间，下架时记录 */
    private LocalDateTime archivedTime;

    /** 乐观锁版本号（与业务版本号 version 区分） */
    @Version
    @com.baomidou.mybatisplus.annotation.TableField("lock_version")
    private Integer lockVersion;
}
