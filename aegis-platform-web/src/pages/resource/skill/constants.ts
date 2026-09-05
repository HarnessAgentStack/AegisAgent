/**
 * @file 技能管理 - 常量与类型
 * @description 选项常量（安全级别、类型、分类）、状态映射、JSON 解析工具
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

/** 技能类型 */
export type SkillType = 'ATOMIC' | 'COMPOSITE';

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
