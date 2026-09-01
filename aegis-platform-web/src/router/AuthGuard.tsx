/**
 * @file 路由守卫
 * @description 登录校验与权限校验，未授权跳转登录页，无权限跳工作台
 * @author wang.zhen
 * @since 1.0.0
 */
import type { FC, ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { usePermission } from '@/hooks/usePermission';
import { ROUTE_PATH } from '@/utils/constants';

/** 路由守卫属性 */
interface AuthGuardProps {
  children: ReactNode;
  requiredPermissions?: string[];
}

/**
 * 路由守卫组件
 * 校验登录态与权限，未登录跳登录页，无权限跳工作台
 */
export const AuthGuard: FC<AuthGuardProps> = ({ children, requiredPermissions }) => {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const token = useAuthStore((s) => s.token);
  const location = useLocation();
  const { hasAnyPermission } = usePermission();

  // Token 基本有效性检查：存在且不是 mock-token 前缀
  const isTokenValid = isAuthenticated && !!token && !token.startsWith('mock-token-');

  if (!isTokenValid) {
    return <Navigate to={ROUTE_PATH.LOGIN} state={{ from: location }} replace />;
  }

  if (
    requiredPermissions &&
    requiredPermissions.length > 0 &&
    !hasAnyPermission(requiredPermissions)
  ) {
    return <Navigate to={ROUTE_PATH.WORKBENCH} replace />;
  }

  return <>{children}</>;
};

export default AuthGuard;