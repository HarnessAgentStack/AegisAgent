/**
 * @file 技能相关 API 客户端
 * @description 会话内 @SKILL 选择器数据接口（对应后端 /api/runtime/skill）
 * @author wang.zhen
 * @since 1.1.0
 */
import { http } from './request';
import type { SkillOption, SkillCreateDraftRequest, SkillDraftResult, SkillMetadataResponse, SkillDebugResult, SkillPackageResult } from '@/types/session';
import { useAuthStore } from '@/stores/authStore';

/** 可用技能查询（@SKILL 选择器） */
export async function getAvailableSkills(keyword?: string, agentId?: string): Promise<SkillOption[]> {
  const authState = useAuthStore.getState();
  const params: Record<string, unknown> = {};
  if (keyword) params.keyword = keyword;
  if (agentId) params.agentId = agentId;
  // dev 环境补传租户/用户，便于网关注入缺失时仍能按租户过滤
  if (authState.user?.tenantId != null) params.tenantId = authState.user.tenantId;
  if (authState.user?.id != null) params.userId = authState.user.id;
  return http.get<SkillOption[]>('/runtime/skill/available', { params });
}

/** 创建技能草稿（对话方式） */
export async function createSkillDraft(req: SkillCreateDraftRequest): Promise<SkillDraftResult> {
  return http.post<SkillDraftResult>('/runtime/skill/draft', req);
}

/** 获取技能元数据 */
export async function getSkillMetadata(id: string): Promise<SkillMetadataResponse> {
  return http.get<SkillMetadataResponse>(`/runtime/skill/${id}/metadata`);
}

/** 调试技能 */
export async function debugSkill(id: string, testInputs?: Record<string, unknown>): Promise<SkillDebugResult> {
  return http.post<SkillDebugResult>(`/runtime/skill/${id}/debug`, testInputs);
}

/** 诊断技能 */
export async function diagnoseSkill(id: string): Promise<SkillDebugResult> {
  return http.post<SkillDebugResult>(`/runtime/skill/${id}/diagnose`);
}

/** 打包技能 */
export async function packageSkill(id: string): Promise<SkillPackageResult> {
  return http.post<SkillPackageResult>(`/runtime/skill/${id}/package`);
}

/** 下载技能包 */
export async function downloadSkillPackage(id: string): Promise<Blob> {
  return http.get<Blob>(`/runtime/skill/${id}/package/download`, { responseType: 'blob' });
}

/** 获取 SKILL.md 内容 */
export async function getSkillMd(id: string): Promise<{ content: string }> {
  return http.get<{ content: string }>(`/runtime/skill/${id}/skillmd`);
}

/** P0-ITEM-4：提交技能审核 */
export async function submitSkillForReview(id: string): Promise<{ success: boolean; message: string; submitted: boolean; reviewId?: number }> {
  return http.post<{ success: boolean; message: string; submitted: boolean; reviewId?: number }>(`/runtime/skill/${id}/submit-review`);
}
