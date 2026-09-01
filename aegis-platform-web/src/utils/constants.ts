/**
 * @file 常量定义
 * @description 路由路径、存储 Key、请求头、分页默认值等全局常量
 * @author wang.zhen
 * @since 1.0.0
 */

/** 路由路径常量 */
export const ROUTE_PATH = {
  LOGIN: '/login',
  // ===== 员工工作台 =====
  WORKBENCH: '/workbench',
  CHAT: '/chat',
  AGENT_LIST: '/agent/list',
  AGENT_DETAIL: '/agent/detail',
  AGENT_CREATE: '/agent/create',
  AGENT_EDIT: '/agent/edit',
  RESOURCE_SKILL: '/resource/skill',
  RESOURCE_KNOWLEDGE: '/resource/knowledge',
  RESOURCE_MCP: '/resource/mcp',
  RESOURCE_TOOL: '/resource/tool',
  // ===== 管理控制台 =====
  ADMIN_SANDBOX: '/admin/sandbox',
  MODEL: '/model',
  SECURITY: '/security',
  AUDIT: '/audit',
  REVIEW: '/review',
  ADMIN_HA: '/admin/ha',
  ADMIN_OBSERVE: '/admin/observe',
  TENANT: '/tenant',
  ORGANIZATION: '/organization',
  ROLE: '/role',
  PROFILE: '/profile',
  NOT_FOUND: '/404',
} as const;

/** 侧边栏模式 */
export type SidebarMode = 'user' | 'admin';

/**
 * 本地存储 Key（单轨：由 storage.ts 的 PREFIX 统一追加命名空间前缀，此处不再重复）
 * 最终存储的实际 Key = `${storage.PREFIX}${STORAGE_KEY.XXX}`。
 */
export const STORAGE_KEY = {
  TOKEN: 'token',
  REFRESH_TOKEN: 'refresh_token',
  USER_INFO: 'user_info',
  TENANT_ID: 'tenant_id',
  AGENT_ID: 'agent_id',
  LOCALE: 'locale',
  THEME: 'theme',
} as const;

/** 请求头名称 */
export const HTTP_HEADER = {
  AUTHORIZATION: 'Authorization',
  TENANT_ID: 'X-Tenant-Id',
  USER_ID: 'X-User-Id',
  DEPT_ID: 'X-Dept-Id',
  AGENT_ID: 'X-Agent-Id',
  REQUEST_ID: 'X-Request-Id',
} as const;

/** 分页默认值 */
export const DEFAULT_PAGE = 1;
export const DEFAULT_PAGE_SIZE = 10;

/** 应用标题 */
export const APP_TITLE = import.meta.env.VITE_APP_TITLE ?? 'Aegis Platform';