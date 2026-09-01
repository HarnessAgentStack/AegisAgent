/**
 * @file 权限管理 API
 * @description 权限树查询 + 角色权限分配
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';

/** 权限项（扁平，前端组装树） */
export interface Permission {
  id: string;
  tenantId?: string;
  permissionCode: string;
  permissionName: string;
  permissionType?: string;
  parentId?: string | null;
  sort?: number;
  status?: string;
}

const PERMISSION_BASE = '/admin/permission';

/** 查询全部权限（扁平列表，前端组装树） */
export function getPermissionTree(): Promise<Permission[]> {
  return http.get<Permission[]>(`${PERMISSION_BASE}/tree`);
}

/** 查询角色已分配的权限ID列表 */
export function getRolePermissionIds(roleId: string): Promise<string[]> {
  return http.get<string[]>(`${PERMISSION_BASE}/role/${roleId}`);
}

/** 分配角色权限（先删后增） */
export function assignRolePermissions(roleId: string, permissionIds: string[]): Promise<void> {
  return http.put<void>(`${PERMISSION_BASE}/role/${roleId}`, { permissionIds });
}
