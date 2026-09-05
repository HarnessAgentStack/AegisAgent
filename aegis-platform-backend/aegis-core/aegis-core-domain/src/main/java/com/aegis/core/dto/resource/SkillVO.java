package com.aegis.core.dto.resource;

import com.aegis.core.enums.agent.AgentLifeStatus;
import com.aegis.core.enums.common.SecurityLevel;
import com.aegis.core.enums.resource.SkillCategory;
import com.aegis.core.enums.resource.SkillType;
import com.aegis.core.enums.common.Visibility;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 技能视图对象。
 *
 * <p>所有 Long 类型 ID 字段通过 {@code @JsonSerialize(ToStringSerializer)} 序列化为字符串，
 * 防止前端 JavaScript Number 精度丢失。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 技能ID（雪花ID，序列化为字符串防止JS精度丢失） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 租户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long tenantId;

    /** 技能唯一编码 */
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

    /** 技能标签 */
    private String tags;

    /** 安全等级 */
    private SecurityLevel securityLevel;

    /** 生命周期状态 */
    private AgentLifeStatus lifeStatus;

    /** 技能版本号 */
    private String version;

    /** 创建者用户 ID */
    private Long authorUserId;

    /** 创建者部门 ID */
    private Long authorDeptId;

    /** 输入参数定义 */
    private String inputs;

    /** 输出参数定义 */
    private String outputs;

    /** 绑定工具列表 */
    private String bindingTools;

    /** 输入输出映射配置 */
    private String mappingConfig;

    /** 指令/方法论（技能核心内容，编辑面板回显依赖此字段） */
    private String instructions;

    /** 订阅数 */
    private Integer subsCount;

    /** 最近发布时间 */
    private LocalDateTime publishedTime;

    /** 发布可见范围 */
    private Visibility visibility;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
