/**
 * @file Axios 实例与拦截器
 * @description 租户标识注入、Token 注入、统一错误处理、Result 解包
 *              设计要点：
 *                - 日志脱敏：DEV 日志中自动屏蔽 token/password 等敏感字段；
 *                - UI 解耦：业务错误通过 onBusinessError 回调注入点通知 UI，不耦合 antd.message；
 *                - ID 归一化：响应拦截器统一将 ID 字段归一化为 string。
 * @author wang.zhen
 * @since 1.0.0
 */
import axios, { type AxiosInstance, type AxiosRequestConfig } from 'axios';
import type { Result } from './types';
import { storage } from '@/utils/storage';
import { STORAGE_KEY, HTTP_HEADER } from '@/utils/constants';

declare module 'axios' {
  interface InternalAxiosRequestConfig {
    __retryCount?: number;
  }
}

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api';

/** 网络请求重试配置 */
const RETRY_CONFIG = {
  maxRetries: 2,        // 最多重试2次（共3次请求）
  retryDelay: 1000,     // 首次重试延迟1秒
  retryableStatuses: [408, 429, 500, 502, 503, 504], // 可重试的HTTP状态码
};

/** 日志脱敏字段（在 DEV 日志中自动屏蔽） */
const SENSITIVE_FIELDS = new Set<string>([
  'password', 'token', 'accessToken', 'refreshToken', 'authorization',
  'Authorization', 'secret', 'apiKey', 'api_key', 'sk', 'credential',
]);

/** 脱敏：将对象中敏感字段替换为 '***'，避免在日志中泄露凭据 */
function maskSensitiveData(obj: unknown, depth = 0): unknown {
  if (obj === null || obj === undefined || depth > 3) return obj;
  if (typeof obj !== 'object') return obj;
  if (Array.isArray(obj)) return obj.map((item) => maskSensitiveData(item, depth + 1));
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
    if (SENSITIVE_FIELDS.has(key)) {
      result[key] = '***';
    } else if (value !== null && typeof value === 'object') {
      result[key] = maskSensitiveData(value, depth + 1);
    } else {
      result[key] = value;
    }
  }
  return result;
}

/** 业务错误处理回调（由 App 根组件注入，避免 request.ts 耦合 antd.message） */
export type BusinessErrorHandler = (message: string, code?: number) => void;

let onBusinessError: BusinessErrorHandler = (msg: string) => {
  // 默认：静默告警，保证 request 层零 UI 依赖；
  // App 启动后通过 setErrorHandler 注入 antd.message.error。
  if (import.meta.env.DEV) {
    console.warn('[HTTP Business Error]', msg);
  }
};

/** 注入业务错误处理回调（通常在 App.tsx 中绑定 antd.message） */
export function setErrorHandler(handler: BusinessErrorHandler): void {
  onBusinessError = handler;
}

/** HTTP 错误码映射表（业务码由响应拦截器处理，此处覆盖网络层状态码） */
const ERROR_CODE_MAP: Record<number, string> = {
  400: '请求参数错误',
  401: '登录已过期，请重新登录',
  403: '没有访问权限',
  404: '请求的资源不存在',
  408: '请求超时',
  409: '资源冲突',
  413: '请求体过大',
  422: '请求参数验证失败',
  429: '请求过于频繁，请稍后再试',
  500: '服务器内部错误',
  502: '网关错误',
  503: '服务暂不可用',
  504: '网关超时',
};

/** Axios 实例：不预设 Content-Type，由请求拦截器或浏览器自动处理 */
const request: AxiosInstance = axios.create({
  baseURL,
  timeout: 30_000,
});

