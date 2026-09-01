/**
 * @file 会话/任务 API 客户端
 * @description 对接 aegis-runtime 服务，封装：
 *   - 任务对话（SSE 流式 / 非流式）
 *   - 会话管理（列表/删除/重命名）
 *   - 历史消息查询
 *   - 任务控制（中断/HITL审批）
 *   - 文件上传
 *   - 可用资源查询（知识库/MCP）
 *
 * 后端接口路径（来自 TaskController、TaskControlController、AgentResourceController、FileUploadController）：
 *   POST   /api/runtime/task/chat          → SSE 流式对话
 *   POST   /api/runtime/task/regenerate    → SSE 重新生成 AI 消息
 *   POST   /api/runtime/task/edit          → SSE 编辑用户消息并重新生成
 *   POST   /api/runtime/task/sync          → 非流式对话
 *   POST   /api/runtime/task/sessions      → 会话列表
 *   POST   /api/runtime/task/history       → 历史消息
 *   DELETE /api/runtime/task/session/{id}  → 删除会话
 *   DELETE /api/runtime/task/session/{id}/message/{msgId} → 删除消息
 *   POST   /api/runtime/control/{id}/interrupt → 中断任务
 *   POST   /api/runtime/control/{id}/hitl/approve → HITL 审批通过
 *   POST   /api/runtime/control/{id}/hitl/reject  → HITL 审批驳回
 *   GET    /api/runtime/agent/resource/available → 可用资源
 *   POST   /api/runtime/task/upload      → 文件上传
 */
import { http } from '@/api/request';
import { STORAGE_KEY } from '@/utils/constants';
import { storage } from '@/utils/storage';
import { safeJsonParse } from '@/utils/number';
import type {
  ChatSession,
  ChatMessage,
  ChatRequestBody,
  AttachmentRef,
  SkillRef,
  AvailableResource,
  AgentSkill,
  Session,
  Message,
  ToolCall,
  KbReference,
} from '@/types/session';
import { MessageRole } from '@/types/enum';
import type { TurnEvent } from '@/types/turn';

// ============ 辅助方法 ============

/**
 * 从本地存储读取当前用户ID。
 * 注意：X-User-Id 以网关 JWT 解析为准，但 fetch/XHR 直连时仍需显式携带。
 */
function getCurrentUserId(): string {
  const userInfo = storage.get<{ id?: number | string }>(STORAGE_KEY.USER_INFO, {});
  return userInfo.id ? String(userInfo.id) : '1';
}

function getCurrentTenantId(): string {
  const tenantId = storage.get<number | string>(STORAGE_KEY.TENANT_ID, 1);
  return tenantId ? String(tenantId) : '1';
}

function getToken(): string {
  return storage.getRaw(STORAGE_KEY.TOKEN) ?? '';
}

// ============ 类型定义 ============

/** 会话列表查询参数 */
interface SessionListParams {
  page?: number;
  size?: number;
  agentId?: string;
}

/** 会话列表响应 */
interface SessionListResult {
  sessions: Session[];
  total: number;
  page: number;
  size: number;
}

/** 历史消息响应 */
interface HistoryResult {
  sessionId: string;
  /** 后端返回的原始 SessionMessage 对象列表（含 messageType/toolCallId 等后端字段，非前端 Message 结构） */
  messages: Record<string, unknown>[];
  count: number;
}

/** 流式对话事件 */
export interface StreamChatEvent {
  event: string;
  data: unknown;
}

/** 流式对话选项 */
export interface StreamChatOptions {
  onEvent: (event: StreamChatEvent) => void;
  onError?: (error: Error) => void;
  onComplete?: () => void;
  onClose?: () => void;
}

// ============ 会话管理 ============

/** 获取会话列表（POST 方式，对齐后端 TaskController.sessions） */
export const getSessionList = async (params?: SessionListParams): Promise<SessionListResult> => {
  const body = {
    page: params?.page ?? 1,
    size: params?.size ?? 20,
    // 保持字符串传递，避免 Number() 对雪花ID(>2^53) 精度丢失
    agentId: params?.agentId ?? undefined,
  };
  return http.post<SessionListResult>('/runtime/task/sessions', body);
};

/**
 * 后端消息角色枚举到大写 => 前端 MessageRole 小写映射。
 * 后端 SessionMessage.messageType 为 USER / ASSISTANT / TOOL_CALL / TOOL_RESULT / KB_REFERENCE，
 * 前端 Message.role 为 user / assistant / system / tool。
 */
