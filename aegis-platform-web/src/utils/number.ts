/**
 * @file 数值精度工具
 * @description 解决 IEEE-754 双精度浮点的两类问题：
 *   1. 雪花 ID 等 64 位整数超过 Number.MAX_SAFE_INTEGER 的精度丢失；
 *   2. toFixed 浮点舍入不确定性（如 1.005.toFixed(2) → "1.00"）。
 *   全工程 ID 传递与金额/百分比计算必须经本模块，禁止裸用 Number()/toFixed()。
 * @author wang.zhen
 * @since 1.0.0
 */

/** 安全整数上界（2^53 - 1） */
export const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER;

/**
 * 判断数值是否在安全整数范围内。
 * 超界 ID 必须以 string 传递，禁止用 Number() 转换。
 * @param value 待判定的值
 * @returns 是否为安全整数
 */
export function isSafeInteger(value: unknown): boolean {
  const n = Number(value);
  return Number.isInteger(n) && n <= MAX_SAFE_INTEGER && n >= -MAX_SAFE_INTEGER;
}

/**
 * 安全数值格式化（替代裸 toFixed）。
 * 采用"先放大为整数再舍入"策略，规避浮点累积误差。
 * @param value 原始数值
 * @param fractionDigits 小数位数（默认 0）
 * @returns 格式化字符串，非有限数值返回 '-'
 */
export function toFixedSafe(value: number, fractionDigits = 0): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '-';
  const factor = 10 ** fractionDigits;
  const rounded = Math.round((value + Number.EPSILON) * factor) / factor;
  return rounded.toFixed(fractionDigits);
}

/**
 * 百分比格式化（规避 value*100 浮点放大误差）。
 * @param ratio 比例值 0~1
 * @param fractionDigits 小数位（默认 1）
 * @returns 形如 "7.0%" 的字符串，空值/NaN 返回 '-'
 */
export function formatRatioToPercent(ratio: number | null | undefined, fractionDigits = 1): string {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '-';
  return `${toFixedSafe(ratio * 100, fractionDigits)}%`;
}

/**
 * 容错 JSON 解析（替代裸 JSON.parse）。
 * @param text 待解析字符串
 * @param defaultValue 解析失败时返回的默认值（默认 null）
 * @returns 解析后的 T，失败返回 defaultValue
 */
export function safeJsonParse<T = unknown>(text: string | null | undefined, defaultValue: T | null = null): T | null {
  if (text === null || text === undefined) return defaultValue;
  try {
    return JSON.parse(text) as T;
  } catch {
    return defaultValue;
  }
}
