/**
 * @file 角色权限 API 客户端
 * @description 封装角色列表查询、新增、更新、删除接口
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { Role } from '@/types/organization';

const BASE = '/admin/role';

/** 查询角色列表 */
export function getRoleList(): Promise<Role[]> {
  return http.get<Role[]>(`${BASE}/list`);
}

/** 新增角色 */
export function createRole(data: Partial<Role>): Promise<Role> {
  return http.post<Role>(BASE, data);
}

/** 更新角色 */
export function updateRole(id: string, data: Partial<Role>): Promise<void> {
  return http.put<void>(`${BASE}/${id}`, data);
}

/** 删除角色 */
export function deleteRole(id: string): Promise<void> {
  return http.delete<void>(`${BASE}/${id}`);
}
