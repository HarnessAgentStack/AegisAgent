/**
 * @file 高可用与灾备 API 客户端
 * @description 封装健康探活、数据备份接口
 *              2026-08 裁剪：故障切换/灾备演练为 Mock 功能，接口已随服务端一并移除
 * @author wang.zhen
 * @since 1.0.0
 */
import { http } from './request';
import type { Page } from './security';

/** 分页查询参数 */
export interface PageQuery {
  page?: number;
  size?: number;
}

/** 健康探活组件状态 */
export interface HealthComponent {
  name?: string;
  status?: string;
  detail?: string;
  lastCheckTime?: string;
}

/** 备份记录 */
export interface BackupRecord {
  id?: string;
  backupId?: string;
  backupType?: string;
  type?: string;
  sizeBytes?: number;
  size?: string;
  durationSec?: number;
  duration?: string;
  status?: string;
  occurTime?: string;
  time?: string;
  createdAt?: string;
}

const BASE = '/admin/ha';

// ==================== 健康探活 ====================

/** 健康探活 */
export function getHealthStatus() {
  return http.get<Record<string, HealthComponent>>(`${BASE}/health`);
}

// ==================== 数据备份 ====================

/** 备份记录分页查询 */
export function getBackupList(params?: PageQuery) {
  return http.get<Page<BackupRecord>>(`${BASE}/backup/list`, { params });
}

/** 执行手动备份 */
export function executeBackup() {
  return http.post<Record<string, unknown>>(`${BASE}/backup/execute`);
}
