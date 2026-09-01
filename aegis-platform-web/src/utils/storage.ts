/**
 * @file 本地存储工具
 * @description 基于 localStorage 的类型安全封装，支持 JSON 序列化与命名空间前缀。
 *              全工程唯一入口：禁止直接 localStorage.setItem/getItem 写裸 key。
 * @author wang.zhen
 * @since 1.0.0
 */
import { safeJsonParse } from '@/utils/number';

/** 存储命名空间前缀（单轨：所有 Key 统一由此前缀修饰，常量层不再重复） */
export const PREFIX = 'aegis_';

/** 组装带命名空间的 Key */
const buildKey = (key: string): string => `${PREFIX}${key}`;

/**
 * 历史遗留 Key → 新 Key 映射（前缀单轨化迁移）
 * 旧格式：`aegis_XXX` 常量 + storage 再次加前缀 = `aegis_aegis_XXX`
 * 新格式：常量去前缀 → storage 加前缀 = `aegis_XXX`
 */
const LEGACY_KEY_MAP: Record<string, string> = {
  aegis_token: 'token',
  aegis_refresh_token: 'refresh_token',
  aegis_user_info: 'user_info',
  aegis_tenant_id: 'tenant_id',
  aegis_agent_id: 'agent_id',
  aegis_locale: 'locale',
};

/**
 * 迁移历史遗留存储 Key 到单轨命名空间。
 * - 扫描 localStorage 全量 Key
 * - 若发现旧前缀格式（裸 `aegis_XXX`）且新 Key 不存在 → 迁移
 * - 幂等：已迁移则跳过
 * - 调用时机：应用启动入口（main.tsx / App 根组件）
 */
export function migrateLegacyKeys(): void {
  try {
    for (const [legacyKey, newKey] of Object.entries(LEGACY_KEY_MAP)) {
      const legacyFullKey = buildKey(legacyKey); // aegis_aegis_token
      const newFullKey = buildKey(newKey); // aegis_token
      // 新 Key 已存在：跳过（优先级：新 > 旧）
      if (localStorage.getItem(newFullKey) !== null) continue;
      const legacyValue = localStorage.getItem(legacyKey); // 同时尝试无前缀旧格式
      const legacyFullValue = localStorage.getItem(legacyFullKey);
      const value = legacyValue ?? legacyFullValue;
      if (value === null) continue;
      localStorage.setItem(newFullKey, value);
      if (legacyValue !== null) localStorage.removeItem(legacyKey);
      if (legacyFullValue !== null) localStorage.removeItem(legacyFullKey);
    }
  } catch {
    // 隐私模式 / 配额限制：静默降级
  }
}

/**
 * 本地存储工具（localStorage 封装） */
export const storage = {
  /**
   * 读取并反序列化存储值
   * @param key 存储 Key（不含前缀）
   * @param defaultValue 解析失败或不存在时返回的默认值
   */
  get<T>(key: string, defaultValue: T): T {
    const raw = localStorage.getItem(buildKey(key));
    if (raw === null) return defaultValue;
    return safeJsonParse<T>(raw, defaultValue) as T;
  },

  /**
   * 序列化并写入存储值
   * @param key 存储 Key（不含前缀）
   * @param value 任意可序列化值
   */
  set<T>(key: string, value: T): void {
    try {
      localStorage.setItem(buildKey(key), JSON.stringify(value));
    } catch {
      // 容量超限或隐私模式：静默降级
    }
  },

  /**
   * 读取原始字符串
   * @param key 存储 Key（不含前缀）
   */
  getRaw(key: string): string | null {
    try {
      return localStorage.getItem(buildKey(key));
    } catch (e) {
      console.warn('[storage] getItem 失败，降级返回 null:', e);
      return null;
    }
  },

  /**
   * 写入原始字符串（不做 JSON 序列化，适用于 Token 等纯字符串）
   */
  setRaw(key: string, value: string): void {
    try {
      localStorage.setItem(buildKey(key), value);
    } catch (e) {
      console.warn('[storage] setItem 失败，跳过写入（可能隐私模式或配额已满）:', e);
    }
  },

  /** 移除指定 Key */
  remove(key: string): void {
    try {
      localStorage.removeItem(buildKey(key));
    } catch (e) {
      console.warn('[storage] removeItem 失败，跳过:', e);
    }
  },

  /** 清空当前命名空间下所有 Key */
  clear(): void {
    try {
      Object.keys(localStorage)
        .filter((k) => k.startsWith(PREFIX))
        .forEach((k) => localStorage.removeItem(k));
    } catch (e) {
      console.warn('[storage] clear 失败，跳过:', e);
    }
  },
};
