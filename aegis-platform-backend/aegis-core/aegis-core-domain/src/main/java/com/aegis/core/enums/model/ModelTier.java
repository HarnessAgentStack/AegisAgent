package com.aegis.core.enums.model;

import lombok.Getter;

/**
 * 模型档位枚举。
 *
 * <p>平台对大模型能力进行档位抽象，业务侧按档位声明需求，
 * 由 {@code ModelRouteResolver} 直接按 tier 查询 model_def 表解析为具体模型实例，
 * 解耦业务与模型实现。档位由低到高对应响应速度递减、推理深度递增。
 *
 * @author wang.zhen
 */
@Getter
public enum ModelTier {

    /** 轻量档：快速响应场景，低成本，适合简单问答与短文本生成 */
    LIGHT("轻量"),

    /** 标准档：平衡场景，兼顾速度与质量，适合常规对话与中等复杂任务 */
    STANDARD("标准"),

    /** 强力档：深度思考场景，高成本，适合复杂推理与长文本生成 */
    STRONG("强力");

    /** 档位中文描述，用于日志输出 */
    private final String desc;

    ModelTier(String desc) { this.desc = desc; }
}