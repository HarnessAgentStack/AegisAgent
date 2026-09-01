/**
 * @file 高可用与灾备 - 共享常量与工具函数
 * @description 产品原型色值、大标签定义、跨 Tab 共享的格式化函数
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
} as const;

/** 大标签定义 */
export const HA_TABS = [
  { key: 'deploy', label: '🏗️ 部署模式' },
  { key: 'backup', label: '💾 数据备份' },
];

/** 字节数格式化为人类可读大小（复用 utils 唯一实现，保留调用方兼容命名） */
export { formatFileSize as formatBytes } from '@/utils/format';

/** 秒数格式化为 HH:MM:SS（备份耗时等场景的时钟格式，与 utils 的 ms→duration 语义不同） */
export function formatClockDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  return [h, m, s].map((v) => String(v).padStart(2, '0')).join(':');
}
