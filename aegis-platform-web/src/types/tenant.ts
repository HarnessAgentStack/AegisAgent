/**
 * @file 租户类型定义
 * @description 租户、租户配额、租户用量、租户上下文相关类型，对齐后端实体
 * @author wang.zhen
 * @since 1.0.0
 */

/** 租户信息 */
export interface Tenant {
  /** 租户 ID */
  id: string;
  /** 租户编码（唯一） */
  tenantCode: string;
  /** 租户名称 */
  tenantName: string;
  /** 租户类型：HQ 总部 / SUBSIDIARY 分公司 / DIVISION 事业部 */
  tenantType: string;
  /** 租户状态：NORMAL 正常 / FROZEN 冻结 */
  status: string;
  /** 联系人 */
  contactName?: string;
  /** 联系电话 */
  contactPhone?: string;
  /** 到期时间 */
  expireTime?: string;
  /** 备注 */
  remark?: string;
  /** 创建时间 */
  createTime?: string;
}

/** 租户配额 */
export interface TenantQuota {
  /** 配额 ID */
  id?: string;
  /** 租户 ID */
  tenantId?: string;
  /** 智能体数量上限 */
  maxAgents: number;
  /** 资源数量上限 */
  maxResources: number;
  /** 并发会话上限 */
  maxConcurrentSessions: number;
  /** 每日 Token 上限 */
  maxTokenPerDay: number;
  /** 每月 Token 上限 */
  maxTokenPerMonth: number;
  /** 沙箱数量上限 */
  maxSandboxes: number;
  /** 存储上限（GB） */
  maxStorageGb: number;
}

/** 租户用量（实时统计） */
export interface TenantUsage {
  /** 租户 ID */
  tenantId?: string;
  /** 当前智能体数 */
  agentCount: number;
  /** 当前资源数 */
  resourceCount: number;
  /** 当前并发会话数 */
  concurrentSessionCount: number;
  /** 今日已用 Token */
  tokenUsedToday: number;
  /** 本月已用 Token */
  tokenUsedThisMonth: number;
  /** 已用沙箱数 */
  sandboxUsed: number;
  /** 已用存储（GB） */
  storageUsedGb: number;
  /** 统计日期 */
  statDate?: string;
}

/** 租户上下文（贯穿全链路） */
export interface TenantContext {
  /** 租户 ID */
  tenantId: string;
  /** 租户编码 */
  tenantCode: string;
  /** 租户名称 */
  tenantName: string;
}
