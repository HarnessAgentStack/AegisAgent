/**
 * @file 智能体详情常量
 * @description 颜色映射、状态常量、Tab 定义等公共配置。
 * @author wang.zhen
 * @since 1.0.0
 */


/** 生命周期状态颜色 */
export const LIFE_STATUS_COLOR: Record<string, string> = {
  DRAFT: 'default',
  REVIEWING: 'processing',
  PUBLISHED: 'green',
  ARCHIVED: 'orange',
  REJECTED: 'red',
};

/** 安全级别颜色（历史字段，新模型已统一为治理档位 GovernanceTier，保留仅为兼容） */
export const SECURITY_LEVEL_COLOR: Record<string, string> = {
  L1: 'green',
  L2: 'blue',
  L3: 'orange',
  L4: 'red',
};

/** 治理档位颜色（单一判别器，取代原安全级别 / 护栏级别 / 规划模式） */
export const GOVERNANCE_COLOR: Record<string, string> = {
  STANDARD: 'green',
  ENHANCED: 'gold',
  STRICT: 'red',
};

/** 治理档位展示文案 */
export const GOVERNANCE_LABEL: Record<string, string> = {
  STANDARD: '标准档',
  ENHANCED: '增强档',
  STRICT: '严格档',
};

/** 智能体类型展示文案 */
export const AGENT_TYPE_LABEL: Record<string, string> = {
  UNIVERSAL: '通用智能体',
  APPLICATION: '应用智能体',
  SYSTEM: '系统智能体',
};

/** 智能体类型短标签 */
export const AGENT_TYPE_SHORT: Record<string, string> = {
  UNIVERSAL: '通用',
  APPLICATION: '应用',
  SYSTEM: '系统',
};

/** 系统智能体 API 发布基础 URL 前缀 */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api';

/** 输出格式 Tag 颜色 */
export const OUTPUT_FORMAT_COLOR: Record<string, string> = {
  JSON: 'blue',
  TEXT: 'default',
  XML: 'purple',
};

/** 超时策略选项 */
export const TIMEOUT_STRATEGY_OPTIONS = [
  { value: 'auto_reject', label: '超时自动驳回' },
  { value: 'auto_approve', label: '超时自动通过' },
  { value: 'escalate', label: '超时升级上级' },
];

export const TIMEOUT_LABEL: Record<string, string> = {
  auto_reject: '超时自动驳回',
  auto_approve: '超时自动通过',
  escalate: '超时升级上级',
};

export const TIMEOUT_COLOR: Record<string, string> = {
  auto_reject: 'red',
  auto_approve: 'green',
  escalate: 'orange',
};

/** 资源类型展示配置 */
export const RESOURCE_TYPE_META: Record<string, { label: string; icon: string }> = {
  SKILL: { label: 'SKILL', icon: '🔧' },
  MCP_SERVICE: { label: 'MCP', icon: '🔌' },
  KNOWLEDGE_BASE: { label: '知识库', icon: '📚' },
  TOOL: { label: '工具', icon: '🛠️' },
  DATASET: { label: '数据集', icon: '🗂️' },
};

/** Tab 定义 */
export const TABS = [
  { key: 'overview', label: '📋 概览' },
  { key: 'resources', label: '🧩 资源绑定' },
  { key: 'config', label: '⚙️ 配置参数' },
  { key: 'version', label: '📦 版本历史' },
  { key: 'api', label: '🔌 API 管理' },
];