/** 请求拦截器：注入 Token、租户标识与用户标识，FormData 请求自动移除 Content-Type */
request.interceptors.request.use(
  (config) => {
    if (import.meta.env.DEV) {
      console.log('[HTTP] 请求发起:', config.method?.toUpperCase(), config.url, {
        baseURL: config.baseURL,
        fullURL: (config.baseURL || '') + (config.url || ''),
        data: maskSensitiveData(config.data),
      });
    }
    // 初始化 headers（如果不存在）
    if (!config.headers) {
      config.headers = new axios.AxiosHeaders();
    }

    // Token、租户标识与用户标识注入
    const token = storage.getRaw(STORAGE_KEY.TOKEN);
    if (token) {
      if (typeof config.headers.set === 'function') {
        config.headers.set(HTTP_HEADER.AUTHORIZATION, `Bearer ${token}`);
      } else {
        (config.headers as unknown as Record<string, string>)[HTTP_HEADER.AUTHORIZATION] = `Bearer ${token}`;
      }
    }
    const tenantId = storage.get<number | null>(STORAGE_KEY.TENANT_ID, null);
    if (tenantId !== null) {
      if (typeof config.headers.set === 'function') {
        config.headers.set(HTTP_HEADER.TENANT_ID, String(tenantId));
      } else {
        (config.headers as unknown as Record<string, string>)[HTTP_HEADER.TENANT_ID] = String(tenantId);
      }
    }
    const userInfo = storage.get<{ id?: number } | null>(STORAGE_KEY.USER_INFO, null);
    if (userInfo?.id != null) {
      if (typeof config.headers.set === 'function') {
        config.headers.set(HTTP_HEADER.USER_ID, String(userInfo.id));
      } else {
        (config.headers as unknown as Record<string, string>)[HTTP_HEADER.USER_ID] = String(userInfo.id);
      }
    }

    // FormData 请求：彻底移除 Content-Type，让浏览器自动设置 multipart/form-data boundary
    if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
      // 删除所有可能的 Content-Type 变体
      try {
        if (typeof config.headers.delete === 'function') {
          config.headers.delete('Content-Type');
          config.headers.delete('content-type');
          config.headers.delete('CONTENT-TYPE');
        } else {
          const h = config.headers as unknown as Record<string, unknown>;
          delete h['Content-Type'];
          delete h['content-type'];
          delete h['CONTENT-TYPE'];
        }
      } catch {
        // 忽略删除异常
      }
    } else {
      // 非 FormData 请求：设置 JSON Content-Type
      if (typeof config.headers.set === 'function') {
        config.headers.set('Content-Type', 'application/json');
      } else {
        const h = config.headers as unknown as Record<string, unknown>;
        h['Content-Type'] = 'application/json';
      }
    }

    return config;
  },
  (error) => {
    console.error('[HTTP] 请求拦截器错误:', error);
    return Promise.reject(error);
  },
);

/**
 * ID 字段白名单：响应数据中匹配这些 key 的字段将被归一化为 string。
 * 覆盖全工程雪花 ID 字段（实体主键、外键、关联 ID），保证前端消费契约统一为 string，
 * 免疫后端 ID 序列化格式（number/string）变化。
 */
const ID_FIELDS = new Set<string>([
  'id', 'agentId', 'userId', 'sessionId', 'skillId', 'resourceId',
  'kbId', 'knowledgeBaseId', 'tenantId', 'deptId', 'modelId', 'providerId',
  'roleId', 'toolId', 'mcpId', 'messageId', 'taskId', 'spanId', 'traceId',
  'roundId', 'stepId', 'docId', 'bindingId', 'approverId', 'authorUserId',
  'authorDeptId', 'createBy', 'defaultModelId', 'fileId', 'parentId', 'backupId',
  'candidateModelIds', 'kbIds', 'mcpIds', 'enabledTools',
]);

/** 将单个 ID 值（含 ID 数组）归一化为 string / string[] */
function normalizeIdValue(value: unknown): unknown {
  if (value === null || value === undefined) return value;
  if (Array.isArray(value)) return value.map((v) => normalizeIdValue(v));
  if (typeof value === 'number' || typeof value === 'string') return String(value);
  return value;
}

/**
 * 递归归一化响应数据中的 ID 字段为 string。
 * 无论后端返回 number 还是 string，前端拿到的 ID 字段一律为 string，
 * 从根源消除 Number(id) 精度丢失风险。
 */
function normalizeIds(data: unknown): unknown {
  if (data === null || data === undefined) return data;
  if (Array.isArray(data)) return data.map(normalizeIds);
  if (typeof data === 'object' && !(data instanceof Blob)) {
    const source = data as Record<string, unknown>;
    const result: Record<string, unknown> = {};
    for (const key of Object.keys(source)) {
      const value = source[key];
      result[key] = ID_FIELDS.has(key) ? normalizeIdValue(value) : normalizeIds(value);
    }
    return result;
  }
  return data;
}

