/**
 * @file usePermission Hook
 * @description 权限与角色校验
 * @author wang.zhen
 * @since 1.0.0
 */
import { useCallback } from 'react';
import { useAuthStore } from '@/stores/authStore';

/** 权限校验 */
export function usePermission() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const hasRole = useAuthStore((s) => s.hasRole);
  const user = useAuthStore((s) => s.user);

  /** 是否拥有全部指定权限 */
  const hasAllPermissions = useCallback(
    (codes: string[]) => codes.every((c) => hasPermission(c)),
    [hasPermission],
  );

  /** 是否拥有任意指定权限 */
  const hasAnyPermission = useCallback(
    (codes: string[]) => codes.some((c) => hasPermission(c)),
    [hasPermission],
  );

  /** 是否拥有任意指定角色 */
  const hasAnyRole = useCallback((codes: string[]) => codes.some((c) => hasRole(c)), [hasRole]);

  /** 是否超级管理员 */
  const isAdmin = !!user?.roles?.some((r: string) => r === 'SUPER_ADMIN' || r === 'PLATFORM_ADMIN');

  return { hasPermission, hasRole, hasAllPermissions, hasAnyPermission, hasAnyRole, isAdmin };
}

export default usePermission;