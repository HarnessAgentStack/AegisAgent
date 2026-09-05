/**
 * @file 系统智能体 API 发布管理接口封装
 * @description 对应后端 /api/admin/agent-api 接口。
 * @author aegis
 * @since 2.0.0
 */
import { http } from './request';
import axios from 'axios';
import type {
  AgentApiConfig,
  AgentApiConfigParams,
  AgentApiKeyInfo,
  AgentApiInvokeResponse,
  AgentApiVersionInfo,
  ApiErrorCode,
  OpenApiSpec,
} from '@/types/agentApi';

/**
 * 系统智能体对外 API 专用 axios 实例（FIX B-2）。
 *
 * 与平台会话 JWT 鉴权域隔离：
 * - 不注入 Authorization: Bearer <平台JWT>，避免噪声头干扰 runtime 鉴权决策；
 * - 不挂全局响应拦截器的 401→跳登录 逻辑，API 凭证失败仅抛业务错误由调用方展示；
 * - 仅携带用户填写的 X-API-Key / Bearer Token（系统智能体凭证）。
 */
const apiRequest = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 120_000,
});

const BASE = '/admin/agent-api';

/** 查询智能体的 API 配置列表 */
export function listAgentApiByAgent(agentId: string): Promise<AgentApiConfig[]> {
  return http.get<AgentApiConfig[]>(BASE, { params: { agentId } });
}

/**
 * 幂等初始化/修复系统智能体的 API 发布配置。
 *
 * 场景：创建/审核链路异常或历史数据缺失导致"已发布却无 API 记录"（详情页显示
 * "API 未配置"）时手动触发自愈：已有记录则启用补齐，缺失则按默认值补建。
 */
export function initAgentApi(agentId: string): Promise<AgentApiConfig> {
  return http.post<AgentApiConfig>(`${BASE}/init/${agentId}`);
}

/** 查询单个 API 配置详情 */
export function getAgentApiDetail(id: string): Promise<AgentApiConfig> {
  return http.get<AgentApiConfig>(`${BASE}/${id}`);
}

/** 更新 API 基本配置 */
export function updateAgentApi(id: string, params: AgentApiConfigParams): Promise<void> {
  return http.put<void>(`${BASE}/${id}`, params);
}

/** 更新 API Schema 配置 */
export function updateAgentApiSchema(id: string, params: AgentApiConfigParams): Promise<void> {
  return http.put<void>(`${BASE}/${id}/schema`, params);
}

/** 启用/禁用 API */
export function updateAgentApiStatus(id: string, enabled: boolean): Promise<void> {
  return http.post<void>(`${BASE}/${id}/status`, null, { params: { enabled } });
}

/** 在线测试 API */
export function testAgentApi(id: string): Promise<AgentApiConfig> {
  return http.post<AgentApiConfig>(`${BASE}/${id}/test`);
}

/** 列出 API Key */
export function listAgentApiKeys(apiId: string): Promise<AgentApiKeyInfo[]> {
  return http.get<AgentApiKeyInfo[]>(`${BASE}/${apiId}/keys`);
}

/** 生成 API Key */
export function generateAgentApiKey(
  apiId: string,
  params: { label?: string; validityType?: string },
): Promise<{ key: string; entity: AgentApiKeyInfo }> {
  return http.post<{ key: string; entity: AgentApiKeyInfo }>(`${BASE}/${apiId}/keys`, params);
}

/** 吊销 API Key */
export function revokeAgentApiKey(keyId: string): Promise<void> {
  return http.post<void>(`${BASE}/keys/${keyId}/revoke`);
}

/** 轮换 API Key */
export function rotateAgentApiKey(
  apiId: string,
  oldKeyId: string,
): Promise<{ key: string; newKey: AgentApiKeyInfo }> {
  return http.post<{ key: string; newKey: AgentApiKeyInfo }>(`${BASE}/${apiId}/rotate-key`, {
    oldKeyId,
  });
}

/** 获取 OpenAPI 规范 */
export function getAgentApiOpenApiSpec(apiId: string): Promise<OpenApiSpec> {
  return http.get<OpenApiSpec>(`${BASE}/${apiId}/openapi.json`);
}

/**
 * 在线测试 API 调用（通过运行时服务）。
 *
 * FIX(B-2): 使用独立 axios 实例 apiRequest，与平台会话 JWT 鉴权域隔离：
 * - 不携带平台 JWT，仅带用户填写的 X-API-Key；
 * - 响应拦截器不触发 401→跳登录，凭证失败由调用方（ApiTester）展示。
 */
export async function testAgentApiInvoke(
  _apiId: string,
  body: Record<string, unknown>,
  apiKey: string,
): Promise<AgentApiInvokeResponse> {
  const resp = await apiRequest.post('/runtime/agent-api/invoke', body, {
    headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json' },
  });
  const data = resp.data;
  if (data && typeof data === 'object' && 'code' in data) {
    const r = data as { code?: number; data?: AgentApiInvokeResponse; message?: string; success?: boolean };
    if (r.code === 200 || r.success === true) return r.data as AgentApiInvokeResponse;
    throw new Error(r.message || `API 调用失败（${r.code}）`);
  }
  return data as AgentApiInvokeResponse;
}

/** 获取 API 错误码定义 */
export function getAgentApiErrorCodes(apiId: string): Promise<ApiErrorCode[]> {
  return http.get<ApiErrorCode[]>(`${BASE}/${apiId}/error-codes`);
}

/** 获取 API 版本信息 */
export function getAgentApiVersion(apiId: string): Promise<AgentApiVersionInfo> {
  return http.get<AgentApiVersionInfo>(`${BASE}/${apiId}/version`);
}

/** 递增 API 版本号 */
export function bumpAgentApiVersion(apiId: string): Promise<AgentApiVersionInfo> {
  return http.post<AgentApiVersionInfo>(`${BASE}/${apiId}/bump-version`);
}
