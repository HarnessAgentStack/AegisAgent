/**
 * @file 技能管理 - 常量、类型与转换工具
 * @description 选项常量（安全级别、类型、分类、参数类型）、状态映射、
 *              可视化参数定义 ↔ JSON 字符串转换函数
 * @author wang.zhen
 * @since 1.0.0
 */
import { SecurityLevel } from '@/types/enum';
import { safeJsonParse } from '@/utils/number';

/** SKILL 卡片图标背景色 */
export const ICON_BG: Record<string, string> = { ATOMIC: '#e6f4ff', COMPOSITE: '#fef3c7' };
/** SKILL 卡片图标 */
export const ICON: Record<string, string> = { ATOMIC: '🛠️', COMPOSITE: '⚡' };

/** 安全级别 ->卡片标签文案 / 颜色 */
export const SECURITY_TAG: Record<SecurityLevel, { text: string; color: string }> = {
  [SecurityLevel.L1]: { text: 'L1 公开', color: 'green' },
  [SecurityLevel.L2]: { text: 'L2 内部', color: 'blue' },
  [SecurityLevel.L3]: { text: 'L3 机密', color: 'orange' },
  [SecurityLevel.L4]: { text: 'L4 绝密', color: 'red' },
};

/** 安全级别筛选选项 */
export const SECURITY_OPTIONS: { label: string; value: string }[] = [
  { label: '全部级别', value: 'all' },
  { label: 'L1 公开', value: SecurityLevel.L1 },
  { label: 'L2 内部', value: SecurityLevel.L2 },
  { label: 'L3 机密', value: SecurityLevel.L3 },
  { label: 'L4 绝密', value: SecurityLevel.L4 },
];

/** 安全级别表单选项（不含"全部"） */
export const SECURITY_FORM_OPTIONS = SECURITY_OPTIONS.filter((o) => o.value !== 'all');

/** 安全级别选项（含 L1-L4，用于 SkillStudioPanel 等场景） */
export const SECURITY_LEVEL_OPTIONS = SECURITY_OPTIONS.filter((o) => o.value !== 'all');

/** 技能类型 */
export type SkillType = 'ATOMIC' | 'COMPOSITE';

/** 技能类型选项 */
export const SKILL_TYPE_OPTIONS = [
  { value: 'ATOMIC', label: '原子技能' },
  { value: 'COMPOSITE', label: '组合技能' },
];

/** 技能类型 ->Tag 颜色 / 文案 */
export const SKILL_TYPE_TAG: Record<SkillType, { color: string; text: string }> = {
  ATOMIC: { color: 'blue', text: '原子技能' },
  COMPOSITE: { color: 'purple', text: '组合技能' },
};

/** 技能分类选项 */
export const CATEGORY_OPTIONS = [
  { value: '问答型', label: '问答型' },
  { value: '执行型', label: '执行型' },
  { value: '分析型', label: '分析型' },
  { value: '创作型', label: '创作型' },
  { value: '数据处理', label: '数据处理' },
];

/** 参数类型 */
export type ParamType = 'string' | 'number' | 'boolean' | 'object';

/** 参数类型选项 */
export const PARAM_TYPE_OPTIONS: { value: ParamType; label: string }[] = [
  { value: 'string', label: 'string' },
  { value: 'number', label: 'number' },
  { value: 'boolean', label: 'boolean' },
  { value: 'object', label: 'object' },
];

/** 可视化参数定义（入参/出参单行） */
export interface ParamDef {
  name: string;
  type: ParamType;
  required: boolean;
  description?: string;
}

/** 创建/编辑表单值 */
export interface SkillFormValues {
  skillCode: string;
  skillName: string;
  description?: string;
  instructions?: string;
  skillType: SkillType;
  category: string;
  securityLevel: SecurityLevel;
  inputs: ParamDef[];
  outputs: ParamDef[];
  bindingTools: string;
  mappingConfig: string;
}

// ===== 转换工具函数：可视化 ↔ JSON 字符串 =====

