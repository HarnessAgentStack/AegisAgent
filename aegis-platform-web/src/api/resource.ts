/**
 * @file 资源相关 API 客户端
 * @description 封装技能、知识库、MCP、工具的管理端 CRUD 接口（/admin/resource）
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type {
  KnowledgeBase,
  KbDocument,
  KbChunk,
  UploadApplyResult,
  Skill,
  McpServer,
  Tool,
  ToolVO,
  ResourceQueryParams,
  SkillVersion,
  SkillVersionDiff,
  SubscribedSkill,
} from '@/types/resource';

/** MyBatis-Plus IPage 分页结果 */
export interface IPage<T> {
  records?: T[];
  total?: number;
  size?: number;
  current?: number;
  pages?: number;
}

/** 兼容分页结果或数组 */
export type ListResult<T> = IPage<T> | T[];

/** 从分页结果中提取列表 */
export function extractList<T>(res: ListResult<T>): T[] {
  return Array.isArray(res) ? res : res.records ?? [];
}

/** 从分页结果中提取总条数 */
export function extractTotal<T>(res: ListResult<T>): number {
  return Array.isArray(res) ? res.length : res.total ?? 0;
}

// ===== 知识库 =====

const KB_BASE = '/admin/resource/kb';
const KB_USER_BASE = '/resource/kb';

/** 知识库快捷方法 */
export const knowledgeApi = {
  /** 知识库分页列表 */
  list: (params: ResourceQueryParams) =>
    http.get<ListResult<KnowledgeBase>>(`${KB_BASE}/page`, { params }),
  /** 知识库详情 */
  detail: (id: string) => http.get<KnowledgeBase>(`${KB_BASE}/${id}`),
  /** 创建知识库 */
  create: (data: Partial<KnowledgeBase>) => http.post<KnowledgeBase>(KB_BASE, data),
  /** 更新知识库 */
  update: (id: string, data: Partial<KnowledgeBase>) =>
    http.put<void>(`${KB_BASE}/${id}`, data),
  /** 删除知识库 */
  remove: (id: string) => http.delete<void>(`${KB_BASE}/${id}`),
  /** 提交审核 */
  submitReview: (id: string) => http.post<void>(`${KB_BASE}/${id}/submit-review`),
  /** 直接发布 */
  publish: (id: string) => http.post<void>(`${KB_BASE}/${id}/publish`),
  /** 文档列表 */
  listDocuments: (kbId: string, params?: { page?: number; size?: number }) =>
    http.get<ListResult<KbDocument>>(`${KB_BASE}/${kbId}/documents`, { params }),
  /** 申请预签名上传 URL */
  uploadApply: (kbId: string, params: { fileName: string; fileSize: number }) =>
    http.post<UploadApplyResult>(`${KB_BASE}/${kbId}/upload/apply`, null, { params }),
  /** 通知上传完成 */
  uploadNotify: (kbId: string, params: { objectKey: string }) =>
    http.post<void>(`${KB_BASE}/${kbId}/upload/notify`, null, { params }),
  /** 直接上传文件（小文件便捷接口） */
  uploadFile: (kbId: string, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    // 关键：不设置 Content-Type，让请求拦截器检测 FormData 并移除默认的 application/json
    // 浏览器会自动添加正确的 multipart/form-data boundary
    return http.post<KbDocument>(`${KB_BASE}/${kbId}/upload/file`, formData);
  },
  /** 删除文档 */
  deleteDocument: (kbId: string, docId: string) =>
    http.delete<void>(`${KB_BASE}/${kbId}/documents/${docId}`),
  /** 重新处理文档（扫描+切片） */
  reprocessDocument: (kbId: string, docId: string) =>
    http.post<KbDocument>(`${KB_BASE}/${kbId}/documents/${docId}/reprocess`),
  /** 文档切片列表 */
  listChunks: (kbId: string, docId: string, params?: { page?: number; size?: number }) =>
    http.get<ListResult<KbChunk>>(`${KB_BASE}/${kbId}/documents/${docId}/chunks`, { params }),

  // --- 用户侧（/resource/kb） ---
  /** 知识库市场列表（仅 PUBLISHED） */
  market: (params?: ResourceQueryParams) =>
    http.get<ListResult<KnowledgeBase>>(`${KB_USER_BASE}/market`, { params }),
  /** 我的知识库列表 */
  mine: (params?: ResourceQueryParams) =>
    http.get<ListResult<KnowledgeBase>>(`${KB_USER_BASE}/mine`, { params }),
  /** 用户侧知识库详情 */
  userDetail: (id: string) => http.get<KnowledgeBase>(`${KB_USER_BASE}/${id}`),
  /** 用户侧发布知识库 */
  userPublish: (id: string) => http.post<{ published: boolean }>(`${KB_USER_BASE}/${id}/publish`),
  /** 用户侧提交审核 */
  userSubmitReview: (id: string) => http.post<{ submitted: boolean }>(`${KB_USER_BASE}/${id}/submit-review`),
  /** 订阅知识库 */
  subscribe: (id: string) => http.post<{ subscribed: boolean }>(`${KB_USER_BASE}/${String(id)}/subscribe`),
  /** 取消订阅知识库 */
  unsubscribe: (id: string) => http.post<{ subscribed: boolean }>(`${KB_USER_BASE}/${String(id)}/unsubscribe`),
  /** 查询订阅状态 */
  subStatus: (id: string) => http.get<{ subscribed: boolean }>(`${KB_USER_BASE}/${String(id)}/sub-status`),
  /** 批量查询订阅状态（性能优化，替代N+1查询） */
  batchSubStatus: (ids: (string)[]) =>
    http.post<{ subscribedMap: Record<string, boolean>; subscribedCount: number }>(
      `${KB_USER_BASE}/batch-sub-status`, ids.map(String),
    ),
  /** 查询当前用户已订阅的全部知识库ID */
  mySubscriptions: () =>
    http.get<{ subscribedIds: string[]; total: number }>(`${KB_USER_BASE}/my-subscriptions`),
};

