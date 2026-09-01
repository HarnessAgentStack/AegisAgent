package com.aegis.core.dto.security;

import com.aegis.core.enums.security.MaskDataType;
import com.aegis.core.enums.security.MaskWay;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据脱敏规则视图对象。
 *
 * @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MaskRuleVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 规则ID */
    private Long id;

    /** 租户ID */
    private Long tenantId;

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

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