/** 参数定义列表 -> JSON Schema 字符串 */
export function paramsToSchema(params?: ParamDef[]): string | undefined {
  if (!params || params.length === 0) return undefined;
  const properties: Record<string, { type: string; description?: string }> = {};
  const required: string[] = [];
  params.forEach((p) => {
    if (!p.name) return;
    const prop: { type: string; description?: string } = { type: p.type };
    if (p.description) prop.description = p.description;
    properties[p.name] = prop;
    if (p.required) required.push(p.name);
  });
  return JSON.stringify({ type: 'object', properties, required });
}

/** JSON Schema 字符串 -> 参数定义列表 */
export function schemaToParams(schemaStr?: string): ParamDef[] {
  if (!schemaStr) return [];
  const schema = safeJsonParse<{
    properties?: Record<string, { type?: ParamType; description?: string }>;
    required?: string[];
  }>(schemaStr);
  if (!schema) return [];
  const props = schema.properties || {};
  const required = schema.required || [];
  return Object.entries(props).map(([name, def]) => ({
    name,
    type: (def?.type as ParamType) || 'string',
    required: required.includes(name),
    description: def?.description,
  }));
}

/** 多行文本 -> JSON 数组字符串（每行一个元素） */
export function linesToJsonArray(text?: string): string | undefined {
  if (!text) return undefined;
  const arr = text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);
  return arr.length === 0 ? undefined : JSON.stringify(arr);
}

/** JSON 数组字符串 -> 多行文本 */
export function jsonArrayToLines(jsonStr?: string): string {
  if (!jsonStr) return '';
  const arr = safeJsonParse<unknown[]>(jsonStr);
  return Array.isArray(arr) ? arr.join('\n') : '';
}

/** 多行文本（key=value）-> JSON 对象字符串，支持"工具.参数=value"嵌套 */
export function linesToJsonObject(text?: string): string | undefined {
  if (!text) return undefined;
  const obj: Record<string, unknown> = {};
  text.split('\n').forEach((line) => {
    const trimmed = line.trim();
    if (!trimmed) return;
    const eqIdx = trimmed.indexOf('=');
    if (eqIdx === -1) return;
    const keyPath = trimmed.slice(0, eqIdx).trim();
    const value = trimmed.slice(eqIdx + 1).trim();
    const dotIdx = keyPath.indexOf('.');
    if (dotIdx === -1) {
      obj[keyPath] = value;
    } else {
      const tool = keyPath.slice(0, dotIdx);
      const param = keyPath.slice(dotIdx + 1);
      const bucket = (obj[tool] as Record<string, unknown>) || {};
      bucket[param] = value;
      obj[tool] = bucket;
    }
  });
  return Object.keys(obj).length === 0 ? undefined : JSON.stringify(obj);
}

/** JSON 对象字符串 -> 多行文本（key=value） */
export function jsonObjectToLines(jsonStr?: string): string {
  if (!jsonStr) return '';
  const obj = safeJsonParse<Record<string, unknown>>(jsonStr);
  if (!obj) return '';
  const lines: string[] = [];
  Object.entries(obj).forEach(([key, value]) => {
    if (value !== null && typeof value === 'object') {
      Object.entries(value as Record<string, unknown>).forEach(([subKey, subValue]) => {
        lines.push(`${key}.${subKey}=${subValue}`);
      });
    } else {
      lines.push(`${key}=${value}`);
    }
  });
  return lines.join('\n');
}

/** 解析 JSON 数组字符串为字符串数组 */
export function parseJsonArray(jsonStr?: string): string[] {
  if (!jsonStr) return [];
  const arr = safeJsonParse<unknown[]>(jsonStr);
  return Array.isArray(arr) ? (arr as string[]) : [];
}

/** 解析 JSON 对象字符串 */
export function parseJsonObject(jsonStr?: string): Record<string, unknown> {
  if (!jsonStr) return {};
  const obj = safeJsonParse<Record<string, unknown>>(jsonStr);
  return obj !== null && typeof obj === 'object'
    ? obj
    : {};
}
