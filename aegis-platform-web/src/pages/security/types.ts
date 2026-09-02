/**
 * @file 安全策略管理 - DTO 类型定义
 * @description 各 Tab 子组件共用的 DTO 与表单值类型
 * @author wang.zhen
 * @since 1.0.0
 */

// ==================== Tab 1: 安全级别 ====================

export type SecurityLevelKey = 'L1' | 'L2' | 'L3' | 'L4';

export interface SecurityLevelCard {
  key: SecurityLevelKey;
  name: string;
  desc: string;
  agentCount: number;
  color: string;
}

export interface LevelPolicyDetail {
  key: SecurityLevelKey;
  level: string;
  dataIsolation: string;
  operationPermission: string;
  auditRequirement: string;
}

// ==================== Tab 2: 工具管控 ====================

/** 工具策略 DTO（对齐后端数据结构，ID 为雪花ID字符串防精度丢失） */
export interface ToolPolicyDTO {
  id?: string;
  tenantId?: string;
  toolType?: string;
  securityLevel?: number;
  action?: string;
  governanceTierMin?: string;
  description?: string;
  enabled?: boolean;
  [key: string]: unknown;
}

/** 工具策略表单值 */
export interface ToolPolicyFormValues {
  toolType: string;
  securityLevel: number;
  action: string;
  governanceTierMin?: string;
  description?: string;
  enabled: boolean;
}

/** 矩阵行（按 toolType 聚合） */
export interface ToolMatrixRow {
  key: string;
  toolType: string;
}

// ==================== Tab 3: 敏感词库 ====================

/** 敏感词 DTO */
export interface SensitiveWordDTO {
  id?: string;
  tenantId?: string;
  word?: string;
  category?: string;
  matchMode?: string;
  action?: string;
  replaceText?: string;
  scope?: string;
  enabled?: boolean;
  [key: string]: unknown;
}

/** 敏感词表单值 */
export interface SensitiveWordFormValues {
  word: string;
  category: string;
  matchMode: string;
  action: string;
  replaceText?: string;
  scope: string;
  enabled: boolean;
}

// ==================== Tab 4: 脱敏规则 ====================

/** 脱敏规则 DTO */
export interface MaskRuleDTO {
  id?: string;
  tenantId?: string;
  dataType?: string;
  regex?: string;
  maskWay?: string;
  example?: string;
  enabled?: boolean;
  [key: string]: unknown;
}

/** 脱敏规则表单值 */
export interface MaskRuleFormValues {
  dataType: string;
  regex: string;
  maskWay: string;
  example?: string;
  enabled: boolean;
}

// ==================== Tab 5: 出站策略 ====================

/** 出站策略 DTO */
export interface OutboundPolicyDTO {
  id?: string;
  tenantId?: string;
  policyType?: string;
  domain?: string;
  ipCidr?: string;
  applicableScope?: string;
  scopeConfig?: string;
  enabled?: boolean;
  expireTime?: string;
  [key: string]: unknown;
}

/** 出站策略表单值 */
export interface OutboundPolicyFormValues {
  policyType: string;
  domain?: string;
  ipCidr?: string;
  applicableScope: string;
  enabled: boolean;
}
