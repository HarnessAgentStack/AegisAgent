/**
 * @file 智能体相关 API 客户端
 * @description 封装智能体管理、市场、订阅、配置等接口调用。
 *              对应后端 {@code /api/admin/agent}（AgentAdminController）。
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { PageResult } from './types';
import type { Agent, AgentQueryParams, AgentSaveParams } from '@/types/agent';
import type { ModelTier } from '@/types/enum';

/** 智能体 API 路径前缀（对应后端 /api/admin/agent） */
const BASE = '/admin/agent';

/** 分页查询参数（后端用 current/size） */
interface PageQuery {
  current?: number;
  size?: number;
  keyword?: string;
  lifeStatus?: string;
  visibility?: string;
}

/**
 * 获取智能体列表（分页）。
 * 后端返回 mybatis-plus Page 结构（records/total/current/size），此处统一适配为 PageResult。
 */
export async function getAgentList(params: AgentQueryParams): Promise<PageResult<Agent>> {
  const query: PageQuery = {
    current: params.page ?? 1,
    size: params.pageSize ?? 10,
  };
  if (params.lifeStatus) query.lifeStatus = params.lifeStatus;
  if (params.visibility) query.visibility = params.visibility;
  const resp = await http.get<{
    records: Agent[];
    total: number;
    current: number;
    size: number;
  }>(`${BASE}/page`, { params: query });
  return {
    list: resp.records ?? [],
    total: resp.total ?? 0,
    page: resp.current ?? 1,
    pageSize: resp.size ?? 10,
  };
}

/** 获取可订阅智能体列表（市场，仅返回已发布） */
export function getSubscribableAgents(): Promise<Agent[]> {
  return http.get<Agent[]>(`${BASE}/subscribable`);
}

/** 获取智能体详情 */
export function getAgentDetail(id: string): Promise<Agent> {
  return http.get<Agent>(`${BASE}/${id}`);
}

/**
 * 创建智能体（草稿态）。
 * 后端 {@code POST /api/admin/agent} 接收 AgentDef 主体字段。
 * tenantId / authorUserId 由后端从请求头补全。
 */
export function createAgent(params: AgentSaveParams): Promise<string> {
  return http.post<string>(BASE, params);
}

/** 更新智能体 */
export function updateAgent(id: string, params: AgentSaveParams): Promise<void> {
  return http.put<void>(`${BASE}/${id}`, params);
}

/** 更新智能体配置（systemPrompt / modelTier / temperature 等） */
export function updateAgentConfig(id: string, params: {
  systemPrompt?: string;
  modelTier?: ModelTier;
  temperature?: number;
  maxTurns?: number;
  memoryStrategy?: string;
  enabledTools?: string;
}): Promise<void> {
  return http.put<void>(`${BASE}/${id}/config`, params);
}

/** 删除智能体（仅草稿） */
export function deleteAgent(id: string): Promise<void> {
  return http.delete<void>(`${BASE}/${id}`);
}

/**
 * 归档下线
 * （发布入口已统一为「提交审核 → 审核通过即发布」，不再提供独立的 publish 接口）
 */
export function archiveAgent(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/archive`);
}

/** 订阅市场智能体（简化：直接订阅，无需审核） */
export function subscribeAgent(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/subscribe`);
}

/** 取消订阅 */
export function unsubscribeAgent(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/unsubscribe`);
}

/** 智能体统计（用于工作台仪表盘） */
export interface AgentStats {
  /** 草稿数 */
  draft: number;
  /** 已发布数 */
  published: number;
  /** 已归档数 */
  archived: number;
  /** 总数 */
  total: number;
}

/** 查询智能体统计信息 */
export function getAgentStats(): Promise<AgentStats> {
  return http.get<AgentStats>(`${BASE}/stats`);
}

/** 获取当前用户的智能体列表 */
export function getMyAgents(): Promise<Agent[]> {
  return http.get<Agent[]>(`${BASE}/my`);
}

/** 获取当前租户的通用智能体（平台预置，每租户唯一） */
export function getUniversalAgent(): Promise<Agent | null> {
  return http.get<Agent | null>(`${BASE}/universal`);
}

/* ===== 审核发布闭环 API ===== */

/** 提交审核（DRAFT/ACTIVE/REJECTED → REVIEWING） */
export function submitAgentReview(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/submit-review`);
}

/** 审核通过（REVIEWING → PUBLISHED）- 审核员操作 */
export function approveAgentReview(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/approve`);
}

/** 审核驳回（REVIEWING → REJECTED）- 审核员操作 */
export function rejectAgentReview(id: string, reason: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/reject`, { reason });
}

/** 智能体审核记录 */
export interface AgentReviewRecord {
  id: number;
  reviewStatus: string;
  applicantUserId: number;
  reviewerUserId?: number;
  submitTime: string;
  reviewTime?: string;
  rejectReason?: string;
}

/** 获取智能体审核历史 */
export function getAgentReviews(id: string): Promise<AgentReviewRecord[]> {
  return http.get<AgentReviewRecord[]>(`${BASE}/${id}/reviews`);
}

/* ===== 版本管理 API ===== */

/** 智能体版本信息 */
export interface AgentVersionInfo {
  version: string;
  createdAt: string;
  systemPrompt?: string;
  modelTier?: string;
}

/** 获取智能体版本历史 */
export function getAgentVersions(id: string): Promise<AgentVersionInfo[]> {
  return http.get<AgentVersionInfo[]>(`${BASE}/${id}/versions`);
}

/** 获取指定版本配置 */
export function getAgentVersionConfig(id: string, version: string): Promise<Agent> {
  return http.get<Agent>(`${BASE}/${id}/versions/${version}`);
}

/* ===== 版本对比 API ===== */

/** 版本差异字段 */
export interface VersionDiff {
  field: string;
  oldValue: string | null;
  newValue: string | null;
}

/** 获取两版本间差异 */
export function getAgentVersionDiff(id: string, v1: string, v2: string): Promise<VersionDiff[]> {
  return http.get<VersionDiff[]>(`${BASE}/${id}/versions/${v1}/diff/${v2}`);
}
