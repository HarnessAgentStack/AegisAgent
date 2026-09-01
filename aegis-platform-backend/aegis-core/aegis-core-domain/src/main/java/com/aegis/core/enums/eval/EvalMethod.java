package com.aegis.core.enums.eval;

import lombok.Getter;

/**
 * 评测判定方法。
 *
 * @author wang.zhen
 */
@Getter
public enum EvalMethod {
    EXACT_MATCH("精确匹配"),
    KEYWORD("关键词包含"),
    LLM_SCORE("LLM评分");

    private final String desc;

    EvalMethod(String desc) {
        this.desc = desc;
    }
}
