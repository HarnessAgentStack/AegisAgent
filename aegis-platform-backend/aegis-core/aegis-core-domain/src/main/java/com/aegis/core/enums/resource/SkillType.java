package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 技能类型枚举。
 *
 * <p>区分技能的封装粒度，原子技能为单工具直接封装，
 * 复合技能为多工具编排流程，支持参数映射与流程控制。
 *
 * @author wang.zhen
 */
@Getter
public enum SkillType {

    /** 原子技能：单工具直接封装，1:1映射工具能力 */
    ATOMIC("原子技能"),

    /** 组合技能：多工具编排流程，支持参数映射、条件分支与循环 */
    COMPOSITE("组合技能");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    SkillType(String desc) { this.desc = desc; }
}