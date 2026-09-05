/**
 * @file 技能统一字段契约
 * @description 创建/编辑/详情三处面板共享的可编辑字段定义、提取与收集工具。
 *              消除三面板字段不一致的根因——单一来源（Single Source of Truth）。
 * @author wang.zhen
 * @since 1.2.0
 */
import type { Skill } from '@/types/resource';

/** 所有可编辑字段的单一来源 —— 创建/编辑/详情三处共享 */
export interface SkillEditableFields {
  skillName: string;
  category: string;
  description: string;
  instructions: string;
  skillType: string;
  securityLevel: string;
  tags: string[];
  bindingTools: string;
  inputs: string;
  outputs: string;
}

/** 合法安全等级枚举值（与后端 SecurityLevel 对齐） */
const VALID_SECURITY_LEVELS = ['L1', 'L2', 'L3', 'L4'];

/** 合法技能类型枚举值（与后端 SkillType 对齐） */
const VALID_SKILL_TYPES = ['ATOMIC', 'COMPOSITE'];

/**
 * 从后端 Skill VO 提取可编辑字段（编辑面板回填 / 详情页展示用）。
 * tags 从 JSON 字符串解析为 string[]。
 */
export function extractEditableFields(skill: Partial<Skill> & Record<string, unknown>): SkillEditableFields {
  const rawTags = typeof skill.tags === 'string' ? safeParseArray(skill.tags) : (skill.tags as string[] | undefined) ?? [];
  return {
    skillName: (skill.skillName as string) ?? '',
    category: (skill.category as string) ?? '',
    description: (skill.description as string) ?? '',
    instructions: (skill.instructions as string) ?? '',
    skillType: (skill.skillType as string) ?? 'ATOMIC',
    securityLevel: (skill.securityLevel as string) ?? 'L1',
    tags: rawTags,
    bindingTools: (skill.bindingTools as string) ?? '[]',
    inputs: (skill.inputs as string) ?? '{}',
    outputs: (skill.outputs as string) ?? '{}',
  };
}

/**
 * 收集提交 payload（创建/编辑统一）。
 *
 * <p>枚举字段做有效性过滤，无效值不提交（避免后端反序列化 400）。
 * 返回的 payload 可直接传给 skillApi.update。
 */
export function collectEditablePayload(fields: SkillEditableFields): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    skillName: fields.skillName,
    description: fields.description,
    instructions: fields.instructions,
    bindingTools: fields.bindingTools,
    inputs: fields.inputs,
    outputs: fields.outputs,
    tags: JSON.stringify(fields.tags ?? []),
  };
  if (fields.category) payload.category = fields.category;
  if (VALID_SKILL_TYPES.includes(fields.skillType)) payload.skillType = fields.skillType;
  if (VALID_SECURITY_LEVELS.includes(fields.securityLevel)) payload.securityLevel = fields.securityLevel;
  return payload;
}

function safeParseArray(jsonStr: string): string[] {
  try {
    const arr = JSON.parse(jsonStr);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}
