/// <reference types="vite/client" />

/**
 * @file Vite 环境变量类型声明
 * @description 扩展 ImportMetaEnv，为自定义环境变量提供类型提示
 * @author wang.zhen
 * @since 1.0.0
 */
interface ImportMetaEnv {
  /** 应用标题 */
  readonly VITE_APP_TITLE: string;
  /** API 基础地址 */
  readonly VITE_API_BASE_URL: string;
  /** SSE 基础地址 */
  readonly VITE_SSE_BASE_URL: string;
  /** 是否启用 Mock */
  readonly VITE_USE_MOCK: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}