package com.aegis.core.domain.security;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.security.SensitiveCategory;
import com.aegis.core.enums.security.MatchMode;
import com.aegis.core.enums.security.SensitiveAction;
import com.aegis.core.enums.security.SensitiveScope;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 敏感词实体
 *
 * <p>敏感词（SensitiveWord）定义需要识别与过滤的敏感词汇，在用户输入、智能体输出、
 * 文档内容等场景进行匹配与处理，保障内容合规。</p>
 *
 * <h3>核心机制</h3>
 * <ul>
 *     <li>匹配模式：matchMode 定义匹配方式，如 EXACT、CONTAIN、REGEX</li>
 *     <li>处理动作：action 定义命中后的处理，如 BLOCK、REPLACE、LOG</li>
 *     <li>替换文本：replaceText 定义替换内容，当 action 为 REPLACE 时使用</li>
 *     <li>适用范围：scope 控制敏感词应用场景，如 INPUT、OUTPUT、ALL</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，敏感词带 tenantId 隔离；
 * 各租户可根据行业合规要求自定义敏感词库。</p>
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
@TableName("sec_sensitive_word")
public class SensitiveWord extends TenantEntity {
    /** 敏感词内容，长度不超过 128 */
    private String word;
    /** 敏感词分类：{@link SensitiveCategory#GENERAL}（通用）/ {@link SensitiveCategory#INDUSTRY}（行业）/ {@link SensitiveCategory#ENTERPRISE}（企业自定义）/ {@link SensitiveCategory#PRIVACY}（个人隐私）等 */
    private SensitiveCategory category;
    /** 匹配模式：{@link MatchMode#EXACT}（精确）/ {@link MatchMode#FUZZY}（模糊）/ {@link MatchMode#REGEX}（正则） */
    private MatchMode matchMode;
    /** 处理动作：{@link SensitiveAction#BLOCK}（拦截）/ {@link SensitiveAction#REPLACE}（替换）/ {@link SensitiveAction#MARK}（标记） */
    private SensitiveAction action;
    /** 替换文本，当 action 为 REPLACE 时使用的替换内容，如 *** */
    private String replaceText;
    /** 适用范围：{@link SensitiveScope#INPUT}（用户输入）/ {@link SensitiveScope#OUTPUT}（模型输出）/ {@link SensitiveScope#TOOL_RESULT}（工具返回） */
    private SensitiveScope scope;
    /** 是否启用，true 生效，false 暂停敏感词 */
    private Boolean enabled;
}