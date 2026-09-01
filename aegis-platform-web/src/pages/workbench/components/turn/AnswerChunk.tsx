/**
 * @file 回答片段项
 * @description 渲染 TurnEvent(answer)：白底 Markdown 气泡片段，保留 react-markdown + GFM +
 *              下载链接特殊处理（迁自旧 ChatArea）。一次轮次可有多段 answer（被工具分隔，
 *              按 timestamp 保持顺序）。
 *
 * @author Aegis
 * @since 4.0.0
 */
import React from 'react';
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { App } from 'antd';
import type { AnswerEvent } from '@/types/turn';
import { storage } from '@/utils/storage';
import { STORAGE_KEY } from '@/utils/constants';

interface AnswerChunkProps {
  event: AnswerEvent;
  /** 是否流式中（流式态用淡色占位） */
  streaming?: boolean;
  /** 是否为错误消息片段 */
  isError?: boolean;
  /** markdown 自定义样式表（由父层注入） */
  markdownStyles?: string;
}

/** generate_file 下载 URL 前缀（与后端 AegisGenerateFileTool.DOWNLOAD_URL_PREFIX 对齐） */
const AEGIS_DOWNLOAD_PREFIX = '/api/runtime/task/download/';

/** 32 位 hex fileId 正则（MinIO objectKey / artifactId 格式） */
const FILE_ID_RE = /^[0-9a-f]{32}(\?.*)?$/i;

/** 判断是否为 generate_file 产生的 fileId（裸 ID 或含 query 参数） */
function isBareFileId(href: string): boolean {
  const path = href.split('?')[0];
  return FILE_ID_RE.test(path);
}

/**
 * 规范化下载链接：
 * 1. 剥离 LLM 误加的 scheme 前缀（sandbox:/ file:/ 等，保留 http/https）
 * 2. 识别裸 fileId（LLM 丢前缀的常见情况），补全为完整下载路径
 * 3. 已含 /runtime/task/download/ 的保持原样
 */
function normalizeDownloadHref(href: string): string {
  if (!href) return href;
  // 剥离伪 scheme
  const m = href.match(/^([a-z][a-z0-9+.-]*):\/+(.*)$/i);
  let stripped = href;
  if (m) {
    const scheme = m[1].toLowerCase();
    const rest = m[2];
    if (scheme === 'http' || scheme === 'https') {
      // 完整 URL：提取 pathname 部分判断
      try {
        const u = new URL(href);
        if (u.pathname.includes('/runtime/task/download/')) return u.pathname + u.search;
        return href;
      } catch {
        return href;
      }
    }
    stripped = rest;
  }
  // 已含下载路径前缀
  if (stripped.includes('/runtime/task/download/')) {
    return stripped.startsWith('/') ? stripped : '/' + stripped;
  }
  // 裸 fileId：补全为完整下载路径（保留 query 参数如 ?X-Tenant-Id=1）
  if (isBareFileId(stripped)) {
    return AEGIS_DOWNLOAD_PREFIX + stripped;
  }
  return href;
}

function aegisDownloadUrlTransform(url: string): string {
  const normalized = normalizeDownloadHref(url);
  if (normalized !== url) return normalized;
  return defaultUrlTransform(url);
}

/** 文件下载（迁自旧 ChatArea downloadFile） */
async function downloadFile(url: string, filename: string, messageApi: { success: (m: string) => void; error: (m: string) => void }) {
  try {
    const token = storage.getRaw(STORAGE_KEY.TOKEN) ?? '';
    const tenantId = String(storage.get<number | string>(STORAGE_KEY.TENANT_ID, 1));
    const userInfo = storage.get<{ id?: number | string }>(STORAGE_KEY.USER_INFO, {});
    const userId = userInfo.id ? String(userInfo.id) : '1';

    let cleanUrl = url;
    if (url.startsWith('http://') || url.startsWith('https://')) {
      try {
        const urlObj = new URL(url);
        cleanUrl = urlObj.pathname;
      } catch {
        /* 相对路径保持 */
      }
    }
    cleanUrl = cleanUrl.replace(/^\/?api/, '');
    if (!cleanUrl.startsWith('/')) cleanUrl = '/' + cleanUrl;

    const separator = cleanUrl.includes('?') ? '&' : '?';
    const finalUrl = `/api${cleanUrl}${separator}X-Tenant-Id=${tenantId}&X-User-Id=${userId}`;

    const resp = await fetch(finalUrl, {
      headers: {
        Authorization: `Bearer ${token}`,
        'X-Tenant-Id': tenantId || '1',
        'X-User-Id': userId || '1',
      },
    });
    if (!resp.ok) throw new Error(`下载失败: ${resp.status} ${resp.statusText}`);
    const blob = await resp.blob();
    const blobUrl = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = blobUrl;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(blobUrl);
    messageApi.success('已开始下载');
  } catch (err) {
    console.error('文件下载失败:', err);
    messageApi.error('文件下载失败: ' + (err as Error).message);
  }
}

export const AnswerChunk: React.FC<AnswerChunkProps> = ({ event, streaming, isError, markdownStyles }) => {
  const { message } = App.useApp();
  const text = event.payload.text;

  if (!text) {
    if (streaming) {
      return <span style={{ color: '#9ca3af' }}>思考中...</span>;
    }
    return null;
  }

  return (
    <>
      {markdownStyles && <style>{markdownStyles}</style>}
      <div
        className="markdown-body"
        style={{
          fontSize: 14,
          background: isError ? 'var(--color-bg-chat-error)' : 'transparent',
          border: isError ? '1px solid var(--color-error)' : 'none',
          borderRadius: isError ? 8 : 0,
          padding: isError ? '8px 12px' : 0,
          color: isError ? 'var(--color-error)' : 'var(--color-text-on-assistant)',
        }}
      >
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeHighlight]}
          urlTransform={aegisDownloadUrlTransform}
          components={{
            a: (props) => {
              let href = (props as { href?: string }).href || '';
              const children = (props as { children?: React.ReactNode }).children;
              href = normalizeDownloadHref(href);
              const isDownload = href.includes('/runtime/task/download/') || isBareFileId(href);
              if (isDownload) {
                let filename = typeof children === 'string' ? children : '';
                if (!filename) {
                  const pathOnly = href.split('?')[0];
                  filename = decodeURIComponent(pathOnly.substring(pathOnly.lastIndexOf('/') + 1)) || '下载文件';
                }
                return (
                  <a
                    href="#"
                    onClick={(e) => {
                      e.preventDefault();
                      downloadFile(href, filename, message).catch(() => {});
                    }}
                    style={{ cursor: 'pointer' }}
                  >
                    {children}
                  </a>
                );
              }
              return (
                <a href={href} target="_blank" rel="noopener noreferrer">
                  {children}
                </a>
              );
            },
          }}
        >
          {text}
        </ReactMarkdown>
      </div>
    </>
  );
};

export default AnswerChunk;
