package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.MaskDataType;
import com.aegis.core.enums.security.MaskWay;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 数据脱敏规则实体
 *
 * <p>数据脱敏规则（MaskRule）定义敏感数据的识别模式与脱敏方式，在数据展示、导出、
 * 日志输出等场景自动应用，防止敏感信息泄露。</p>
 *
 * <h3>脱敏机制</h3>
 * <ul>
 *     <li>数据类型：dataType 标识敏感数据类型，如 PHONE、ID_CARD、EMAIL</li>
 *     <li>识别模式：regex 定义正则表达式，匹配敏感数据</li>
 *     <li>脱敏方式：maskWay 定义脱敏方法，如 MASK_MIDDLE、MASK_ALL、REPLACE</li>
 *     <li>示例：example 展示脱敏前后对比，便于规则验证</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，脱敏规则带 tenantId 隔离；
 * 各租户可根据合规要求自定义脱敏规则。</p>
 *
 * @author wang.zhen
 * @see TenantEntity
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("sec_mask_rule")
public class MaskRule extends TenantEntity {
    /** 数据类型：{@link MaskDataType#PHONE}（手机号）/ {@link MaskDataType#ID_CARD}（身份证）/ {@link MaskDataType#BANK_CARD}（银行卡）/ {@link MaskDataType#EMAIL}（邮箱）/ {@link MaskDataType#IP}（IP地址）/ {@link MaskDataType#CUSTOM}（自定义）等 */
    private MaskDataType dataType;
    /** 识别正则，匹配敏感数据的正则表达式 */
    private String regex;
    /** 脱敏方式：{@link MaskWay#MIDDLE4}（中间4位*）/ {@link MaskWay#KEEP_HEAD_TAIL}（保留首尾）/ {@link MaskWay#KEEP_LAST4}（保留后4位）/ {@link MaskWay#ALL}（全部替换）/ {@link MaskWay#HASH}（哈希脱敏） */
    private MaskWay maskWay;
    /** 脱敏示例，展示脱敏前后对比，如 "138****1234" */
    private String example;
    /** 是否启用，true 生效，false 暂停规则 */
    private Boolean enabled;
}