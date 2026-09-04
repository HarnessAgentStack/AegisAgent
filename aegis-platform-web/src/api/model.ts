/**
 * @file 模型相关 API 客户端
 * @description 封装模型供应商、模型实例、限流配置等管理端接口
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';

const BASE = '/admin/model';

/** 分页响应 */
interface Page<T> {
  records?: T[];
  total?: number;
}

/** 供应商 VO */
export interface ProviderVO {
  id?: string;
  providerCode?: string;
  providerName?: string;
  status?: string;
  endpoint?: string;
  modelCount?: number;
  apiKeyMasked?: string;
}

/** 模型实例 VO */
export interface ModelDefVO {
  id?: string;
  modelCode?: string;
  modelName?: string;
  modelType?: string;
  tier?: string;
  providerId?: string;
  /** 供应商名称（后端关联填充） */
  providerName?: string;
  /** 供应商编码（后端关联填充） */
  providerCode?: string;
  contextWindow?: number;
  status?: string;
  /** 模型能力矩阵（后端 JSON 字符串，含 rag.similarityHint 等） */
  capabilities?: string;
}

/** 限流配置 VO */
export interface RateLimitVO {
  id?: string;
  scope?: string;
  scopeTargetId?: string;
  lightQps?: number;
  standardQps?: number;
  strongQps?: number;
  totalQps?: number;
  action?: string;
}

/** 模型管理 API */
export const modelApi = {
  // ===== 供应商 =====
  /** 供应商列表（分页或数组） */
  listProviders: () => http.get<ProviderVO[] | Page<ProviderVO>>(`${BASE}/providers`),
  /** 新增供应商，返回新建的供应商ID */
  createProvider: (data: unknown) => http.post<string>(`${BASE}/providers`, data),
  /** 更新供应商 */
  updateProvider: (id: string, data: unknown) =>
    http.put<unknown>(`${BASE}/providers/${id}`, data),
  /** 测试供应商连接 */
  testProvider: (id: string) => http.post<boolean>(`${BASE}/providers/${id}/test`),

  // ===== 模型实例 =====
  /** 模型实例列表（管理端，需 PLATFORM_ADMIN/TENANT_ADMIN） */
  listDefs: () => http.get<ModelDefVO[]>(`${BASE}/defs`),
  /** 启用中的嵌入模型列表（用户侧只读，所有已认证用户可访问，知识库创建等场景） */
  listEnabledEmbeddingDefs: () => http.get<ModelDefVO[]>('/admin/model-user/defs'),
  /** 新增模型实例，返回新建的模型ID */
  createDef: (data: unknown) => http.post<string>(`${BASE}/defs`, data),
  /** 更新模型实例 */
  updateDef: (id: string, data: unknown) => http.put<unknown>(`${BASE}/defs/${id}`, data),

  // ===== 限流配置 =====
  /** 限流配置列表 */
  listRateLimits: () => http.get<RateLimitVO[]>(`${BASE}/rate-limits`),
  /** 配置（创建/更新）限流 */
  saveRateLimit: (data: unknown) => http.put<unknown>(`${BASE}/rate-limits`, data),
};
