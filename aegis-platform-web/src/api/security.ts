/**
 * @file 安全治理 API 客户端
 * @description 封装安全策略、HITL审批、审计日志等接口
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';

/** MyBatis-Plus 分页响应 */
export interface Page<T> {
  records: T[];
  total: number;
  current: number;
  size: number;
}

/** 分页查询参数 */
export interface PageQuery {
  page?: number;
  size?: number;
  [key: string]: unknown;
}

// ==================== 安全策略 ====================

const SECURITY_BASE = '/admin/security';

/** 工具策略（对齐后端 ToolPolicyVO） */
export interface ToolPolicy {
  id?: string;
  tenantId?: string;
  toolType?: string;
  securityLevel?: number;
  action?: string;
  description?: string;
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** 敏感词（对齐后端 SensitiveWordVO） */
export interface SensitiveWord {
  id?: string;
  tenantId?: string;
  word?: string;
  category?: string;
  matchMode?: string;
  action?: string;
  replaceText?: string;
  scope?: string;
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** 脱敏规则（对齐后端 MaskRuleVO） */
export interface MaskRule {
  id?: string;
  tenantId?: string;
  dataType?: string;
  regex?: string;
  maskWay?: string;
  example?: string;
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** 出站策略（对齐后端 OutboundPolicyVO） */
export interface OutboundPolicy {
  id?: string;
  tenantId?: string;
  policyType?: string;
  domain?: string;
  ipCidr?: string;
  portLimit?: number;
  applicableScope?: string;
  scopeConfig?: string;
  validHours?: number;
  description?: string;
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

// ---- Tool Policies ----

/** 工具策略分页查询 */
export function getToolPolicies(params?: PageQuery): Promise<Page<ToolPolicy>> {
  return http.get<Page<ToolPolicy>>(`${SECURITY_BASE}/tool-policies`, { params });
}

/** 新建工具策略 */
export function createToolPolicy(data: Partial<ToolPolicy>): Promise<ToolPolicy> {
  return http.post<ToolPolicy>(`${SECURITY_BASE}/tool-policies`, data);
}

/** 更新工具策略 */
export function updateToolPolicy(id: string, data: Partial<ToolPolicy>): Promise<ToolPolicy> {
  return http.put<ToolPolicy>(`${SECURITY_BASE}/tool-policies/${id}`, data);
}

/** 删除工具策略 */
export function deleteToolPolicy(id: string): Promise<void> {
  return http.delete<void>(`${SECURITY_BASE}/tool-policies/${id}`);
}

// ---- Sensitive Words ----

/** 敏感词分页查询 */
export function getSensitiveWords(params?: PageQuery): Promise<Page<SensitiveWord>> {
  return http.get<Page<SensitiveWord>>(`${SECURITY_BASE}/sensitive-words`, { params });
}

/** 新建敏感词 */
export function createSensitiveWord(data: Partial<SensitiveWord>): Promise<SensitiveWord> {
  return http.post<SensitiveWord>(`${SECURITY_BASE}/sensitive-words`, data);
}

/** 更新敏感词 */
export function updateSensitiveWord(id: string, data: Partial<SensitiveWord>): Promise<SensitiveWord> {
  return http.put<SensitiveWord>(`${SECURITY_BASE}/sensitive-words/${id}`, data);
}

/** 删除敏感词 */
export function deleteSensitiveWord(id: string): Promise<void> {
  return http.delete<void>(`${SECURITY_BASE}/sensitive-words/${id}`);
}

// ---- Mask Rules ----

/** 脱敏规则分页查询 */
export function getMaskRules(params?: PageQuery): Promise<Page<MaskRule>> {
  return http.get<Page<MaskRule>>(`${SECURITY_BASE}/mask-rules`, { params });
}

/** 新建脱敏规则 */
export function createMaskRule(data: Partial<MaskRule>): Promise<MaskRule> {
  return http.post<MaskRule>(`${SECURITY_BASE}/mask-rules`, data);
}

/** 更新脱敏规则 */
export function updateMaskRule(id: string, data: Partial<MaskRule>): Promise<MaskRule> {
  return http.put<MaskRule>(`${SECURITY_BASE}/mask-rules/${id}`, data);
}

/** 删除脱敏规则 */
export function deleteMaskRule(id: string): Promise<void> {
  return http.delete<void>(`${SECURITY_BASE}/mask-rules/${id}`);
}

// ---- Outbound Policies ----

/** 出站策略分页查询 */
export function getOutboundPolicies(params?: PageQuery): Promise<Page<OutboundPolicy>> {
  return http.get<Page<OutboundPolicy>>(`${SECURITY_BASE}/outbound-policies`, { params });
}

/** 新建出站策略 */
export function createOutboundPolicy(data: Partial<OutboundPolicy>): Promise<OutboundPolicy> {
  return http.post<OutboundPolicy>(`${SECURITY_BASE}/outbound-policies`, data);
}

/** 更新出站策略 */
export function updateOutboundPolicy(id: string, data: Partial<OutboundPolicy>): Promise<OutboundPolicy> {
  return http.put<OutboundPolicy>(`${SECURITY_BASE}/outbound-policies/${id}`, data);
}

/** 删除出站策略 */
export function deleteOutboundPolicy(id: string): Promise<void> {
  return http.delete<void>(`${SECURITY_BASE}/outbound-policies/${id}`);
}

// ==================== HITL 审批 ====================

const HITL_BASE = '/admin/hitl';

/** HITL 节点（对齐后端 HitlNodeVO） */
export interface HitlNode {
  id?: string;
  tenantId?: string;
  agentId?: string;
  nodeName?: string;
  triggerCondition?: string;
  approverUserId?: string;
  approverRole?: string;
  slaHours?: number;
  timeoutStrategy?: string;
  allowedActions?: string;
  enabled?: boolean;
  createTime?: string;
  updateTime?: string;
  [key: string]: unknown;
}

/** HITL 历史记录（对齐后端 HitlHistoryVO） */
export interface HitlHistory {
  id?: string;
  tenantId?: string;
  nodeId?: string;
  agentId?: string;
  sessionId?: string;
  action?: string;
  operatorUserId?: string;
  operatorName?: string;
  detail?: string;
  occurTime?: string;
  createTime?: string;
  [key: string]: unknown;
}

// ---- HITL Nodes ----

/** HITL 节点分页查询 */
export function getHitlNodes(params?: PageQuery): Promise<Page<HitlNode>> {
  return http.get<Page<HitlNode>>(`${HITL_BASE}/nodes`, { params });
}

/** 新建 HITL 节点 */
export function createHitlNode(data: Partial<HitlNode>): Promise<HitlNode> {
  return http.post<HitlNode>(`${HITL_BASE}/nodes`, data);
}

/** 更新 HITL 节点 */
export function updateHitlNode(id: string, data: Partial<HitlNode>): Promise<HitlNode> {
  return http.put<HitlNode>(`${HITL_BASE}/nodes/${id}`, data);
}

/** 删除 HITL 节点 */
export function deleteHitlNode(id: string): Promise<void> {
  return http.delete<void>(`${HITL_BASE}/nodes/${id}`);
}

// ---- HITL History ----

/** HITL 历史记录分页查询 */
export function getHitlHistory(params?: PageQuery): Promise<Page<HitlHistory>> {
  return http.get<Page<HitlHistory>>(`${HITL_BASE}/history`, { params });
}

// ==================== 审计日志 ====================

const AUDIT_BASE = '/admin/audit';

/** 审计日志（对齐后端 AuditLog 实体字段名） */
export interface AuditLog {
  id?: string;
  logType?: string;
  userId?: string;
  username?: string;
  operation?: string;
  resourceType?: string;
  resourceName?: string;
  detail?: string;
  result?: string;
  ip?: string;
  traceId?: string;
  occurTime?: string;
  retentionDays?: number;
  [key: string]: unknown;
}

/** 审计日志查询参数 */
export interface AuditLogQuery extends PageQuery {
  logType?: string;
  userId?: string;
  result?: string;
  operation?: string;
  resourceName?: string;
  keyword?: string;
  startTime?: string;
  endTime?: string;
}

/** 审计日志分页查询 */
export function getAuditLogs(params?: AuditLogQuery): Promise<Page<AuditLog>> {
  return http.get<Page<AuditLog>>(`${AUDIT_BASE}/logs`, { params });
}

/** 导出审计日志 */
export function exportAuditLogs(params?: Omit<AuditLogQuery, 'page' | 'size'>): Promise<Blob> {
  return http.get<Blob>(`${AUDIT_BASE}/logs/export`, { params, responseType: 'blob' });
}

/** 审计统计（按类型计数，支持时间范围联动） */
export function getAuditStats(params?: Record<string, unknown>): Promise<Record<string, number>> {
  return http.get<Record<string, number>>(`${AUDIT_BASE}/stats`, { params });
}
