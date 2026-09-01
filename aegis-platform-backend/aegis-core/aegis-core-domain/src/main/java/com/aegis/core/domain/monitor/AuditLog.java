package com.aegis.core.domain.monitor;

import com.aegis.core.base.TenantEntity;
import com.aegis.core.enums.monitor.AuditLogType;
import com.aegis.core.enums.monitor.AuditResult;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import com.aegis.core.domain.org.User;

/**
 * 审计日志实体
 *
 * <p>审计日志（AuditLog）记录平台内所有关键操作的完整信息，包括操作人、操作类型、
 * 资源对象、操作结果、溯源信息等，是安全审计、合规检查与问题排查的核心数据。</p>
 *
 * <h3>日志分类</h3>
<ul>
 *     <li>SESSION：会话生命周期日志（CHAT_START / CHAT_COMPLETE）</li>
 *     <li>SECURITY：安全事件日志（SECURITY_BLOCK / SECURITY_ASK / 导出）</li>
 *     <li>POLICY_DECISION：策略决策日志（工具策略评估实时记录）</li>
 * </ul>
 *
 * <h3>多租户隔离</h3>
 * <p>继承自 {@link TenantEntity}，审计日志带 tenantId 隔离；
 * retentionDays 控制日志保留天数，过期自动归档或删除。</p>
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
@TableName("mon_audit_log")
public class AuditLog extends TenantEntity {
    /** 日志类型：{@link AuditLogType#SESSION}（会话）/ {@link AuditLogType#SECURITY}（安全）/ {@link AuditLogType#POLICY_DECISION}（策略决策） */
    private AuditLogType logType;
    /** 操作人用户 ID，关联 user.id */
    private Long userId;
    /** 操作人用户名，冗余存储便于审计列表展示 */
    private String username;
    /** 操作类型，如 CREATE_AGENT / UPDATE_SKILL / DELETE_KB，标识具体操作 */
    private String operation;
    /** 资源类型，如 AGENT / SKILL / KNOWLEDGE_BASE，被操作资源种类 */
    private String resourceType;
    /** 资源名称，被操作资源的名称，便于审计追溯 */
    private String resourceName;
    /** 操作详情，JSON 字符串，记录操作的具体参数与变更内容 */
    private String detail;
    /** 操作结果：{@link AuditResult#SUCCESS}（成功）/ {@link AuditResult#BLOCKED}（拦截）/ {@link AuditResult#ALERT}（告警）/ {@link AuditResult#RECORDED}（已记录） */
    private AuditResult result;
    /** 客户端 IP，操作发起的 IP 地址，用于安全审计 */
    private String ip;
    /** User-Agent，客户端标识，记录操作来源设备与浏览器 */
    private String userAgent;
    /** 链路追踪 ID，用于全链路日志关联与排查 */
    private String traceId;
    /** 会话ID（结构化字段，供精确查询，不再从 detail JSON LIKE 捞） */
    private String sessionId;
    /** 智能体ID（结构化字段，供精确查询） */
    private Long agentId;
    /** 保留天数，日志保留时长，依据 logType 不同默认值不同，过期自动清理 */
    private Integer retentionDays;
    /** 发生时间，操作实际发生的时间 */
    private LocalDateTime occurTime;
}