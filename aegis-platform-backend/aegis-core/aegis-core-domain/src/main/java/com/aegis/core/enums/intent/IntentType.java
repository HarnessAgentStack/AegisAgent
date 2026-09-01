package com.aegis.core.enums.intent;

import lombok.Getter;

/**
 * 用户意图类型枚举。
 *
 * <p>由意图识别中间件（{@code AegisIntentMiddleware}）在 Agent 执行前识别，
 * 驱动 RAG 检索、工具调用、Prompt 裁剪等差异化路由策略。</p>
 *
 * <h3>路由规则</h3>
 * <table>
 *   <tr><th>意图</th><th>RAG</th><th>工具</th><th>说明</th></tr>
 *   <tr><td>CHITCHAT</td><td>跳过</td><td>跳过</td><td>闲聊问候，直接对话</td></tr>
 *   <tr><td>TASK</td><td>按需</td><td>启用</td><td>任务执行，可能调用工具</td></tr>
 *   <tr><td>RAG_QUERY</td><td>强制</td><td>按需</td><td>知识库查询</td></tr>
 *   <tr><td>SKILL_CREATE</td><td>跳过</td><td>启用</td><td>技能创建，路由到 SkillCreator</td></tr>
 *   <tr><td>CLARIFICATION</td><td>跳过</td><td>跳过</td><td>意图模糊，返回澄清问题</td></tr>
 * </table>
 *
 * @author wang.zhen
 */
@Getter
public enum IntentType {

    /** 闲聊问候，跳过 RAG / 工具 */
    CHITCHAT("闲聊"),

    /** 任务执行，启用工具 */
    TASK("任务执行"),

    /** 知识检索，强制 RAG */
    RAG_QUERY("知识检索"),

    /** 技能创建 / 修改，路由到 SkillCreator */
    SKILL_CREATE("技能创建"),

    /** 澄清，返回澄清问题不进主执行 */
    CLARIFICATION("澄清");

    private final String desc;

    IntentType(String desc) {
        this.desc = desc;
    }
}
