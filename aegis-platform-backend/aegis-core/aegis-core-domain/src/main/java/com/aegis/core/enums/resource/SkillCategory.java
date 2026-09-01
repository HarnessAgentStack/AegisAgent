package com.aegis.core.enums.resource;

import lombok.Getter;

/**
 * 技能一级分类枚举。
 *
 * <p>技能按业务能力维度的一级分类，用于技能市场组织、检索与权限治理。
 * 分类与工具类型正交，一个技能可调用多个工具但归属一个分类。
 *
 * @author wang.zhen
 */
@Getter
public enum SkillCategory {

    /** 数据处理：数据清洗、格式转换、ETL等数据加工类能力 */
    DATA("数据处理"),

    /** 内容生成：文本、图像、音频、视频等AIGC内容创作类能力 */
    CONTENT("内容生成"),

    /** 集成对接：第三方系统API调用、消息推送、回调通知等集成类能力 */
    INTEGRATION("集成对接"),

    /** 计算：数学计算、统计分析、机器学习推理等计算类能力 */
    COMPUTE("计算"),

    /** 检索：知识库检索、向量查询、文档召回等RAG类能力 */
    RETRIEVAL("检索");

    /** 分类中文描述，用于日志输出 */
    private final String desc;

    SkillCategory(String desc) { this.desc = desc; }
}