const BACKEND_ROLE_TO_FRONTEND: Record<string, MessageRole> = {
  USER: MessageRole.USER,
  ASSISTANT: MessageRole.ASSISTANT,
  TOOL_CALL: MessageRole.ASSISTANT,
  TOOL_RESULT: MessageRole.TOOL,
  KB_REFERENCE: MessageRole.SYSTEM,
};

/**
 * 将后端 SessionMessage 原始对象批量映射为前端 Message，并重建时序 events。
 *
 * 后端按行存储：USER / ASSISTANT / TOOL_CALL / TOOL_RESULT / KB_REFERENCE。
 * 本函数把每个 ASSISTANT 行作为"轮次锚点"，其后的 TOOL/KB 行并入同轮 events，
 * 严格按 createTime(timestamp) 时序交错——解决旧实现"压扁成单条 Message 丢时序"的问题。
 *
 * 降级兼容：若 ASSISTANT 行缺失或为单行旧数据，仍产出可渲染 Message（events 为空，
 * 组件层用 reasoning/toolCalls/content 降级）。
 */
function toFrontendMessages(raws: Record<string, unknown>[]): Message[] {
  const result: Message[] = [];
  for (const raw of raws) {
    const messageType = String(raw.messageType ?? '');
    const role = BACKEND_ROLE_TO_FRONTEND[messageType] ?? MessageRole.ASSISTANT;
    const createdAt = raw.createTime ? String(raw.createTime) : undefined;
    const ts = createdAt ? new Date(createdAt).getTime() : Date.now();

    // 工具调用行：作为事件并入"当前最后一个 assistant 轮次"
    if (messageType === 'TOOL_CALL' || messageType === 'TOOL_RESULT') {
      const lastAssistant = [...result].reverse().find((m) => m.role === MessageRole.ASSISTANT);
      if (lastAssistant) {
        const toolId = String(raw.toolCallId ?? '');
        const toolName = String(raw.toolName ?? '');
        let parsedParams: Record<string, unknown> | undefined;
        if (typeof raw.toolParams === 'string') {
          parsedParams = safeJsonParse<Record<string, unknown>>(raw.toolParams) ?? undefined;
        }
        const hasResult = raw.toolResult != null;
        let parsedResult: unknown = raw.toolResult;
        if (typeof raw.toolResult === 'string') {
          parsedResult = safeJsonParse(raw.toolResult, raw.toolResult);
        }
        const status: 'SUCCESS' | 'FAILED' | 'RUNNING' =
          messageType === 'TOOL_RESULT' && raw.isError === true ? 'FAILED'
          : hasResult ? 'SUCCESS' : 'RUNNING';
        // 合并到同 toolId 的既有 tool 事件；否则新增
        const existingToolEvent = (lastAssistant.events ?? []).find(
          (e) => e.kind === 'tool' && e.payload.toolId === toolId,
        );
        lastAssistant.events = [...(lastAssistant.events ?? [])];
        if (existingToolEvent && existingToolEvent.kind === 'tool') {
          // 更新既有 tool 事件（补 result/status/duration）
          lastAssistant.events = lastAssistant.events.map((e) =>
            e.kind === 'tool' && e.payload.toolId === toolId
              ? {
                  ...e,
                  payload: {
                    ...e.payload,
                    result: hasResult ? parsedResult : e.payload.result,
                    status,
                    durationMs: raw.durationMs != null ? Number(raw.durationMs) : e.payload.durationMs,
                    error: status === 'FAILED' ? String(raw.toolResult ?? '') : e.payload.error,
                  },
                }
              : e,
          );
        } else {
          lastAssistant.events.push({
            id: `hist-tool-${toolId || ts}`,
            kind: 'tool',
            timestamp: ts,
            payload: {
              toolId,
              toolName,
              arguments: parsedParams,
              result: hasResult ? parsedResult : undefined,
              status,
              durationMs: raw.durationMs != null ? Number(raw.durationMs) : undefined,
            },
          });
        }
        // 同步 toolCalls（降级字段）
        const tc: ToolCall = {
          id: toolId,
          name: toolName,
          arguments: parsedParams,
          result: parsedResult,
          status: status === 'RUNNING' ? 'running' : status === 'FAILED' ? 'failed' : 'success',
          durationMs: raw.durationMs != null ? Number(raw.durationMs) : undefined,
        };
        lastAssistant.toolCalls = [...(lastAssistant.toolCalls ?? []).filter((x) => x.id !== toolId), tc];
      }
      continue;
    }

    // KB 引用行：并入当前 assistant 轮次的 kbReferences
    if (messageType === 'KB_REFERENCE' || messageType === 'KB_REFERENCE ') {
      const lastAssistant = [...result].reverse().find((m) => m.role === MessageRole.ASSISTANT);
      if (lastAssistant && typeof raw.kbRefs === 'string' && raw.kbRefs.length > 2) {
        const refs = safeJsonParse<KbReference[]>(raw.kbRefs) ?? [];
        lastAssistant.kbReferences = [...(lastAssistant.kbReferences ?? []), ...refs];
      }
      continue;
    }

    // USER / ASSISTANT / SYSTEM 行：新建 Message
    let kbReferences: KbReference[] | undefined;
    if (typeof raw.kbRefs === 'string' && raw.kbRefs.length > 2) {
      kbReferences = safeJsonParse<KbReference[]>(raw.kbRefs) ?? undefined;
    }
    const reasoning = raw.reasoning ? String(raw.reasoning) : undefined;

    const msg: Message = {
      id: String(raw.id ?? ''),
      sessionId: String(raw.sessionId ?? ''),
      role,
      content: String(raw.content ?? ''),
      reasoning,
      toolCalls: undefined,
      kbReferences,
      isError: raw.isError === true || raw.isError === 'true',
      createdAt,
    };

    // ASSISTANT 行：初始化 events，把 reasoning 作为首个 thinking 段、content 作为 answer 段
    if (role === MessageRole.ASSISTANT) {
      const events: TurnEvent[] = [];
      if (reasoning) {
        events.push({
          id: `hist-thinking-${msg.id}`,
          kind: 'thinking',
          timestamp: ts,
          payload: { title: '思考过程', detail: reasoning, status: 'SUCCESS' },
        });
      }
      if (msg.content) {
        events.push({
          id: `hist-answer-${msg.id}`,
          kind: 'answer',
          timestamp: ts + 1,
          payload: { text: msg.content },
        });
      }
      msg.events = events;
      msg.turnMeta = { isComplete: true };
    }
    result.push(msg);
  }
  return result;
}

