package com.aegis.core.enums.monitor;

import lombok.Getter;

/**
 * 审计日志类型枚举。
 *
 * <p>平台审计日志的三大分类。
 *
 * <table border="1">
 *   <caption>类型与保留策略</caption>
 *   <tr><th>类型</th><th>用途</th><th>保留期</th></tr>
 *   <tr><td>SESSION</td><td>会话生命周期（CHAT_START / CHAT_COMPLETE）</td><td>90 天</td></tr>
 *   <tr><td>SECURITY</td><td>安全事件（SECURITY_BLOCK / SECURITY_ASK / 审计导出）</td><td>365 天</td></tr>
 *   <tr><td>POLICY_DECISION</td><td>策略决策记录（每次工具策略评估实时落库）</td><td>90 天</td></tr>
 * </table>
 *
 * @author wang.zhen
 */
@Getter
public enum AuditLogType {

    /** 会话：会话生命周期事件（创建/结束），保留90天 */
    SESSION("会话"),

    /** 安全：安全事件（拦截/审批请求/审计导出），保留365天 */
    SECURITY("安全"),

    /** 策略决策：工具策略评估结果（ALLOW/DENY/APPROVE），保留90天 */
    POLICY_DECISION("策略决策");

    /** 类型中文描述，用于日志输出 */
    private final String desc;

    AuditLogType(String desc) { this.desc = desc; }
}
