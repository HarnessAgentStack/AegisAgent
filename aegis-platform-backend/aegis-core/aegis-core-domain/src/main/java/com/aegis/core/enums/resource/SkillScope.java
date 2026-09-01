package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 技能作用域枚举。
 *
 * <p>控制技能的可见范围，替代"全用户默认订阅"的硬编码方式。
 * GLOBAL 技能所有用户自动加载，LOCAL 技能仅对有权限的用户可见。</p>
 *
 *  @author wang.zhen
 */
@Getter
public enum SkillScope {
    /** 全局技能：所有用户自动加载，无需订阅。当前仅 skill_creator 使用此值 */
    GLOBAL("全局"),
    /** 局部技能：默认值，仅对有权限的用户可见（订阅者 + 自有者 + 租户共享） */
    LOCAL("局部");

    private final String desc;
    SkillScope(String desc) { this.desc = desc; }
}