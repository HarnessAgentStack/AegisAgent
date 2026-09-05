package com.aegis.core.dto.resource;

import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.enums.common.Visibility;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 技能创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 技能唯一编码，租户内唯一 */
    private String skillCode;

    /** 技能展示名称 */
    private String skillName;

    /** 技能图标 URL */
    private String icon;

    /** 技能描述 */
    private String description;

    /** 技能类型：ATOMIC / COMPOSITE */
    private SkillType skillType;

    /** 技能分类 */
    private SkillCategory category;

    /** 技能标签，JSON 数组格式 */
    private String tags;

    /** 安全等级：L1~L4 */
    private SecurityLevel securityLevel;

    /** 生命周期状态：DRAFT / REVIEWING / PUBLISHED / ARCHIVED */
    private AgentLifeStatus lifeStatus;

    /** 技能版本号 */
    private String version;

    /** 创建者用户 ID */
    private Long authorUserId;

    /** 创建者部门 ID */
    private Long authorDeptId;

    /** 输入参数定义，JSON Schema 字符串 */
    private String inputs;

    /** 输出参数定义，JSON Schema 字符串 */
    private String outputs;

    /** 绑定工具列表，JSON 数组格式 */
    private String bindingTools;

    /** 输入输出映射配置，JSON 字符串 */
    private String mappingConfig;

    /** 发布可见范围：TENANT / PUBLIC */
    private Visibility visibility;

    /** 技能方法论正文（SKILL.md body）：V2 核心字段，承载可执行的操作范式 */
    private String instructions;

    /** 引用资源清单 JSON */
    private String referencesManifest;

    /** 触发示例 JSON 数组 */
    private String triggerExamples;
}
