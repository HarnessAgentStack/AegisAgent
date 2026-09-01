import { http } from './request';
import type { IPage, ListResult, extractList, extractTotal } from './resource';

export type { IPage, ListResult, extractList, extractTotal };

/** 沙箱基础镜像 */
export interface SandboxBaseImage {
  id?: number;
  tenantId?: number;
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
  createTime?: string;
  updateTime?: string;
}

/** 沙箱池（两参数驱动模型） */
export interface SandboxPool {
  id?: number;
  tenantId?: number;
  poolCode?: string;
  namespace?: string;
  baseImageId?: number;
  poolName: string;
  poolType: string;
  applicableScene?: string;
  /** 最小实例数：始终保持的干净 IDLE 实例数（预热基准） */
  minInstances: number;
  /** 最大实例数：总实例数上限（缩容阈值） */
  maxInstances: number;
  /** 空闲超时（分钟）：脏 IDLE 超时触发工作区重初始化 */
  idleTimeoutMin?: number;
  networkPolicy: string;
  cpuLimit: string;
  memLimitMb: number;
  diskLimitGb?: number;
  status?: string;
  createTime?: string;
}

/** 沙箱实例（两参数驱动模型） */
export interface SandboxInstance {
  id?: number;
  instanceId: string;
  poolId: number;
  tenantId?: number;
  status: string;
  userId?: number;
  agentId?: number;
  sessionId?: string;
  podName?: string;
  namespace?: string;
  cpuUsage?: number;
  memUsage?: number;
  startTime?: string;
  runtimeMinutes?: number;
  allocatedTime?: string;
  recycledTime?: string;
  reuseCount?: number;
  /** 是否已初始化（1=干净IDLE，0=脏IDLE） */
  initialized?: number;
  baseImageId?: number;
  lastRecycleTime?: string;
  snapshotId?: string;
  snapshotOssKey?: string;
  isolationScope?: string;
  slotKey?: string;
  snapshotTime?: string;
}

const BASE = '/admin/sandbox';

/** 镜像 API */
export const imageApi = {
  page: (params: { page: number; size: number; imageCode?: string; imageName?: string; status?: string }) =>
    http.get<ListResult<SandboxBaseImage>>(`${BASE}/image/page`, { params }),
  list: () => http.get<SandboxBaseImage[]>(`${BASE}/image/list`),
  getById: (id: number) => http.get<SandboxBaseImage>(`${BASE}/image/${id}`),
  create: (data: SandboxBaseImage) => http.post<SandboxBaseImage>(`${BASE}/image`, data),
  update: (data: SandboxBaseImage) => http.put<SandboxBaseImage>(`${BASE}/image`, data),
  updateStatus: (id: number, status: string) => http.put<void>(`${BASE}/image/${id}/status`, { params: { status } }),
  delete: (id: number) => http.delete<void>(`${BASE}/image/${id}`),
};

/** 池 API（两参数驱动模型：预热和回收由 Reconcile 自动执行） */
export const poolApi = {
  page: (params: { page: number; size: number; poolName?: string; poolType?: string; status?: string }) =>
    http.get<ListResult<SandboxPool>>(`${BASE}/pool/page`, { params }),
  list: () => http.get<SandboxPool[]>(`${BASE}/pool/list`),
  getById: (id: number) => http.get<SandboxPool>(`${BASE}/pool/${id}`),
  create: (data: SandboxPool) => http.post<SandboxPool>(`${BASE}/pool`, data),
  update: (data: SandboxPool) => http.put<SandboxPool>(`${BASE}/pool`, data),
  updateStatus: (id: number, status: string) => http.put<void>(`${BASE}/pool/${id}/status`, { params: { status } }),
  delete: (id: number) => http.delete<void>(`${BASE}/pool/${id}`),
  getK8sStatus: (id: number) => http.get<any>(`${BASE}/pool/${id}/k8s-status`),
  /** 手动修复池 K8s 资源（重建 Namespace + ResourceQuota + NetworkPolicy） */
  repairK8s: (id: number) => http.post<any>(`${BASE}/pool/${id}/repair`),
};

/** 实例 API（两参数驱动模型：recycle 统一为工作区重初始化，无需 strategy 参数） */
export const instanceApi = {
  page: (params: { page: number; size: number; poolId?: number; status?: string; filterTenantId?: number; instanceId?: string }) =>
    http.get<ListResult<SandboxInstance>>(`${BASE}/instance/page`, { params }),
  getByInstanceId: (instanceId: string) => http.get<SandboxInstance>(`${BASE}/instance/${instanceId}`),
  /** 手动回收实例（工作区重初始化，统一策略） */
  recycle: (instanceId: string) => http.post<void>(`${BASE}/instance/${instanceId}/recycle`),
  destroy: (instanceId: string) => http.delete<void>(`${BASE}/instance/${instanceId}`),
  getPodStatus: (instanceId: string) => http.get<any>(`${BASE}/instance/${instanceId}/pod-status`),
  countByStatus: () => http.get<Record<string, number>>(`${BASE}/instance/stats`),
};
