package com.aegis.core.domain.resource;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 技能版本快照实体。
 *
 * <p>不可变快照：每次发布/提交审核时生成一份完整内容快照，
 * 版本号唯一，内容永不修改。发布/回滚仅通过更新 {@link Skill} 的
 * {@code activeVersion} 指针实现，属于"指针式发布"模型。</p>
 *
 * <p>快照字段与 {@link Skill} 元数据字段一一对应，
 * 唯一键：{@code (tenant_id, skill_code, version)}。</p>
 *
 * @author wang.zhen
 * @see Skill
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("res_skill_version")
public class SkillVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键ID（雪花ID） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 租户ID */
    private Long tenantId;

    /** 关联 res_skill.id */
    private Long skillId;

    /** 技能编码（冗余，便于查询） */
    private String skillCode;

    /** 快照版本号，语义化如 1.0.0 */
    private String version;

    /** 技能展示名称（快照） */
    private String skillName;

    /** 技能描述（快照） */
    private String description;

    /** 分类（快照） */
    private String category;

    /** 标签（快照，JSON 数组字符串） */
    private String tags;

    /** 安全等级（快照） */
    private String securityLevel;

    /** 方法论正文（快照，不可变） */
    private String instructions;

    /** 引用资源（快照，JSON） */
    private String referencesManifest;

    /** 触发示例（快照，JSON） */
    private String triggerExamples;

    /** 输入 Schema（快照，JSON） */
    private String inputs;

    /** 输出 Schema（快照，JSON） */
    private String outputs;

    /** 绑定工具（快照，JSON） */
    private String bindingTools;

    /** 映射配置（快照，JSON） */
    private String mappingConfig;

    /** 是否系统技能（快照） */
    private Integer isSystem;

    /** 创建人ID */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 逻辑删除：0-正常，1-已删除 */
    @TableLogic
    private Integer deleted;
}
