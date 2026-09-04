/**
 * @file 格式化工具
 * @description 时间、数字、文件大小、金额、百分比、耗时等通用格式化方法的唯一实现入口。
 *              数值类格式化内部复用 utils/number.ts，规避浮点舍入与精度丢失。
 * @author wang.zhen
 * @since 1.0.0
 */
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import 'dayjs/locale/zh-cn';
import { toFixedSafe, formatRatioToPercent } from './number';

dayjs.extend(relativeTime);
dayjs.locale('zh-cn');

/** 日期时间类型入参 */
type DateInput = string | number | Date | null | undefined;

/**
 * 格式化日期时间
 * @param value 日期时间
 * @param pattern 日期格式（默认 YYYY-MM-DD HH:mm:ss）
 * @returns 格式化字符串，空值返回 '-'
 */
export function formatDateTime(value: DateInput, pattern = 'YYYY-MM-DD HH:mm:ss'): string {
  if (!value) return '-';
  const d = dayjs(value);
  return d.isValid() ? d.format(pattern) : '-';
}

/**
 * 格式化文件大小（全工程唯一实现）
 * 统一契约：
 *   < 1 KB  → "x B"（整数）
 *   < 1 MB  → "x.x KB"（1 位小数）
 *   < 1 GB  → "x.xx MB"（2 位小数）
 *   ≥ 1 GB  → "x.xx GB"（2 位小数）
 * @param bytes 字节数
 */
export function formatFileSize(bytes?: number): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '-';
  if (bytes === 0) return '0 B';
  const absBytes = Math.abs(bytes);
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const i = Math.min(units.length - 1, Math.floor(Math.log(absBytes) / Math.log(1024)));
  const size = bytes / Math.pow(1024, i);
  const fractionDigits = i === 0 ? 0 : i === 1 ? 1 : 2;
  return `${toFixedSafe(size, fractionDigits)} ${units[i]}`;
}

/**
 * 格式化百分比（委托 number.formatRatioToPercent，规避 value*100 浮点放大误差）
 * @param value 比例值（0~1）
 * @param fractionDigits 小数位数（默认 1） */
export function formatPercent(value?: number, fractionDigits = 1): string {
  return formatRatioToPercent(value, fractionDigits);
}

/**
 * 格式化耗时（毫秒自适应转 s/m/h，全工程唯一实现）
 * @param ms 毫秒数
 * @returns 形如 "1.23s" / "2.5m" / "1.2h" 的字符串，空值返回 '-'
 */
export function formatDuration(ms?: number): string {
  if (ms === null || ms === undefined || Number.isNaN(ms)) return '-';
  if (ms < 1000) return `${ms}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${toFixedSafe(seconds, 2)}s`;
  const minutes = seconds / 60;
  if (minutes < 60) return `${toFixedSafe(minutes, 1)}m`;
  const hours = minutes / 60;
  return `${toFixedSafe(hours, 2)}h`;
}

/**
 * 格式化文本长度（字符数自适应转 K）
 * @param length 字符数
 * @returns 形如 "1.2K 字符" 的字符串，空值返回 '-'
 */
export function formatLength(length?: number): string {
  if (length === null || length === undefined || Number.isNaN(length)) return '-';
  if (length < 1000) return `${length} 字符`;
  return `${toFixedSafe(length / 1000, 1)}K 字符`;
}
