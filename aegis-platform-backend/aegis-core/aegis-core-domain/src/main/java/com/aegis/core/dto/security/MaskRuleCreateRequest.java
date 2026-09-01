package com.aegis.core.dto.security;

import com.aegis.core.enums.security.MaskDataType;
import com.aegis.core.enums.security.MaskWay;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据脱敏规则创建请求。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaskRuleCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据类型：PHONE / ID_CARD / BANK_CARD / EMAIL / IP / CUSTOM */
    private MaskDataType dataType;

    /** 识别正则，匹配敏感数据的正则表达式 */
    private String regex;

    /** 脱敏方式：MIDDLE4 / KEEP_HEAD_TAIL / KEEP_LAST4 / ALL / HASH */
    private MaskWay maskWay;

    /** 脱敏示例，展示脱敏前后对比 */
    private String example;

    /** 是否启用 */
    private Boolean enabled;
}