// ===== 技能 =====

const SKILL_BASE = '/admin/resource/skill';
const SKILL_USER_BASE = '/resource/skill';

/** 技能快捷方法（管理端） */
export const skillApi = {
  /** 技能分页列表 */
  list: (params: ResourceQueryParams) =>
    http.get<ListResult<Skill>>(`${SKILL_BASE}/page`, { params }),
  /** 技能详情 */
  detail: (id: string) => http.get<Skill>(`${SKILL_BASE}/${id}`),
  /** 创建技能 */
  create: (data: Partial<Skill>) => http.post<Skill>(SKILL_BASE, data),
  /** 更新技能 */
  update: (id: string, data: Partial<Skill>) =>
    http.put<void>(`${SKILL_BASE}/${id}`, data),
  /** 删除技能 */
  remove: (id: string) => http.delete<void>(`${SKILL_BASE}/${id}`),
  /** 提交审核 */
  submitReview: (id: string) => http.post<void>(`${SKILL_BASE}/${id}/submit-review`),
  /** 安全扫描 */
  scan: (id: string) => http.post<ScanResult>(`${SKILL_BASE}/${id}/scan`),

  // --- 用户侧（/resource/skill） ---
  /** 市场列表（用户侧） */
  market: (params: ResourceQueryParams) =>
    http.get<ListResult<Skill>>(`${SKILL_USER_BASE}/market`, { params }),
  /** 我的技能（用户侧） */
  mine: (params?: ResourceQueryParams) =>
    http.get<ListResult<Skill>>(`${SKILL_USER_BASE}/mine`, { params }),
  /** 订阅技能 */
  subscribe: (id: string) =>
    http.post<{ subscribed: boolean }>(`${SKILL_USER_BASE}/${id}/subscribe`),
  /** 取消订阅 */
  unsubscribe: (id: string) =>
    http.post<{ subscribed: boolean }>(`${SKILL_USER_BASE}/${id}/unsubscribe`),
  /** 查询订阅状态 */
  subStatus: (id: string) =>
    http.get<{ subscribed: boolean }>(`${SKILL_USER_BASE}/${id}/sub-status`),
  /** 批量查询订阅状态（P1-ITEM-3：消除技能市场 N+1 查询） */
  batchSubStatus: (ids: string[]) =>
    http.post<Record<string, boolean>>(`${SKILL_USER_BASE}/subscription/batch-status`, ids),

  // --- 生命周期操作（P1-ITEM-2） ---
  /** 退回草稿（PUBLISHED/ARCHIVED -> DRAFT，仅作者可操作） */
  revertDraft: (id: string) =>
    http.post<void>(`${SKILL_BASE}/${id}/revert-draft`),
  /** 归档技能（PUBLISHED -> ARCHIVED，仅作者可操作） */
  archive: (id: string) =>
    http.post<void>(`${SKILL_BASE}/${id}/archive`),

  // --- 版本管理 ---
  /** 发布新版本 */
  publishVersion: (id: string, data: { version: string; releaseNotes?: string }) =>
    http.post<SkillVersion>(`${SKILL_BASE}/${id}/publish`, data),
  /** 回滚到指定版本 */
  rollbackVersion: (id: string, data: { targetVersion: string }) =>
    http.post<SkillVersion>(`${SKILL_BASE}/${id}/rollback`, data),
  /** 灰度发布 */
  grayReleaseVersion: (id: string, data: { version: string; percent: number }) =>
    http.post<SkillVersion>(`${SKILL_BASE}/${id}/gray-release`, data),
  /** 获取版本历史 */
  getVersionHistory: (id: string) =>
    http.get<SkillVersion[]>(`${SKILL_BASE}/${id}/versions`),
  /** 获取版本差异 */
  getVersionDiff: (id: string, fromVersion: string, toVersion: string) =>
    http.get<SkillVersionDiff>(`${SKILL_BASE}/${id}/version-diff`, {
      params: { from: fromVersion, to: toVersion },
    }),

  // --- HITL 审批 ---
  /** 审批通过技能 */
  approveSkill: (id: string, data?: { comment?: string }) =>
    http.post<Skill>(`${SKILL_BASE}/${id}/approve`, data),
  /** 驳回技能 */
  rejectSkill: (id: string, data: { reason: string }) =>
    http.post<Skill>(`${SKILL_BASE}/${id}/reject`, data),

  // --- 用户侧订阅列表 ---
  /** 已订阅技能列表 */
  getSubscribedList: (params?: ResourceQueryParams) =>
    http.get<ListResult<SubscribedSkill>>(`${SKILL_USER_BASE}/subscribed`, { params }),
};

