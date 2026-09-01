/**
 * @file 认证状态管理
 * @description Token、用户信息、登录态管理；基于 Zustand + localStorage 持久化
 * @author wang.zhen
 * @since 1.0.0
 */
import { create } from 'zustand';
import type { UserInfo } from '@/types/user';
import { storage } from '@/utils/storage';
import { STORAGE_KEY } from '@/utils/constants';

/** 认证状态 */
interface AuthState {
  /** 访问令牌 */
  token: string | null;
  /** 刷新令牌 */
  refreshToken: string | null;
  /** 当前用户信息 */
  user: UserInfo | null;
  /** 是否已登录 */
  isAuthenticated: boolean;
  /** 设置登录态（持久化 Token 与用户信息） */
  setAuth: (token: string, refreshToken: string | null, user: UserInfo) => void;
  /** 更新用户信息 */
  setUser: (user: UserInfo) => void;
  /** 设置 Token（如刷新后） */
  setToken: (token: string, refreshToken?: string | null) => void;
  /** 登出（清空状态与本地存储） */
  logout: () => void;
  /** 权限校验：是否拥有指定权限编码 */
  hasPermission: (code: string) => boolean;
  /** 角色校验：是否拥有指定角色编码 */
  hasRole: (code: string) => boolean;
}

/** 从本地存储恢复初始 Token 与用户信息 */
const initialToken = storage.getRaw(STORAGE_KEY.TOKEN);
const initialRefreshToken = storage.getRaw(STORAGE_KEY.REFRESH_TOKEN);
const initialUser = storage.get<UserInfo | null>(STORAGE_KEY.USER_INFO, null);

export const useAuthStore = create<AuthState>((set, get) => ({
  token: initialToken,
  refreshToken: initialRefreshToken,
  user: initialUser,
  isAuthenticated: !!initialToken && !!initialUser,

  setAuth: (token, refreshToken, user) => {
    storage.setRaw(STORAGE_KEY.TOKEN, token);
    if (refreshToken) storage.setRaw(STORAGE_KEY.REFRESH_TOKEN, refreshToken);
    storage.set(STORAGE_KEY.USER_INFO, user);
    set({ token, refreshToken, user, isAuthenticated: true });
  },

  setUser: (user) => {
    storage.set(STORAGE_KEY.USER_INFO, user);
    set({ user });
  },

  setToken: (token, refreshToken) => {
    storage.setRaw(STORAGE_KEY.TOKEN, token);
    if (refreshToken !== undefined && refreshToken !== null) {
      storage.setRaw(STORAGE_KEY.REFRESH_TOKEN, refreshToken);
    }
    set({ token, refreshToken: refreshToken ?? get().refreshToken });
  },

  logout: () => {
    storage.remove(STORAGE_KEY.TOKEN);
    storage.remove(STORAGE_KEY.REFRESH_TOKEN);
    storage.remove(STORAGE_KEY.USER_INFO);
    set({ token: null, refreshToken: null, user: null, isAuthenticated: false });
  },

  hasPermission: (code) => {
    const { user } = get();
    return !!user?.permissions?.includes(code);
  },

  hasRole: (code) => {
    const { user } = get();
    return !!user?.roles?.includes(code);
  },
}));