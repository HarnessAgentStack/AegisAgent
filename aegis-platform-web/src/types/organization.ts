/**
 * @file 组织架构类型定义
 * @description 部门、角色、用户相关类型，对齐后端实体
 * @author wang.zhen
 * @since 1.0.0
 */

/** 部门信息（树形结构） */
export interface Department {
  /** 部门 ID */
  id: string;
  /** 租户 ID */
  tenantId?: string;
  /** 部门名称 */
  deptName: string;
  /** 父部门 ID */
  parentId?: string | null;
  /** 部门路径 */
  deptPath?: string;
  /** 部门层级 */
  deptLevel?: number;
  /** 排序 */
  sort?: number;
  /** 部门负责人 ID */
  leaderUserId?: string;
  /** 状态 */
  status?: string;
  /** 同步来源 */
  syncSource?: string;
  /** 创建时间 */
  createTime?: string;
  /** 子部门 */
  children?: Department[];
}

/** 角色信息 */
export interface Role {
  /** 角色 ID */
  id: string;
  /** 租户 ID */
  tenantId?: string;
  /** 角色编码 */
  roleCode: string;
  /** 角色名称 */
  roleName: string;
  /** 角色类型：PLATFORM 平台角色 / RESOURCE 资源角色 */
  roleType: string;
  /** 描述 */
  description?: string;
  /** 排序 */
  sort?: number;
  /** 状态 */
  status?: string;
  /** 创建时间 */
  createTime?: string;
}

/** 用户信息 */
export interface User {
  /** 用户 ID */
  id: string;
  /** 租户 ID */
  tenantId?: string;
  /** 用户名（登录账号） */
  username: string;
  /** 真实姓名 */
  realName?: string;
  /** 工号 */
  empNo?: string;
  /** 邮箱 */
  email?: string;
  /** 手机号 */
  phone?: string;
  /** 头像 URL */
  avatar?: string;
  /** 状态：NORMAL 正常 / DISABLED 禁用 */
  status?: string;
  /** 最近登录时间 */
  lastLoginTime?: string;
  /** 最近登录 IP */
  lastLoginIp?: string;
  /** 部门 ID */
  deptId?: string;
  /** 创建时间 */
  createTime?: string;
  /** 已分配角色 */
  roles?: Role[];
}
