/**
 * @file 用户类型定义
 * @description 用户、角色、登录态相关类型
 * @author wang.zhen
 * @since 1.0.0
 */
import type { UserStatus } from './enum';

/** 用户信息 */
export interface UserInfo {
  /** 用户 ID（雪花ID，前端一律 string） */
  id: string;
  /** 用户名（登录账号） */
  username: string;
  /** 昵称 */
  nickname: string;
  /** 头像 URL */
  avatar?: string;
  /** 邮箱 */
  email?: string;
  /** 手机号 */
  phone?: string;
  /** 所属租户 ID（雪花ID，前端一律 string） */
  tenantId: string;
  /** 角色编码列表 */
  roles: string[];
  /** 权限编码列表 */
  permissions: string[];
  /** 用户状态 */
  status: UserStatus;
  /** 最近登录时间 */
  lastLoginAt?: string;
}

/** 登录请求参数 */
export interface LoginParams {
  /** 用户名 */
  username: string;
  /** 密码 */
  password: string;
  /** 租户标识（多租户登录） */
  tenantCode?: string;
  /** 验证码 */
  captcha?: string;
}

/** 登录响应结果 */
export interface LoginResult {
  /** 访问令牌 */
  token: string;
  /** 刷新令牌 */
  refreshToken?: string;
  /** 过期时间（秒） */
  expiresIn?: number;
  /** 当前用户信息 */
  user: UserInfo;
}