/**
 * @file 组织架构 API 客户端
 * @description 封装部门树查询、新增、更新、删除接口
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { Department } from '@/types/organization';

const BASE = '/admin/department';

/** 查询部门树 */
export function getDepartmentTree(): Promise<Department[]> {
  return http.get<Department[]>(`${BASE}/tree`);
}

/** 新增部门 */
export function createDepartment(data: Partial<Department>): Promise<Department> {
  return http.post<Department>(BASE, data);
}

/** 更新部门 */
export function updateDepartment(id: string, data: Partial<Department>): Promise<void> {
  return http.put<void>(`${BASE}/${id}`, data);
}

/** 删除部门 */
export function deleteDepartment(id: string): Promise<void> {
  return http.delete<void>(`${BASE}/${id}`);
}
