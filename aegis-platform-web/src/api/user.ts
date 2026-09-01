/**
 * @file 用户管理 API 客户端
 * @description 封装用户分页查询、详情、新增、更新、角色分配、禁用/启用接口
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { PageResult } from './types';
import type { User } from '@/types/organization';

const BASE = '/admin/user';

/** 用户分页查询参数 */
export interface UserPageParams {
  /** 搜索关键词（用户名 / 真实姓名 / 工号） */
  keyword?: string;
  /** 部门 ID */
  deptId?: string;
  /** 状态：NORMAL / DISABLED */
  status?: string;
  /** 页码（从 1 开始） */
  page: number;
  /** 每页条数 */
  size: number;
}

/** 用户列表（分页） */
export function getUserPage(params: UserPageParams): Promise<PageResult<User>> {
  return http.get<PageResult<User>>(`${BASE}/page`, { params });
}

/** 用户详情 */
export function getUserDetail(id: string): Promise<User> {
  return http.get<User>(`${BASE}/${id}`);
}

/** 创建用户 */
export function createUser(data: Partial<User> & { password?: string }): Promise<User> {
  return http.post<User>(BASE, data);
}

/** 更新用户 */
export function updateUser(id: string, data: Partial<User>): Promise<void> {
  return http.put<void>(`${BASE}/${id}`, data);
}

/** 分配用户角色 — 后端 @RequestBody List<Long> 要求纯数组，不能包装成对象 */
export function assignUserRoles(id: string, roleIds: string[]): Promise<void> {
  return http.post<void>(`${BASE}/${id}/roles`, roleIds);
}

/** 禁用用户 */
export function disableUser(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/disable`);
}

/** 启用用户 */
export function enableUser(id: string): Promise<void> {
  return http.post<void>(`${BASE}/${id}/enable`);
}
