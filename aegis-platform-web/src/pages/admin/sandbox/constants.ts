/**
 * @file 沙箱资源管理 - 公共常量
 * @description 枚举选项、状态映射、色值等共享常量，对齐后端枚举
 *              两参数驱动模型：策略体系已移除，池参数仅保留 min_instances/max_instances/idle_timeout_min
 * @author wang.zhen
 * @since 1.0.0
 */

/** 产品原型色值 */
export const COLOR = {
  primary: '#4f46e5',
  success: '#10b981',
  warning: '#f59e0b',
  danger: '#ef4444',
  info: '#3b82f6',
  gray: '#9ca3af',
} as const;

/** 主标签定义（两参数驱动模型：策略 Tab 已移除） */
export const SANDBOX_TABS = [
  { key: 'image', label: '🐳 基础镜像' },
  { key: 'pool', label: '🏊 沙箱池' },
] as const;

// ==================== 镜像仓库类型 ====================

export const REGISTRY_TYPE_OPTIONS = [
  { value: 'DOCKER_HUB', label: 'Docker Hub' },
  { value: 'HARBOR', label: 'Harbor 私有仓库' },
];

export const REGISTRY_TYPE_MAP: Record<string, { text: string; color: string }> = {
  DOCKER_HUB: { text: 'Docker Hub', color: COLOR.info },
  HARBOR: { text: 'Harbor', color: COLOR.primary },
};

// ==================== 池类型 ====================

export const POOL_TYPE_OPTIONS = [
  { value: 'GENERAL', label: '通用' },
  { value: 'LIGHT', label: '轻量' },
  { value: 'STANDARD', label: '标准' },
  { value: 'HEAVY', label: '重量' },
  { value: 'ISOLATED', label: '隔离' },
  { value: 'DEBUG', label: '调试' },
];

export const POOL_TYPE_MAP: Record<string, { text: string; color: string }> = {
  GENERAL: { text: '通用', color: COLOR.info },
  LIGHT: { text: '轻量', color: COLOR.success },
  STANDARD: { text: '标准', color: COLOR.primary },
  HEAVY: { text: '重量', color: COLOR.warning },
  ISOLATED: { text: '隔离', color: COLOR.danger },
  DEBUG: { text: '调试', color: COLOR.gray },
};

// ==================== 网络策略 ====================

export const NETWORK_POLICY_OPTIONS = [
  { value: 'ISOLATED', label: '隔离（无网络）' },
  { value: 'RESTRICTED', label: '限制出站' },
  { value: 'NO_EXTERNAL', label: '禁止外网' },
  { value: 'OPEN', label: '允许联网' },
];

export const NETWORK_POLICY_MAP: Record<string, { text: string; color: string }> = {
  ISOLATED: { text: '隔离', color: COLOR.danger },
  RESTRICTED: { text: '限制出站', color: COLOR.warning },
  NO_EXTERNAL: { text: '禁止外网', color: COLOR.primary },
  OPEN: { text: '允许联网', color: COLOR.success },
};

// ==================== 池状态 ====================

export const POOL_STATUS_OPTIONS = [
  { value: 'ENABLED', label: '启用' },
  { value: 'DISABLED', label: '禁用' },
  { value: 'MAINTAINING', label: '维护中' },
];

export const POOL_STATUS_MAP: Record<string, { text: string; color: string }> = {
  ENABLED: { text: '启用', color: COLOR.success },
  DISABLED: { text: '禁用', color: COLOR.gray },
  MAINTAINING: { text: '维护中', color: COLOR.warning },
};

// ==================== 通用启用/停用状态（镜像） ====================

export const ENABLED_STATUS_OPTIONS = [
  { value: 'ENABLED', label: '启用' },
  { value: 'DISABLED', label: '停用' },
];

export const ENABLED_STATUS_MAP: Record<string, { text: string; color: string }> = {
  ENABLED: { text: '启用', color: COLOR.success },
  DISABLED: { text: '停用', color: COLOR.gray },
};

// ==================== 实例状态 ====================

export const INSTANCE_STATUS_OPTIONS = [
  { value: 'OCCUPIED', label: '占用中' },
  { value: 'IDLE', label: '空闲' },
  { value: 'ABNORMAL', label: '异常' },
  { value: 'DESTROYED', label: '已销毁' },
];

export const INSTANCE_STATUS_MAP: Record<string, { text: string; color: string }> = {
  OCCUPIED: { text: '占用中', color: COLOR.info },
  IDLE: { text: '空闲', color: COLOR.success },
  ABNORMAL: { text: '异常', color: COLOR.danger },
  DESTROYED: { text: '已销毁', color: COLOR.gray },
};

// ==================== 表单值类型 ====================

/** 镜像表单值 */
export interface ImageFormValues {
  imageCode: string;
  imageName: string;
  description?: string;
  registryType: string;
  registry?: string;
  repository: string;
  tag: string;
  digest?: string;
  imageSizeMb?: number;
  status?: string;
}

/** 池表单值（两参数驱动模型） */
export interface PoolFormValues {
  poolCode: string;
  poolName: string;
  poolType: string;
  baseImageId: number;
  applicableScene?: string;
  /** 最小实例数：始终保持的干净 IDLE 实例数 */
  minInstances: number;
  /** 最大实例数：总实例数上限 */
  maxInstances: number;
  /** 空闲超时（分钟） */
  idleTimeoutMin: number;
  networkPolicy: string;
  cpuLimit: string;
  memLimitMb: number;
  diskLimitGb?: number;
  status?: string;
}

/** 将 IPage 字段命名（后端 MyBatis-Plus Page）映射为前端通用结构 */
export function normalizePage<T>(raw: unknown): { list: T[]; total: number } {
  if (Array.isArray(raw)) {
    return { list: raw, total: raw.length };
  }
  const obj = raw as { records?: T[]; total?: number } | null;
  return {
    list: obj?.records ?? [],
    total: obj?.total ?? 0,
  };
}
