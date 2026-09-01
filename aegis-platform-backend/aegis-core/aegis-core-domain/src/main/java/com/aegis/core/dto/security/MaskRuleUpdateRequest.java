package com.aegis.core.dto.security;

import com.aegis.core.enums.security.MaskDataType;
import com.aegis.core.enums.security.MaskWay;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 数据脱敏规则更新请求。
 *
 * <p>所有字段可选，用于部分更新。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaskRuleUpdateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据类型 */
    private MaskDataType dataType;

    /** 识别正则 */
    private String regex;

    /** 脱敏方式 */
    private MaskWay maskWay;

    /** 脱敏示例 */
    private String example;

    /** 是否启用 */
    private Boolean enabled;
}
