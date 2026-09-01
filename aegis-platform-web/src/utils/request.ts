/**
 * @file 请求工具函数
 * @description 与 api/request.ts 配合使用的辅助函数：查询参数序列化、文件下载、进度回调
 * @author wang.zhen
 * @since 1.0.0
 */
import type { AxiosProgressEvent } from 'axios';

/**
 * 将扁平对象序列化为查询串（自动跳过空值）
 * @param params 查询参数对象
 * @returns 以 ? 开头的查询串，无参数时返回空字符串
 */
export function buildQueryParams(params: Record<string, unknown>): string {
  const sp = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v === null || v === undefined || v === '') return;
    sp.append(k, String(v));
  });
  const qs = sp.toString();
  return qs ? `?${qs}` : '';
}

/** 下载进度回调类型：参数为已完成百分比 0~100 */
export type DownloadProgressHandler = (percent: number) => void;

/**
 * 触发浏览器下载（Blob 流）
 * @param data 二进制数据
 * @param filename 下载文件名 */
export function downloadBlob(data: Blob, filename: string): void {
  const url = URL.createObjectURL(data);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

/**
 * 生成 Axios 下载进度回调
 * @param onProgress 进度回调
 */
export function createDownloadProgressHandler(onProgress?: DownloadProgressHandler) {
  return (e: AxiosProgressEvent) => {
    if (!onProgress || !e.total) return;
    onProgress(Math.round((e.loaded / e.total) * 100));
  };
}