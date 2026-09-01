/**
 * @file 认证相关 API 客户端
 * @description 封装登录、刷新令牌、获取当前用户信息等认证端点
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { UserStatus } from '@/types/enum';

/** 登录请求参数 */
export interface LoginParams {
  username: string;
  password: string;
  tenantCode?: string;
}

/** 登录响应（拦截器已解包 Result，直接返回业务数据） */
export interface LoginResponse {
  token: string;
  refreshToken?: string;
  expiresIn?: number;
  user: {
    id: string;
    username: string;
    nickname?: string;
    avatar?: string;
    email?: string;
    phone?: string;
    tenantId: string;
    roles: string[];
    permissions: string[];
    status: UserStatus;
    lastLoginAt?: string;
  };
}

/** 刷新令牌请求参数 */
export interface RefreshParams {
  refreshToken: string;
}

/** 个人资料更新参数 */
export interface UpdateProfileParams {
  nickname?: string;
  avatar?: string;
  email?: string;
  phone?: string;
}

/** 修改密码参数 */
export interface ChangePasswordParams {
  oldPassword: string;
  newPassword: string;
}

/** 认证 API */
export const authApi = {
  /** 登录 */
  login: (params: LoginParams) =>
    http.post<LoginResponse>('/admin/auth/login', params),
  /** 刷新令牌 */
  refresh: (params: RefreshParams) =>
    http.post<{ token: string; expiresIn?: number }>('/admin/auth/refresh', params),
  /** 获取当前用户信息 */
  me: () =>
    http.get<LoginResponse['user']>('/admin/auth/me'),
  /** 更新当前用户个人资料 */
  updateProfile: (params: UpdateProfileParams) =>
    http.put<void>('/admin/auth/profile', params),
  /** 修改密码 */
  changePassword: (params: ChangePasswordParams) =>
    http.post<void>('/admin/auth/change-password', params),
};
