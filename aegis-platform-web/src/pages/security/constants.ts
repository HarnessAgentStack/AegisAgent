/**
 * @file 安全策略管理 - 公共常量
 * @description 色值、子标签、选项映射等共享常量
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Tag } from 'antd';
import type {
  LevelPolicyDetail,
  SecurityLevelCard,
  SecurityLevelKey,
} from './types';

/** 产品原型色值 */
export const COLOR = {
  primary: '#4f46e5',
  success: '#10b981',
  warning: '#f59e0b',
  danger: '#ef4444',
  info: '#3b82f6',
} as const;

/** 子标签定义（已移除独立的"安全级别"TAB，安全级别详情嵌入工具管控决策矩阵） */
export const SECURITY_TABS = [
  { key: 'tool', label: '🔧 工具管控' },
  { key: 'word', label: '📝 敏感词库' },
  { key: 'mask', label: '🎭 脱敏规则' },
  { key: 'out', label: '🌐 出站策略' },
  { key: 'content', label: '🔍 内容审核' },
];

// ==================== Tab 1: 安全级别（静态说明数据） ====================

export const SECURITY_LEVELS: SecurityLevelCard[] = [
  { key: 'L1', name: 'L1 公开', desc: '可对外公开的数据与操作', agentCount: 12, color: COLOR.success },
  { key: 'L2', name: 'L2 内部', desc: '仅限组织内部可见与使用', agentCount: 28, color: COLOR.info },
  { key: 'L3', name: 'L3 机密', desc: '需授权访问的核心业务数据', agentCount: 9, color: COLOR.warning },
  { key: 'L4', name: 'L4 绝密', desc: '最高级别管控，严格审计', agentCount: 3, color: COLOR.danger },
];

export const LEVEL_POLICY_DETAILS: LevelPolicyDetail[] = [
  {
    key: 'L1',
    level: 'L1 公开',
    dataIsolation: '共享数据空间，无隔离要求',
    operationPermission: '全部只读操作 + 受限写操作',
    auditRequirement: '按月归档，无需实时审计',
  },
  {
    key: 'L2',
    level: 'L2 内部',
    dataIsolation: '租户级隔离，跨租户不可见',
    operationPermission: '内部全部操作，禁外部共享',
    auditRequirement: '操作日志保留 90 天',
  },
  {
    key: 'L3',
    level: 'L3 机密',
    dataIsolation: '行级权限隔离，按角色授权',
    operationPermission: '需二次确认，敏感操作审批',
    auditRequirement: '实时审计，日志保留 180 天',
  },
  {
    key: 'L4',
    level: 'L4 绝密',
    dataIsolation: '独立加密存储，专用沙箱执行',
    operationPermission: '最小权限原则，逐次审批',
    auditRequirement: '实时审计 + 全量留痕，永久保留',
  },
];

// ==================== Tab 2: 工具管控 ====================

/** 工具类型选项 */
export const TOOL_TYPE_OPTIONS = [
  { value: 'READONLY', label: '只读操作' },
  { value: 'INTERNAL_API', label: '内部 API' },
  { value: 'WRITE', label: '写操作' },
  { value: 'EXTERNAL_NETWORK', label: '外部网络' },
  { value: 'CODE_EXEC', label: '代码执行' },
  { value: 'HIGH_RISK', label: '高风险操作' },
];

export const TOOL_TYPE_LABEL: Record<string, string> = Object.fromEntries(
  TOOL_TYPE_OPTIONS.map((o) => [o.value, o.label]),
);

/** 安全级别选项（1-4 对应 L1-L4） */
export const SECURITY_LEVEL_OPTIONS = [
  { value: 1, label: 'L1 公开' },
  { value: 2, label: 'L2 内部' },
  { value: 3, label: 'L3 机密' },
  { value: 4, label: 'L4 绝密' },
];

export const LEVEL_LABEL: Record<number, string> = {
  1: 'L1 公开',
  2: 'L2 内部',
  3: 'L3 机密',
  4: 'L4 绝密',
};

/** 工具动作选项 */
export const TOOL_ACTION_OPTIONS = [
  { value: 'ALLOW', label: '放行' },
  { value: 'APPROVE', label: '审批' },
  { value: 'REJECT', label: '拒绝' },
];

export const TOOL_ACTION_MAP: Record<string, { text: string; color: string }> = {
  ALLOW: { text: '放行', color: COLOR.success },
  APPROVE: { text: '审批', color: COLOR.warning },
  REJECT: { text: '拒绝', color: COLOR.danger },
};

export const TOOL_ACTION_CYCLE: string[] = ['ALLOW', 'APPROVE', 'REJECT'];

/** 矩阵列定义 */
export const MATRIX_LEVEL_COLUMNS: { level: number; title: string }[] = [
  { level: 1, title: 'L1 公开' },
  { level: 2, title: 'L2 内部' },
  { level: 3, title: 'L3 机密' },
  { level: 4, title: 'L4 绝密' },
];

// ==================== Tab 3: 敏感词库 ====================

