/**
 * @file 审核中心 API 客户端
 * @description 封装资源审核提交、审批、查询等接口，对接 ReviewController
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { Page } from './security';

/** 分页查询参数 */
export interface PageQuery {
  page?: number;
  size?: number;
  [key: string]: unknown;
}

const BASE = '/admin/review';

/** 审核记录 */
export interface ResourceReview {
  id?: string;
  resourceType?: string;
  /** 资源子类型（如 SYSTEM 系统智能体） */
  resourceSubType?: string;
  resourceId?: string;
  resourceName?: string;
  reviewStatus?: string;
  applicantUserId?: string;
  applicantName?: string;
  approverUserId?: string;
  approverName?: string;
  submitTime?: string;
  reviewTime?: string;
  finishTime?: string;
  rejectReason?: string;
  changeSummary?: string;
  version?: string;
  securityLevel?: number;
  [key: string]: unknown;
}

/** 待审核列表查询 */
export function getPendingReviews(params?: PageQuery & { resourceType?: string }): Promise<Page<ResourceReview>> {
  return http.get<Page<ResourceReview>>(`${BASE}/pending`, { params });
}

/** 我的提交列表查询 */
export function getMyReviews(params?: PageQuery): Promise<Page<ResourceReview>> {
  return http.get<Page<ResourceReview>>(`${BASE}/mine`, { params });
}

/** 提交审核 */
export function submitReview(data: { resourceType: string; resourceId: string }): Promise<number> {
  return http.post<number>(`${BASE}/submit`, data);
}

/** 审核通过 */
export function approveReview(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/approve`);
}

/** 审核驳回 */
export function rejectReview(id: string, reason: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/reject`, { reason });
}