/** 获取会话历史消息 */
export const getMessages = async (sessionId: string): Promise<Message[]> => {
  const body = { sessionId, limit: 50 };
  const resp = await http.post<HistoryResult>('/runtime/task/history', body);
  const rawMessages = resp?.messages ?? [];
  return toFrontendMessages(rawMessages);
};

/** 删除会话 */
export const deleteSession = async (sessionId: string): Promise<void> => {
  return http.delete(`/runtime/task/session/${sessionId}`);
};

/** 删除消息 */
/** 删除消息（messageId 为后端雪花ID，必须以 string 传递避免精度丢失） */
export const deleteMessage = async (sessionId: string, messageId: string): Promise<void> => {
  return http.delete(`/runtime/task/session/${sessionId}/message/${messageId}`);
};

/** 取消/中断任务 */
export const cancelTask = async (sessionId: string): Promise<void> => {
  return http.post(`/runtime/control/${sessionId}/interrupt`);
};

/** HITL 审批通过 */
export const approveHitl = async (sessionId: string): Promise<void> => {
  return http.post(`/runtime/control/${sessionId}/hitl/approve`);
};

/** HITL 审批驳回 */
export const rejectHitl = async (sessionId: string): Promise<void> => {
  return http.post(`/runtime/control/${sessionId}/hitl/reject`);
};

// ============ 对话 ============

/** 发送消息（非流式，用于简单场景或测试） */
export const sendMessage = async (
  body: ChatRequestBody
): Promise<ChatMessage> => {
  return http.post<ChatMessage>('/runtime/task/sync', body);
};

/** 流式连接静默超时（毫秒）：超过该时长未收到任何事件视为连接停滞 */
const SSE_STALL_TIMEOUT_MS = 45_000;

/**
 * 流式对话（SSE）
 *
 * 健壮性增强（任务 12）：
 * - **停滞看门狗**：连接建立后启动定时器，每次收到事件重置；超过 {@link SSE_STALL_TIMEOUT_MS}
 *   无事件则判定连接停滞，主动 abort 并通过 onError 上报，避免 UI 永久等待。
 * - **可重试错误**：onError 收到 `SseStallError` / 网络异常时，调用方可据 `err.retryable=true`
 *   决定是否重发（见 useWorkbenchChat 的退避重试）。
 *
 * 任务 8：`url` 参数支持 /chat（默认）、/regenerate、/edit 三个 SSE 端点。
 *
 * @param body    请求体
 * @param options 流回调
 * @param url     目标端点（默认 /api/runtime/task/chat）
 * @returns AbortController，用于中断对话
 */
