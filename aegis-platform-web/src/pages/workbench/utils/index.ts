/**
 * @file Workbench 通用工具与样式
 * @description Markdown 渲染样式、错误映射、辅助函数
 * @author wang.zhen
 * @since 1.0.0
 */

/** Markdown 渲染样式（全局注入） */
export const markdownStyles = `
  .markdown-body code { background: #f1f5f9; padding: 2px 6px; border-radius: 4px; font-size: 13px; }
  .markdown-body pre { background: #1e293b; color: #e2e8f0; padding: 12px 16px; border-radius: 8px; overflow-x: auto; margin: 8px 0; }
  .markdown-body pre code { background: transparent; padding: 0; color: inherit; }
  .markdown-body blockquote { border-left: 3px solid #d1d5db; padding-left: 12px; color: #6b7280; margin: 8px 0; }
  .markdown-body table { border-collapse: collapse; width: 100%; margin: 8px 0; }
  .markdown-body th, .markdown-body td { border: 1px solid #e5e7eb; padding: 6px 12px; text-align: left; }
  .markdown-body th { background: #f9fafb; }
  .markdown-body ul, .markdown-body ol { padding-left: 20px; }
  .markdown-body a { color: #4f46e5; text-decoration: underline; }
  .markdown-body img { max-width: 100%; border-radius: 8px; }
  .markdown-body p { margin: 4px 0; }
`;

/** 错误码到用户友好提示的映射 */
export function friendlyErrorMap(code: string, rawMsg: string): string {
  const map: Record<string, string> = {
    BLOCKED: '智能体执行被拦截，可能原因：配额超限或内容审核。请稍后重试或联系管理员。',
    HARNESS_UNAVAILABLE: '智能体运行时暂不可用，请稍后重试。',
    INTERNAL_ERROR: '智能体处理请求时遇到内部错误，请稍后重试。',
    QUOTA_EXCEEDED: '今日对话配额已用完，请明天再试或联系管理员。',
    TENANT_MISSING: '租户信息缺失，请重新登录后再试。',
    CONFLICT: '当前会话有未完成的任务，请中断后重试。',
    PARAM_ERROR: '请求参数有误，请检查后重试。',
    UNAUTHORIZED: '登录已失效，请重新登录后再试。',
    DUPLICATE_REQUEST: '该请求已处理，请勿重复提交。',
    EMPTY_RESPONSE: '智能体未返回有效回复，可能是服务繁忙或内部错误，请稍后重试。',
    NETWORK_ERROR: '网络连接异常，请检查网络后重试。',
    CONNECTION_CLOSED: '智能体连接已关闭，未收到回复。请稍后重试。',
    SESSION_CONFLICT: '会话状态冲突，请刷新页面重试。',
    SEQ_CONFLICT: '消息序号冲突导致保存失败，请重试。',
    SERVICE_UNAVAILABLE: 'AI 模型服务网络连接异常，可能是网络不稳定，请稍后重试。',
    GATEWAY_TIMEOUT: 'AI 模型响应超时，可能是网络不稳定，请稍后重试。',
  };
  return map[code] ?? rawMsg;
}

/** 会话历史项 */
export interface SessionItem {
  id: string;
  title: string;
  time: string;
}
