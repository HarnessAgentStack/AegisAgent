package com.aegis.core.domain.resource;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillScope;
import com.aegis.core.enums.resource.SkillType;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import com.aegis.core.enums.common.Visibility;

/**
 * 技能资源实体
 *
 * <p>技能（Skill）是智能体执行特定任务的能力封装单元，承载工具编排、输入输出映射与安全等级管控。
 * 技能由租户内用户创建并维护，支持发布到资源中心供其他智能体订阅复用。</p>
 *
 * <h3>核心职责</h3>
 * <ul>
 *     <li>定义技能元信息（编码、名称、类型、分类、标签等）</li>
 *     <li>绑定工具集合并提供输入输出映射配置</li>
 *     <li>承载安全等级与生命周期状态，参与多租户隔离与权限控制</li>
 *     <li>记录订阅数与评分，支撑资源中心排序与推荐</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，通过 tenantId 字段实现租户级数据隔离；
 * 自用技能保存即生效，发布技能需经审核审批后方可被跨部门订阅。</p>
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
@TableName("res_skill")
public class Skill extends TenantEntity {
    /** 技能唯一编码，租户内唯一，由字母、数字、下划线组成，长度不超过 64 */
    private String skillCode;
    /** 技能展示名称，长度不超过 128，用于资源中心与智能体配置页展示 */
    private String skillName;
    /** 技能图标 URL，可选，用于资源中心视觉标识 */
    private String icon;
    /** 技能描述，长度不超过 512，向用户说明技能用途与适用场景 */
    private String description;
    /** 技能类型：{@link SkillType#ATOMIC}（原子技能）、{@link SkillType#COMPOSITE}（组合技能），标识技能执行方式 */
    private SkillType skillType;
    /** 技能分类：{@link SkillCategory}（数据处理/内容生成/集成对接/计算/检索），用于资源中心分组展示 */
    private SkillCategory category;
    /** 技能标签，JSON 数组格式（如 ["推荐","官方"]），用于检索与筛选 */
    private String tags;
    /** 安全等级：{@link SecurityLevel#L1}~{@link SecurityLevel#L4}，影响技能可用范围与数据访问权限 */
    private SecurityLevel securityLevel;
    /** 生命周期状态：{@link AgentLifeStatus#DRAFT}→{@link AgentLifeStatus#REVIEWING}→{@link AgentLifeStatus#PUBLISHED}→{@link AgentLifeStatus#ARCHIVED} */
    private AgentLifeStatus lifeStatus;
    /** 技能版本号，语义化版本如 1.0.0，发布后只增不减 */
    private String version;
    /** 创建者用户 ID，关联 user.id */
    private Long authorUserId;
    /** 创建者部门 ID，关联 department.id，用于部门级权限控制 */
    private Long authorDeptId;
    /** 输入参数定义，JSON Schema 字符串，描述技能入参结构与约束 */
    private String inputs;
    /** 输出参数定义，JSON Schema 字符串，描述技能出参结构 */
    private String outputs;
    /** 绑定工具列表，JSON 数组格式（如 [1,2,3]），关联 tool.id */
    private String bindingTools;
    /** 输入输出映射配置，JSON 字符串，描述技能入参与工具入参的转换关系 */
    private String mappingConfig;
    /** 订阅数，该技能被其他智能体订阅的总次数，用于热门排序 */
    private Integer subsCount;
    /** 最近发布时间，技能从草稿转为已发布时写入 */
    private LocalDateTime publishedTime;

    /** 发布可见范围：{@link com.aegis.core.enums.common.Visibility#TENANT}（本租户可见，默认）/ {@link com.aegis.core.enums.common.Visibility#PUBLIC}（全平台可见） */
    private com.aegis.core.enums.common.Visibility visibility;

    // ===================================================================
    // 扩展字段
    // ===================================================================

    /** 技能方法论正文（SKILL.md body）：承载可执行的操作范式 */
    private String instructions;

    /** 引用资源清单，JSON：{ "文件名": "文件内容" }，运行时注入为 AgentSkill.resources */
    private String referencesManifest;

    /** 触发示例，JSON 数组，用于市场检索与模型召回 */
    private String triggerExamples;

    /** 是否系统内置技能（如 SKILL_CREATEOR），1=系统 0=用户 */
    private Boolean isSystem;

    /** 当前生效版本指针（指针式发布，回滚=改此列），对应 res_skill_version.version */
    private String activeVersion;

    /** 灰度版本指针，NULL 表示无灰度 */
    private String canaryVersion;

    /** 灰度发布百分比（1-100），NULL 或 0 表示无灰度。 */
    private Integer canaryPercent;

    /** 最新版本号（含草稿态） */
    private String latestVersion;

    /** 技能作用域：GLOBAL=全局（所有用户自动加载）/ LOCAL=局部（权限过滤），默认 LOCAL */
    private SkillScope scope;
}