/** 安全扫描结果 */
export interface ScanResult {
  passed: boolean;
  riskLevel: string;
  summary: string;
  issues?: ScanIssue[];
}

/** 扫描问题项 */
export interface ScanIssue {
  dimension: string;
  riskLevel: string;
  keyword?: string;
  message: string;
}

// ===== MCP =====

const MCP_BASE = '/admin/resource/mcp';
const MCP_USER_BASE = '/resource/mcp';

export const mcpApi = {
  // --- MCP 服务 ---
  /** MCP 服务分页列表 */
  listServices: (params?: { page?: number; size?: number }) =>
    http.get<ListResult<McpServer>>(`${MCP_BASE}/services/page`, { params }),
  /** 创建 MCP 服务 */
  createService: (data: unknown) => http.post<void>(`${MCP_BASE}/services`, data),
  /** 更新 MCP 服务 */
  updateService: (id: string, data: unknown) =>
    http.put<void>(`${MCP_BASE}/services/${id}`, data),
  /** 启用 MCP 服务 */
  activateService: (id: string) => http.post<void>(`${MCP_BASE}/services/${id}/activate`),
  /** 禁用 MCP 服务 */
  deactivateService: (id: string) => http.post<void>(`${MCP_BASE}/services/${id}/deactivate`),
  /** 删除 MCP 服务 */
  removeService: (id: string) => http.delete<void>(`${MCP_BASE}/services/${id}`),
  /** 查询 MCP 服务详情（含工具列表） */
  getServiceDetail: (id: string) =>
    http.get<McpServer & { tools?: ToolVO[] }>(`${MCP_BASE}/services/${id}`),
  /** 查询 MCP 服务提供的工具列表（动态获取） */
  getServiceTools: (id: string) =>
    http.get<ToolVO[]>(`${MCP_BASE}/services/${id}/tools`),

  // --- 用户侧 MCP（/resource/mcp） ---
  /** MCP 服务市场（仅 PUBLISHED） */
  marketServices: (params?: { page?: number; size?: number; keyword?: string }) =>
    http.get<ListResult<McpServer>>(`${MCP_USER_BASE}/market`, { params }),
  /** 订阅 MCP 服务（即订即用） */
  subscribeService: (serviceId: string) =>
    http.post<{ subscribed: boolean }>(`${MCP_USER_BASE}/subscribe/${serviceId}`),
  /** 取消订阅 MCP 服务 */
  unsubscribeService: (serviceId: string) =>
    http.delete<void>(`${MCP_USER_BASE}/subscribe/${serviceId}`),
  /** 查询订阅状态 */
  subscribeStatus: (serviceId: string) =>
    http.get<{ subscribed: boolean }>(`${MCP_USER_BASE}/subscribe/${serviceId}/status`),
};

// ===== 工具 =====

const TOOL_BASE = '/admin/tool';

/** 工具快捷方法 */
export const toolApi = {
  /** 工具分页列表（对接后端 ToolController /api/admin/tool/page 端点） */
  page: (params?: { keyword?: string; page?: number; size?: number }) =>
    http.get<{ records: Tool[]; total: number; current: number; size: number }>(`${TOOL_BASE}/page`, { params }),
  /** 工具全量列表（内部走分页端点提取 records，后端无独立 list 端点） */
  list: async (): Promise<Tool[]> => {
    const res = await toolApi.page({ page: 1, size: 1000 });
    return res?.records ?? [];
  },
};
