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
 * 敏感词创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensitiveWordCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 敏感词内容，长度不超过 128 */
    private String word;

    /** 敏感词分类：GENERAL / INDUSTRY / ENTERPRISE / PRIVACY */
    private SensitiveCategory category;

    /** 匹配模式：EXACT（精确）/ FUZZY（模糊）/ REGEX（正则） */
    private MatchMode matchMode;

    /** 处理动作：BLOCK（拦截）/ REPLACE（替换）/ MARK（标记） */
    private SensitiveAction action;

    /** 替换文本，当 action 为 REPLACE 时使用 */
    private String replaceText;

    /** 适用范围：INPUT / OUTPUT / TOOL_RESULT */
    private SensitiveScope scope;

    /** 是否启用 */
    private Boolean enabled;
}