/** 响应拦截器：解包 Result，统一错误处理 */
request.interceptors.response.use(
  (response) => {
    if (import.meta.env.DEV) {
      console.log('[HTTP] 响应成功:', response.config.method?.toUpperCase(), response.config.url, {
        status: response.status,
        data: maskSensitiveData(response.data),
      });
    }
    // 文件下载等非 JSON 响应直接返回
    if (response.config.responseType === 'blob') {
      return response.data;
    }
    const res = response.data as Result;
    if (res && typeof res === 'object' && 'code' in res) {
      // 后端 Result.success() 使用 code=200；同时兼容 success 字段
      if (res.code === 200 || (res as { success?: boolean }).success === true) {
        return normalizeIds(res.data);
      }
      if (res.code === 401) {
        // Token 无效或过期，清除登录态并跳转登录页
        storage.remove(STORAGE_KEY.TOKEN);
        storage.remove(STORAGE_KEY.REFRESH_TOKEN);
        storage.remove(STORAGE_KEY.USER_INFO);
        storage.remove(STORAGE_KEY.TENANT_ID);
        window.location.href = '/login';
        return Promise.reject(new Error('登录已过期，请重新登录'));
      } else {
        onBusinessError(res.message || `请求失败（${res.code}）`, res.code);
      }
      return Promise.reject(new Error(res.message || 'Business Error'));
    }
    return normalizeIds(response.data);
  },
  (error) => {
    console.error('[HTTP] 响应错误:', error?.config?.method?.toUpperCase(), error?.config?.url, {
      status: error?.response?.status,
      data: maskSensitiveData(error?.response?.data),
      message: error?.message,
    });
    // 取消请求不弹错
    if (axios.isCancel(error)) {
      return Promise.reject(error);
    }
    // 网络断开特殊提示
    if (!navigator.onLine) {
      onBusinessError('网络已断开，请检查网络连接');
      return Promise.reject(error);
    }
    const status = error?.response?.status;
    const mappedMsg = status != null ? ERROR_CODE_MAP[status] : undefined;
    const msg = error?.response?.data?.message || mappedMsg || error?.message || '网络异常';
    if (status === 401) {
      storage.remove(STORAGE_KEY.TOKEN);
      storage.remove(STORAGE_KEY.REFRESH_TOKEN);
      storage.remove(STORAGE_KEY.USER_INFO);
      storage.remove(STORAGE_KEY.TENANT_ID);
      window.location.href = '/login';
      return Promise.reject(error);
    } else {
      onBusinessError(msg, status);
    }
    return Promise.reject(error);
  },
);

/** 响应拦截器：网络错误与可重试状态码自动重试（指数退避） */
request.interceptors.response.use(undefined, (error) => {
  const config = error?.config;
  if (!config) return Promise.reject(error);

  const status = error?.response?.status;
  const isNetworkError = !error.response;
  const isRetryableStatus = status !== undefined && RETRY_CONFIG.retryableStatuses.includes(status);

  if (!isNetworkError && !isRetryableStatus) return Promise.reject(error);

  config.__retryCount = config.__retryCount || 0;
  if (config.__retryCount >= RETRY_CONFIG.maxRetries) return Promise.reject(error);

  config.__retryCount += 1;
  const delay = RETRY_CONFIG.retryDelay * 2 ** (config.__retryCount - 1);
  if (import.meta.env.DEV) {
    console.warn(`[HTTP] 自动重试第 ${config.__retryCount} 次，延迟 ${delay}ms`);
  }

  return new Promise<unknown>((resolve) => {
    setTimeout(() => {
      resolve(request(config));
    }, delay);
  });
});

/** 解包后的请求方法（响应拦截器已返回业务 data） */
export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig) => request.get<T, T>(url, config),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request.post<T, T>(url, data, config),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request.put<T, T>(url, data, config),
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    request.patch<T, T>(url, data, config),
  delete: <T>(url: string, config?: AxiosRequestConfig) => request.delete<T, T>(url, config),
};

export default request;