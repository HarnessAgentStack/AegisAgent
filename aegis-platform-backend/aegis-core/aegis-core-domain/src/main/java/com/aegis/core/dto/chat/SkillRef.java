package com.aegis.core.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @SKILL 结构化引用（不靠解析文本）。
 *
 * <p>通过 {@code @} 唤起技能列表，选中后以结构化方式随 {@link ChatRequest#skills}
 * 透传；运行时消费此引用，强制将对应技能注入本次请求的上下文（绕过仅“可见”的默认策略）。
 *
 * <h3>与 AgentScope 的关系</h3>
 * <p>本对象在装配期被展平为技能 code 列表，写入 {@code RuntimeContext} 的
 * {@code aegis.requestedSkills} 属性；框架 {@code RuntimeContextSkillRepository} 读取该属性，
 * 在 {@code getAllSkills(ctx)} 中强制包含这些技能。
 *
 *  @author wang.zhen
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillRef implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 技能编码（res_skill.skill_code），必填，用于精确匹配 */
    private String skillCode;

    /** 指定版本，可选；为空时跟随技能当前 active_version */
    private String version;

    /**
     * 作用域，可选，默认 SESSION：
     * <ul>
     *   <li>SESSION：仅本次对话生效</li>
     *   <li>PINNED：固定注入该智能体的后续所有对话（写入智能体绑定）</li>
     * </ul>
     */
    private String scope;
}
