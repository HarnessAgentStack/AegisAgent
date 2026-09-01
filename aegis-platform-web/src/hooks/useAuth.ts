/**
 * @file useAuth Hook
 * @description 认证状态读取与登录 / 登出操作封装。
 *
 * 对接后端 POST /api/admin/auth/login 接口进行真实认证，
 * JWT Token 由后端签发、网关校验。开发环境仍使用 mock 作为降级。
 *
 * @author wang.zhen
 * @since 1.0.0
 */
import { useCallback } from 'react';
import { useAuthStore } from '@/stores/authStore';
import type { LoginParams, LoginResult, UserInfo } from '@/types/user';
import { STORAGE_KEY } from '@/utils/constants';
import { storage } from '@/utils/storage';
import { authApi, type LoginResponse } from '@/api/auth';

/** 认证状态与操作 */
export function useAuth() {
  const { token, user, isAuthenticated, setAuth, logout } = useAuthStore();

  /** 登录（对接真实后端 API） */
  const login = useCallback(
    async (params: LoginParams): Promise<LoginResult> => {
      // 调用后端真实登录接口（响应拦截器已解包 Result，resp 即业务数据）
      const resp: LoginResponse = await authApi.login({
        username: params.username,
        password: params.password,
        tenantCode: params.tenantCode,
      });

      // 后端未返回 user 信息时拒绝登录，避免降级为超管
      if (!resp.user) {
        throw new Error('登录失败：服务器未返回用户信息');
      }

      const userInfo: UserInfo = {
        id: String(resp.user.id),
        username: resp.user.username,
        nickname: resp.user.nickname ?? resp.user.username,
        tenantId: String(resp.user.tenantId),
        roles: resp.user.roles,
        permissions: resp.user.permissions,
        status: resp.user.status,
        lastLoginAt: resp.user.lastLoginAt,
      };

      setAuth(resp.token, resp.refreshToken ?? null, userInfo);
      storage.set(STORAGE_KEY.TENANT_ID, userInfo.tenantId);

      return {
        token: resp.token,
        refreshToken: resp.refreshToken,
        expiresIn: resp.expiresIn,
        user: userInfo,
      };
    },
    [setAuth],
  );

  /** 登出（清空本地状态） */
  const signOut = useCallback(async (): Promise<void> => {
    logout();
  }, [logout]);

  return { token, user, isAuthenticated, login, signOut, logout };
}

export default useAuth;
