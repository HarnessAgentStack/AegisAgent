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
 * 技能更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 技能展示名称 */
    private String skillName;

    /** 技能图标 URL */
    private String icon;

    /** 技能描述 */
    private String description;

    /** 技能类型 */
    private SkillType skillType;

    /** 技能分类 */
    private SkillCategory category;

    /** 技能标签 */
    private String tags;

    /** 安全等级 */
    private SecurityLevel securityLevel;

    /** 生命周期状态 */
    private AgentLifeStatus lifeStatus;

    /** 技能版本号 */
    private String version;

    /** 输入参数定义 */
    private String inputs;

    /** 输出参数定义 */
    private String outputs;

    /** 绑定工具列表 */
    private String bindingTools;

    /** 输入输出映射配置 */
    private String mappingConfig;

    /** 执行配置：模型档位/温度/maxTurns/安全护栏等运行时参数，JSON 字符串 */
    private String execConfig;

    /** 发布可见范围 */
    private Visibility visibility;

    /** 方法论正文（SKILL.md body） */
    private String instructions;

    /** 引用资源清单 JSON */
    private String referencesManifest;

    /** 触发示例 JSON */
    private String triggerExamples;

    /** 交互式创建表单 Schema JSON */
    private String skillForm;
}