export const streamChat = (
  body: ChatRequestBody,
  options: StreamChatOptions,
  url: string = '/api/runtime/task/chat'
): AbortController => {
  const controller = new AbortController();
  const { onEvent, onError, onComplete, onClose } = options;

  let lastEventTime = Date.now();
  let stallTimer: ReturnType<typeof setInterval> | null = null;
  let errFired = false;
  let closed = false;

  const clearStallWatchdog = () => {
    if (stallTimer) { clearInterval(stallTimer); stallTimer = null; }
  };
  /** onClose 幂等守护：保证整个生命周期只触发一次 */
  const safeClose = () => {
    if (closed) return;
    closed = true;
    clearStallWatchdog();
    onClose?.();
  };

  if (import.meta.env.DEV) {
    console.log('[streamChat] sending body:', JSON.stringify(body, null, 2));
  }

  fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      'Authorization': `Bearer ${getToken()}`,
      'X-Tenant-Id': getCurrentTenantId(),
      'X-User-Id': getCurrentUserId(),
    },
    body: JSON.stringify(body),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const reader = response.body?.getReader();
      const decoder = new TextDecoder();
      if (!reader) { safeClose(); return; }

      // 启动停滞看门狗：每 5s 检查一次，超时则 abort 并上报可重试错误
      stallTimer = setInterval(() => {
        if (Date.now() - lastEventTime > SSE_STALL_TIMEOUT_MS) {
          clearStallWatchdog();
          const stallErr = new Error('流式响应超时停滞，连接可能已断开') as Error & { retryable?: boolean };
          stallErr.name = 'SseStallError';
          stallErr.retryable = true;
          if (!errFired) {
            errFired = true;
            onError?.(stallErr);
          }
          try { controller.abort(); } catch { /* no-op */ }
          safeClose();
        }
      }, 5_000);

      let buffer = '';
      let pendingEvent = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';

        for (const line of lines) {
          if (line.startsWith('event:')) {
            pendingEvent = line.slice(6).trim();
          } else if (line.startsWith('data:')) {
            const dataStr = line.slice(5).trim();
            const eventName = pendingEvent || 'message';
            pendingEvent = '';

            // 收到任意事件，重置停滞计时
            lastEventTime = Date.now();

            if (dataStr === '[DONE]') {
              onEvent({ event: 'done', data: null });
              onComplete?.();
              safeClose();
              return;
            }
            const parsed = safeJsonParse<Record<string, unknown>>(dataStr);
            // 后端返回的是 AgentEvent，结构为 { event: "...", data: {...} }
            // 但 SSE 的 event 字段已经在外层，所以 data 字段就是实际数据
            onEvent({ event: eventName, data: parsed != null ? (parsed.data ?? parsed) : dataStr });
          }
        }
      }
      onComplete?.();
      safeClose();
    })
    .catch((err: Error) => {
      if (err.name === 'AbortError') {
        // 用户主动中断或停滞看门狗触发的 abort：Aborted 事件供上层区分，不视为错误
        onEvent({ event: 'aborted', data: null });
      } else if (!errFired) {
        errFired = true;
        const retryableErr = err as Error & { retryable?: boolean };
        if (retryableErr.retryable === undefined) retryableErr.retryable = true;
        onError?.(err);
      }
      safeClose();
    });

  return controller;
};

// ============ 文件上传 ============

/**
 * 上传附件文件
 */
export const uploadFile = async (
  file: File,
  onProgress?: (percent: number) => void
): Promise<AttachmentRef> => {
  const formData = new FormData();
  formData.append('file', file);

  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/runtime/task/upload', true);
    xhr.setRequestHeader('Authorization', `Bearer ${getToken()}`);
    xhr.setRequestHeader('X-Tenant-Id', getCurrentTenantId());
    xhr.setRequestHeader('X-User-Id', getCurrentUserId());

    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) {
        onProgress(Math.round((e.loaded / e.total) * 100));
      }
    };

    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        const response = safeJsonParse<Record<string, unknown>>(xhr.responseText);
        if (response) {
          resolve((response as { data?: unknown }).data || response);
        } else {
          reject(new Error('Invalid response'));
        }
      } else {
        reject(new Error(`Upload failed: ${xhr.status}`));
      }
    };

    xhr.onerror = () => reject(new Error('Upload failed'));
    xhr.onabort = () => reject(new Error('Upload aborted'));

    xhr.send(formData);
  });
};

// ============ 资源查询 ============

/** 获取可用资源（知识库 + MCP服务） */
export const getAvailableResources = async (agentId?: string): Promise<AvailableResource> => {
  const url = agentId
    ? `/runtime/agent/resource/available?agentId=${agentId}`
    : '/runtime/agent/resource/available';
  return http.get<AvailableResource>(url);
};

// ============ 导出类型 ============
export type {
  ChatSession,
  ChatMessage,
  ChatRequestBody,
  AttachmentRef,
  SkillRef,
  AvailableResource,
  AgentSkill,
  Session,
  Message,
};
