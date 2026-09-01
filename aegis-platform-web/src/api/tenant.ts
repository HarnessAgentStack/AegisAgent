/**
 * @file 租户管理 API 客户端
 * @description 封装租户分页查询、详情、新增、更新、配额管理、冻结/解冻、用量查询等接口
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { PageResult } from './types';
import type { Tenant, TenantQuota, TenantUsage } from '@/types/tenant';

const BASE = '/admin/tenant';

/** 租户分页查询参数 */
export interface TenantPageParams {
  /** 搜索关键词（编码 / 名称） */
  keyword?: string;
  /** 状态筛选：NORMAL / FROZEN */
  status?: string;
  /** 页码（从 1 开始） */
  page: number;
  /** 每页条数 */
  size: number;
}

/** 租户列表（分页） */
export function getTenantPage(params: TenantPageParams): Promise<PageResult<Tenant>> {
  return http.get<PageResult<Tenant>>(`${BASE}/page`, { params });
}

/** 当前用户可访问的租户列表（用于租户切换） */
export function getAccessibleTenants(): Promise<Tenant[]> {
  return http.get<Tenant[]>(`${BASE}/accessible`);
}

/** 租户详情 */
export function getTenantDetail(id: string): Promise<Tenant> {
  return http.get<Tenant>(`${BASE}/${id}`);
}

/** 创建租户 */
export function createTenant(data: Partial<Tenant>): Promise<Tenant> {
  return http.post<Tenant>(BASE, data);
}

/** 更新租户 */
export function updateTenant(id: string, data: Partial<Tenant>): Promise<Tenant> {
  return http.put<Tenant>(`${BASE}/${id}`, data);
}

/** 更新租户配额 */
export function updateTenantQuota(id: string, quota: Partial<TenantQuota>): Promise<TenantQuota> {
  return http.put<TenantQuota>(`${BASE}/${id}/quota`, quota);
}

/** 冻结租户 */
export function freezeTenant(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/freeze`);
}

/** 解冻租户 */
export function unfreezeTenant(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/unfreeze`);
}

/** 查询租户用量 */
export function getTenantUsage(id: string): Promise<TenantUsage> {
  return http.get<TenantUsage>(`${BASE}/${id}/usage`);
}