export const WORD_CATEGORY_OPTIONS = [
  { value: 'GENERAL', label: '通用' },
  { value: 'PRIVACY', label: '隐私' },
];

export const WORD_CATEGORY_MAP: Record<string, { text: string; color: string }> = {
  GENERAL: { text: '通用', color: COLOR.primary },
  PRIVACY: { text: '隐私', color: COLOR.info },
};

export const WORD_MATCH_MODE_OPTIONS = [
  { value: 'EXACT', label: '精确匹配' },
  { value: 'FUZZY', label: '模糊匹配' },
  { value: 'REGEX', label: '正则匹配' },
];

export const WORD_MATCH_MODE_MAP: Record<string, { text: string; color: string }> = {
  EXACT: { text: '精确', color: '#6b7280' },
  FUZZY: { text: '模糊', color: COLOR.info },
  REGEX: { text: '正则', color: '#7c3aed' },
};

export const WORD_ACTION_OPTIONS = [
  { value: 'BLOCK', label: '拦截' },
  { value: 'REPLACE', label: '替换' },
  { value: 'MARK', label: '标记' },
];

export const WORD_ACTION_MAP: Record<string, { text: string; color: string }> = {
  BLOCK: { text: '拦截', color: COLOR.danger },
  REPLACE: { text: '替换', color: COLOR.primary },
  MARK: { text: '标记', color: COLOR.warning },
};

export const WORD_SCOPE_OPTIONS = [
  { value: 'INPUT', label: '输入' },
  { value: 'OUTPUT', label: '输出' },
  { value: 'TOOL_RESULT', label: '工具结果' },
  { value: 'ALL', label: '全部' },
];

export const WORD_SCOPE_MAP: Record<string, { text: string; color: string }> = {
  INPUT: { text: '输入', color: COLOR.info },
  OUTPUT: { text: '输出', color: COLOR.primary },
  TOOL_RESULT: { text: '工具结果', color: '#7c3aed' },
  ALL: { text: '全部', color: '#6b7280' },
};

// ==================== Tab 4: 脱敏规则 ====================

export const MASK_DATA_TYPE_OPTIONS = [
  { value: 'PHONE', label: '手机号' },
  { value: 'ID_CARD', label: '身份证号' },
  { value: 'BANK_CARD', label: '银行卡号' },
  { value: 'EMAIL', label: '邮箱' },
  { value: 'IP', label: 'IP 地址' },
  { value: 'CUSTOM', label: '自定义' },
];

export const MASK_DATA_TYPE_MAP: Record<string, { text: string; color: string }> = {
  PHONE: { text: '手机号', color: COLOR.primary },
  ID_CARD: { text: '身份证号', color: COLOR.info },
  BANK_CARD: { text: '银行卡号', color: COLOR.warning },
  EMAIL: { text: '邮箱', color: COLOR.success },
  IP: { text: 'IP 地址', color: '#7c3aed' },
  CUSTOM: { text: '自定义', color: '#6b7280' },
};

export const MASK_WAY_OPTIONS = [
  { value: 'MIDDLE4', label: '中间 4 位脱敏' },
  { value: 'KEEP_HEAD_TAIL', label: '保留首尾' },
  { value: 'KEEP_LAST4', label: '保留后 4 位' },
  { value: 'ALL', label: '全部脱敏' },
  { value: 'HASH', label: '哈希脱敏' },
];

export const MASK_WAY_MAP: Record<string, { text: string; color: string }> = {
  MIDDLE4: { text: '中间4位', color: COLOR.primary },
  KEEP_HEAD_TAIL: { text: '保留首尾', color: COLOR.info },
  KEEP_LAST4: { text: '保留后4位', color: COLOR.warning },
  ALL: { text: '全部脱敏', color: COLOR.danger },
  HASH: { text: '哈希', color: '#7c3aed' },
};

// ==================== Tab 5: 出站策略 ====================

export const OUTBOUND_POLICY_TYPE_OPTIONS = [
  { value: 'WHITELIST_DOMAIN', label: '域名白名单' },
  { value: 'BLACKLIST_IP', label: 'IP 黑名单' },
];

export const OUTBOUND_SCOPE_OPTIONS = [
  { value: 'ALL', label: '全部' },
  { value: 'AGENT', label: '智能体' },
  { value: 'DEPT', label: '部门' },
];

export const OUTBOUND_SCOPE_MAP: Record<string, { text: string; color: string }> = {
  ALL: { text: '全部', color: '#6b7280' },
  AGENT: { text: '智能体', color: COLOR.primary },
  DEPT: { text: '部门', color: COLOR.info },
};

// ==================== Tab 6: 内容审核 ====================

export const AUDIT_SCOPE_MAP = WORD_SCOPE_MAP;

// ==================== 通用辅助 ====================

/** 渲染启用/停用 Tag */
export const renderEnabledTag = (enabled?: boolean) =>
  React.createElement(
    Tag,
    { color: enabled ? COLOR.success : '#9ca3af' },
    enabled ? '启用' : '停用',
  );

export type { SecurityLevelKey };
