/**
 * @file 主题状态管理
 * @description 亮/暗主题切换，基于 Zustand + localStorage 持久化；
 *              切换时同步写入 <html data-theme="..."> 供 CSS 变量与组件样式消费。
 * @author wang.zhen
 * @since 1.0.0
 */
import { create } from 'zustand';
import { storage } from '@/utils/storage';
import { STORAGE_KEY } from '@/utils/constants';

/** 主题模式 */
export type ThemeMode = 'light' | 'dark';

/** 主题状态 */
interface ThemeState {
  /** 当前主题模式 */
  mode: ThemeMode;
  /** 切换主题（持久化 + 同步 data-theme 属性） */
  toggle: () => void;
  /** 直接设置主题 */
  setMode: (mode: ThemeMode) => void;
}

/** 从本地存储恢复初始主题，默认跟随系统偏好 */
function resolveInitialMode(): ThemeMode {
  const saved = storage.getRaw(STORAGE_KEY.THEME);
  if (saved === 'light' || saved === 'dark') return saved;
  try {
    if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) return 'dark';
  } catch { /* ignore */ }
  return 'light';
}

/** 同步 <html data-theme="..."> 属性，供 CSS 变量切换 */
export function applyThemeAttribute(mode: ThemeMode): void {
  try {
    document.documentElement.setAttribute('data-theme', mode);
  } catch { /* SSR / 非浏览器环境：忽略 */ }
}

const initialMode = resolveInitialMode();
applyThemeAttribute(initialMode);

export const useThemeStore = create<ThemeState>((set, get) => ({
  mode: initialMode,

  toggle: () => {
    const next: ThemeMode = get().mode === 'dark' ? 'light' : 'dark';
    storage.setRaw(STORAGE_KEY.THEME, next);
    applyThemeAttribute(next);
    set({ mode: next });
  },

  setMode: (mode) => {
    storage.setRaw(STORAGE_KEY.THEME, mode);
    applyThemeAttribute(mode);
    set({ mode });
  },
}));
