package com.aegis.core.dto.security;

import com.aegis.core.enums.security.MatchMode;
import com.aegis.core.enums.security.SensitiveAction;
import com.aegis.core.enums.security.SensitiveCategory;
import com.aegis.core.enums.security.SensitiveScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 敏感词更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveWordUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 敏感词内容 */
    private String word;

    /** 敏感词分类 */
    private SensitiveCategory category;

    /** 匹配模式 */
    private MatchMode matchMode;

    /** 处理动作 */
    private SensitiveAction action;

    /** 替换文本 */
    private String replaceText;

    /** 适用范围 */
    private SensitiveScope scope;

    /** 是否启用 */
    private Boolean enabled;
}
