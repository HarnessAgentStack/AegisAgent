/**
 * @file 聊天 SSE Hook
 * @description 封装 streamChat SSE 连接、事件处理（reasoning/tool_call/assistant/hitl/skill_creator 等）、
 *              草稿同步、turnStream（per-message 执行流）驱动、HITL 自动恢复、附件/资源/技能选择。
 * @author wang.zhen
 * @since 1.0.0
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { streamChat, cancelTask, approveHitl as approveHitlApi, rejectHitl as rejectHitlApi } from '@/api/session';
import type { ChatRequestBody, AttachmentRef, SkillRef } from '@/api/session';
import type { Message, SkillCreatorDebugPayload, SkillCreatorPackagePayload } from '@/types/session';
import { MessageRole, HitlStatus } from '@/types/enum';
import { safeJsonParse } from '@/utils/number';
import { useTurnStream } from '@/hooks/useTurnStream';
import type { TurnEventStatus } from '@/types/turn';
import { friendlyErrorMap } from '../utils';
import type { SkillDraft, SkillFileItem } from '../components/SkillStudioPanel';
import type { SkillType } from '@/pages/resource/skill/constants';

/** useApp() 的 message 实例 */
export interface MessageLike {
  success: (msg: string) => void;
  warning: (msg: string) => void;
  error: (msg: string) => void;
  info: (msg: string) => void;
}

/** WorkbenchChat 依赖的外部状态注入 */
export interface UseWorkbenchChatOptions {
  currentAgentId: string | undefined;
  currentSessionId: string | undefined;
  setCurrentSessionId: (id: string | undefined) => void;

  skillCreatorMode: boolean;
  /** setSkillCreatorMode(false) 退出创建模式 */
  setSkillCreatorMode: (v: boolean) => void;
  createEmptySkillDraft: () => SkillDraft;

  // 技能草稿相关 setter
  setDraftSkillId: React.Dispatch<React.SetStateAction<string | null>>;
  setSkillCreatorStage: React.Dispatch<React.SetStateAction<{ phase: string; description: string; progress: number; ts: number } | null>>;
  setSkillDebugResult: React.Dispatch<React.SetStateAction<{ success: boolean; message?: string; steps?: Array<{ name: string; status?: string; detail?: string }>; output?: string; findings?: Array<{ level: string; message: string }>; ts: number } | null>>;
  setSkillFiles: React.Dispatch<React.SetStateAction<SkillFileItem[]>>;
  setSkillDraft: React.Dispatch<React.SetStateAction<SkillDraft>>;

  // 资源选择 —— 直接读取当前值（用 getSnapshot 闭包）
  getSelectedKbIds: () => string[];
  getSelectedMcpIds: () => string[];
  getSelectedAttachments: () => AttachmentRef[];
  getSelectedSkills: () => SkillRef[];

  // 用户
  getUserId: () => string | undefined;
  getTenantId: () => string | undefined;

  // message & loadSessions
  message: MessageLike;
  loadSessions: () => void;
}

/** 辅助：查找最后一条 ASSISTANT 消息索引 */
function findLastAssistantIdx(prev: Message[]): number {
  const reversed = [...prev].reverse();
  const lastAssistantIdx = reversed.findIndex(m => m.role === MessageRole.ASSISTANT);
  if (lastAssistantIdx === -1) return -1;
  return prev.length - 1 - lastAssistantIdx;
}

/** 辅助：更新最后一条 ASSISTANT 消息 */
function updateLastAssistant(prev: Message[], updater: (m: Message) => Message): Message[] {
  const targetIdx = findLastAssistantIdx(prev);
  if (targetIdx === -1) return prev;
  return prev.map((m, i) => i === targetIdx ? updater(m) : m);
}

/** 兜底：将最后一条 ASSISTANT 中仍为 running 的 toolCall 标记为 success */
function finishRunningToolCalls(prev: Message[]): Message[] {
  const lastIdx = findLastAssistantIdx(prev);
  if (lastIdx === -1) return prev;
  const last = prev[lastIdx];
  if (!last.toolCalls?.some(tc => tc.status === 'running')) return prev;
  const updated = {
    ...last,
    toolCalls: last.toolCalls.map(tc =>
      tc.status === 'running' ? { ...tc, status: 'success' as const } : tc
    ),
  };
  return prev.map((m, i) => i === lastIdx ? updated : m);
}

/** Workbench 聊天核心 Hook */
export function useWorkbenchChat(opts: UseWorkbenchChatOptions) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const pendingAutoResumeRef = useRef(false);
  const lastUserTextRef = useRef('');

  const turnStream = useTurnStream(setMessages);
  /** 当前活跃轮次的 assistant message id（供 markComplete 等不带 id 的兜底调用） */
  const activeAssistantIdRef = useRef<string | null>(null);

  /** 前向引用：sendMessage ↔ resumeAfterHitl */
  const resumeAfterHitlRef = useRef<() => void>(() => {});

  /** 重置整个对话（清空 messages + timeline + stop streaming） */
  const resetChat = useCallback(() => {
    abortRef.current?.abort();
    setStreaming(false);
    setMessages([]);
    activeAssistantIdRef.current = null;
    turnStream.reset();
  }, [turnStream]);

  /** 停止生成 */
  const stopStream = useCallback(async () => {
    abortRef.current?.abort();
    setStreaming(false);
    if (opts.currentSessionId) {
      try {
        await cancelTask(opts.currentSessionId);
      } catch (e) {
        console.warn('中断任务通知失败:', e);
      }
    }
  }, [opts.currentSessionId]);

  /** 发送用户消息（核心：启动 SSE + 事件分发） */
  const sendMessage = useCallback((text: string, attachments?: AttachmentRef[], skills?: SkillRef[]) => {
    const trimmedText = text.trim();
    if ((!trimmedText && (!skills || skills.length === 0)) || !opts.currentAgentId || streaming) return;

    // 敏感内容拦截
    const sensitivePatt = /(\d{16,19})|(\d{17}[\dXx])|(password\s*[:=])|(密码\s*[:=])|(身份证)/i;
    if (sensitivePatt.test(trimmedText)) {
      opts.message.warning('⚠️ 检测到敏感内容（银行卡号/身份证号/密码等），已拦截。请脱敏后重试');
      return;
    }

    // 自动激活 skill_creator 模式
    const hasSkillCreator = skills?.some(s => s.skillCode === 'skill_creator');
    if (hasSkillCreator && !opts.skillCreatorMode) {
      opts.setSkillCreatorMode(true);
      const draft = opts.createEmptySkillDraft();
      opts.setSkillDraft(draft);
      opts.setDraftSkillId(null);
      setMessages(prev => [...prev, {
        id: `skill-creator-activated-${Date.now()}`,
        sessionId: prev[0]?.sessionId ?? '',
        role: MessageRole.SYSTEM,
        content: '🔧 技能创建模式已激活：对话将辅助你创建、调试和交付技能',
        createdAt: new Date().toISOString(),
      }]);
    }

    const effectiveSkills = [...(skills || [])];
    if (opts.skillCreatorMode && !effectiveSkills.some(s => s.skillCode === 'skill_creator')) {
      effectiveSkills.push({ skillCode: 'skill_creator' });
    }

    const userMsg: Message = {
      id: `u${Date.now()}`,
      sessionId: opts.currentSessionId || '',
      role: MessageRole.USER,
      content: trimmedText,
      createdAt: new Date().toISOString(),
      context: {
        kbIds: opts.getSelectedKbIds().length > 0 ? [...opts.getSelectedKbIds()] : undefined,
        mcpIds: opts.getSelectedMcpIds().length > 0 ? [...opts.getSelectedMcpIds()] : undefined,
        attachments: opts.getSelectedAttachments().length > 0
          ? opts.getSelectedAttachments().map((a) => ({ fileId: a.fileId, fileName: a.fileName, name: a.name }))
          : undefined,
        skills: effectiveSkills.length > 0 ? effectiveSkills.map((s) => s.skillCode) : undefined,
      },
    };

    turnStream.reset();
    turnStream.markActive();

    const assistantMsg: Message = {
      id: `a${Date.now()}`,
      sessionId: opts.currentSessionId || '',
      role: MessageRole.ASSISTANT,
      content: '',
      reasoning: '',
      reasoningCollapsed: false,
      events: [],
      turnMeta: { isComplete: false },
      createdAt: new Date().toISOString(),
    };
    const assistantMsgId = assistantMsg.id;
    activeAssistantIdRef.current = assistantMsgId;
    setMessages((prev) => [...prev, userMsg, assistantMsg]);
    setStreaming(true);

    const body: ChatRequestBody = {
      agentId: opts.currentAgentId,
      sessionId: opts.currentSessionId,
      message: trimmedText,
      tenantId: opts.getTenantId(),
      userId: opts.getUserId(),
      attachments: (attachments && attachments.length > 0) || opts.getSelectedAttachments().length > 0
        ? (attachments?.length ? attachments : opts.getSelectedAttachments())
        : undefined,
      skills: (effectiveSkills.length > 0) || opts.getSelectedSkills().length > 0
        ? [...effectiveSkills, ...opts.getSelectedSkills().filter(s => !effectiveSkills.some(es => es.skillCode === s.skillCode))]
        : undefined,
      resources: {
        kbIds: opts.getSelectedKbIds().length > 0 ? opts.getSelectedKbIds() : undefined,
        mcpIds: opts.getSelectedMcpIds().length > 0 ? opts.getSelectedMcpIds() : undefined,
      },
    };

    let hasContent = false;
    let hasError = false;
    lastUserTextRef.current = trimmedText;

    // —— 任务 12：断线自动重连（退避重试） ——
    // 仅在"尚未收到任何有效内容"时重试，避免重复推送内容；
    // 连续失败达上限后提示"连接中断请重试"，不卡死 UI。
    const MAX_ATTEMPTS = 3;
    let attempt = 0;

    const startStream = () => {
      attempt += 1;
      abortRef.current = streamChat(body, {
        onEvent: (event) => {
          if (import.meta.env.DEV) console.log('[Workbench] onEvent:', event.event, event.data);
          const raw = event.data as { data?: Record<string, unknown> } | undefined;
          const data = raw?.data ?? (event.data as Record<string, unknown>) ?? {};
          handleSseEvent(event.event, data, {
            setCurrentSessionId: opts.setCurrentSessionId,
            setMessages,
            turnStream,
            assistantMessageId: assistantMsgId,
            setStreaming,
            opts,
            setHasContent: () => { hasContent = true; },
            setHasError: () => { hasError = true; },
            setHasErrorFlag: (v) => { hasError = v; },
            getHasContent: () => hasContent,
            getHasError: () => hasError,
            pendingAutoResumeRef,
          });
        },
        onError: (err) => {
          console.error('SSE error:', err, 'attempt=', attempt, 'hasContent=', hasContent);
          setMessages((prev) => finishRunningToolCalls(prev));
          const retryable = (err as Error & { retryable?: boolean }).retryable === true;
          // 可重试且尚未收到内容且未达上限：退避重试
          if (retryable && !hasContent && !hasError && attempt < MAX_ATTEMPTS) {
            const backoff = Math.min(1000 * Math.pow(2, attempt - 1), 4000);
            opts.message.warning(`连接异常，${backoff / 1000}s 后自动重试（第 ${attempt + 1}/${MAX_ATTEMPTS} 次）…`);
            setTimeout(() => startStream(), backoff);
            return;
          }
          // 最终失败：落错误态
          if (!hasError) {
            setMessages((prev) => {
              const lastAssistantIdx = findLastAssistantIdx(prev);
              const tip = attempt >= MAX_ATTEMPTS && retryable
                ? '连接中断请重试：多次重连失败，请检查网络后重新发送。'
                : '网络连接异常，请检查网络后重试。';
              if (lastAssistantIdx !== -1 && !prev[lastAssistantIdx].content) {
                const updated = [...prev];
                updated[lastAssistantIdx] = {
                  ...updated[lastAssistantIdx],
                  content: tip,
                  isError: true,
                  errorCode: 'NETWORK_ERROR',
                  createdAt: new Date().toISOString(),
                };
                return updated;
              }
              if (lastAssistantIdx !== -1) {
                return [...prev, {
                  id: `error-network-${Date.now()}`,
                  sessionId: prev[0]?.sessionId ?? '',
                  role: MessageRole.ASSISTANT,
                  content: '网络连接异常，智能体回复可能不完整，请重试。',
                  isError: true,
                  errorCode: 'NETWORK_ERROR',
                  createdAt: new Date().toISOString(),
                }];
              }
              return prev;
            });
          }
          setStreaming(false);
          turnStream.markComplete(assistantMsgId);
        },
        onClose: () => {
          if (!hasContent && !hasError) {
            setMessages((prev) => {
              const lastAssistantIdx = findLastAssistantIdx(prev);
              if (lastAssistantIdx !== -1 && !prev[lastAssistantIdx].content && !prev[lastAssistantIdx].isError) {
                const updated = [...prev];
                updated[lastAssistantIdx] = {
                  ...updated[lastAssistantIdx],
                  content: '智能体连接已关闭，未收到回复。请稍后重试。',
                  isError: true,
                  errorCode: 'CONNECTION_CLOSED',
                  createdAt: new Date().toISOString(),
                };
                return updated;
              }
              return prev;
            });
          }
          setStreaming(false);
          turnStream.markComplete(assistantMsgId);
          opts.loadSessions();
        },
      });
    };

    startStream();
  }, [streaming, opts, turnStream]);

  /** HITL 审批通过后恢复执行 */
  const resumeAfterHitl = useCallback(() => {
    if (!opts.currentAgentId || !opts.currentSessionId || streaming) return;

    setStreaming(true);
    const assistantMsg: Message = {
      id: `a-resume-${Date.now()}`,
      sessionId: opts.currentSessionId,
      role: MessageRole.ASSISTANT,
      content: '',
      reasoning: '',
      reasoningCollapsed: false,
      createdAt: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, assistantMsg]);

    const body: ChatRequestBody = {
      agentId: opts.currentAgentId,
      sessionId: opts.currentSessionId,
      message: '',
      tenantId: opts.getTenantId(),
      userId: opts.getUserId(),
      resources: {
        kbIds: opts.getSelectedKbIds().length > 0 ? opts.getSelectedKbIds() : undefined,
        mcpIds: opts.getSelectedMcpIds().length > 0 ? opts.getSelectedMcpIds() : undefined,
      },
    };

    let hasContent = false;
    let hasError = false;
    abortRef.current = streamChat(body, {
      onEvent: (event) => {
        if (import.meta.env.DEV) console.log('[HITL Resume] onEvent:', event.event);
        const raw = event.data as { data?: Record<string, unknown> } | undefined;
        const data = raw?.data ?? (event.data as Record<string, unknown>) ?? {};
        handleHitlResumeEvent(event.event, data, {
          setMessages,
          opts,
          setHasContent: () => { hasContent = true; },
          setHasError: () => { hasError = true; },
          getHasContent: () => hasContent,
          getHasError: () => hasError,
          pendingAutoResumeRef,
          setStreaming,
          turnStream,
          assistantMessageId: assistantMsg.id,
          loadSessions: opts.loadSessions,
        });
      },
      onError: () => {
        setMessages((prev) => finishRunningToolCalls(prev));
        setStreaming(false);
      },
      onClose: () => {
        setStreaming(false);
        opts.loadSessions();
      },
    });
  }, [streaming, opts, turnStream]);

  /** 同步 ref，解决前向引用 */
  useEffect(() => {
    resumeAfterHitlRef.current = resumeAfterHitl;
  }, [resumeAfterHitl]);

  /** HITL 审批同意（后端注入 ConfirmResult 重跑工具） */
  const approveHitl = useCallback(async () => {
    if (!opts.currentSessionId) return;
    try {
      await approveHitlApi(opts.currentSessionId);
      opts.message.success('已同意，任务恢复执行');
      setMessages((prev) => updateLastAssistant(prev, (m) =>
        m.hitl ? { ...m, hitl: { ...m.hitl, status: HitlStatus.APPROVED } } : m
      ));
      setTimeout(() => resumeAfterHitlRef.current(), 300);
    } catch (e) {
      opts.message.error('审批失败: ' + (e as Error).message);
    }
  }, [opts.currentSessionId, opts.message]);

  /** HITL 审批拒绝 */
  const rejectHitl = useCallback(async () => {
    if (!opts.currentSessionId) return;
    try {
      await rejectHitlApi(opts.currentSessionId);
      opts.message.success('已拒绝，任务终止');
      setMessages((prev) => updateLastAssistant(prev, (m) =>
        m.hitl ? { ...m, hitl: { ...m.hitl, status: HitlStatus.REJECTED } } : m
      ));
    } catch (e) {
      opts.message.error('操作失败: ' + (e as Error).message);
    }
  }, [opts.currentSessionId, opts.message]);

  /** 自动恢复流的 ref（done 事件触发 pendingAutoResume → resumeAfterHitl） */
  const triggerResumeAfterHitl = useCallback(() => resumeAfterHitlRef.current(), []);

  /** 任务 8：SSE 流启动内部辅助（regenerate / editMessage 共用 sendMessage 的事件分发 + 退避重试模式） */
  const startSseStream = useCallback((body: ChatRequestBody, url: string, assistantMsgId: string) => {
    let hasContent = false;
    let hasError = false;
    const MAX_ATTEMPTS = 3;
    let attempt = 0;

    const beginStream = () => {
      attempt += 1;
      abortRef.current = streamChat(body, {
        onEvent: (event) => {
          const raw = event.data as { data?: Record<string, unknown> } | undefined;
          const data = raw?.data ?? (event.data as Record<string, unknown>) ?? {};
          handleSseEvent(event.event, data, {
            setCurrentSessionId: opts.setCurrentSessionId,
            setMessages,
            turnStream,
            assistantMessageId: assistantMsgId,
            setStreaming,
            opts,
            setHasContent: () => { hasContent = true; },
            setHasError: () => { hasError = true; },
            setHasErrorFlag: (v) => { hasError = v; },
            getHasContent: () => hasContent,
            getHasError: () => hasError,
            pendingAutoResumeRef,
          });
        },
        onError: (err) => {
          console.error('SSE error:', err, 'attempt=', attempt, 'hasContent=', hasContent);
          setMessages((prev) => finishRunningToolCalls(prev));
          const retryable = (err as Error & { retryable?: boolean }).retryable === true;
          if (retryable && !hasContent && !hasError && attempt < MAX_ATTEMPTS) {
            const backoff = Math.min(1000 * Math.pow(2, attempt - 1), 4000);
            opts.message.warning(`连接异常，${backoff / 1000}s 后自动重试（第 ${attempt + 1}/${MAX_ATTEMPTS} 次）…`);
            setTimeout(() => beginStream(), backoff);
            return;
          }
          if (!hasError) {
            setMessages((prev) => {
              const lastIdx = findLastAssistantIdx(prev);
              const tip = attempt >= MAX_ATTEMPTS && retryable
                ? '连接中断请重试：多次重连失败，请检查网络后重新发送。'
                : '网络连接异常，请检查网络后重试。';
              if (lastIdx !== -1 && !prev[lastIdx].content) {
                const updated = [...prev];
                updated[lastIdx] = { ...updated[lastIdx], content: tip, isError: true, errorCode: 'NETWORK_ERROR', createdAt: new Date().toISOString() };
                return updated;
              }
              if (lastIdx !== -1) {
                return [...prev, { id: `error-network-${Date.now()}`, sessionId: prev[0]?.sessionId ?? '', role: MessageRole.ASSISTANT, content: '网络连接异常，智能体回复可能不完整，请重试。', isError: true, errorCode: 'NETWORK_ERROR', createdAt: new Date().toISOString() }];
              }
              return prev;
            });
          }
          setStreaming(false);
          turnStream.markComplete(assistantMsgId);
        },
        onClose: () => {
          if (!hasContent && !hasError) {
            setMessages((prev) => {
              const lastIdx = findLastAssistantIdx(prev);
              if (lastIdx !== -1 && !prev[lastIdx].content && !prev[lastIdx].isError) {
                const updated = [...prev];
                updated[lastIdx] = { ...updated[lastIdx], content: '智能体连接已关闭，未收到回复。请稍后重试。', isError: true, errorCode: 'CONNECTION_CLOSED', createdAt: new Date().toISOString() };
                return updated;
              }
              return prev;
            });
          }
          setStreaming(false);
          turnStream.markComplete(assistantMsgId);
          opts.loadSessions();
        },
      }, url);
    };
    beginStream();
  }, [opts, turnStream]);

  /** 任务 8：重新生成 AI 消息 —— 删除该 AI 消息及之后所有消息，基于上一条 user 消息重新执行 */
  const regenerate = useCallback((messageId: string) => {
    if (!opts.currentAgentId || !opts.currentSessionId || streaming) return;

    // 保持字符串传递，避免 parseInt 对雪花ID(>2^53) 精度丢失
    const dbMessageId = messageId && !/^[a-z]/i.test(messageId) ? messageId : undefined;

    const newAssistantMsg: Message = {
      id: `a${Date.now()}`,
      sessionId: opts.currentSessionId,
      role: MessageRole.ASSISTANT,
      content: '',
      reasoning: '',
      reasoningCollapsed: false,
      events: [],
      turnMeta: { isComplete: false },
      createdAt: new Date().toISOString(),
    };
    const assistantMsgId = newAssistantMsg.id;
    activeAssistantIdRef.current = assistantMsgId;

    setMessages((prev) => {
      const idx = prev.findIndex(m => m.id === messageId);
      return idx < 0 ? [...prev, newAssistantMsg] : [...prev.slice(0, idx), newAssistantMsg];
    });
    setStreaming(true);
    turnStream.reset();
    turnStream.markActive();

    const body: ChatRequestBody = {
      agentId: opts.currentAgentId,
      sessionId: opts.currentSessionId,
      messageId: dbMessageId,
      tenantId: opts.getTenantId(),
      userId: opts.getUserId(),
    };
    startSseStream(body, '/api/runtime/task/regenerate', assistantMsgId);
  }, [streaming, opts, turnStream, startSseStream]);

  /** 任务 8：编辑用户消息 —— 删除该 user 消息及之后所有消息，持久化新文本重新执行 */
  const editMessage = useCallback((messageId: string, newText: string, attachments?: AttachmentRef[], skills?: SkillRef[]) => {
    if (!opts.currentAgentId || !opts.currentSessionId || streaming) return;
    const trimmedText = newText.trim();
    if (!trimmedText) { opts.message.warning('消息内容不能为空'); return; }

    // 保持字符串传递，避免 parseInt 对雪花ID(>2^53) 精度丢失
    const dbMessageId = messageId && !/^[a-z]/i.test(messageId) ? messageId : undefined;

    const userMsg: Message = {
      id: `u${Date.now()}`,
      sessionId: opts.currentSessionId,
      role: MessageRole.USER,
      content: trimmedText,
      createdAt: new Date().toISOString(),
    };
    const assistantMsg: Message = {
      id: `a${Date.now()}`,
      sessionId: opts.currentSessionId,
      role: MessageRole.ASSISTANT,
      content: '',
      reasoning: '',
      reasoningCollapsed: false,
      events: [],
      turnMeta: { isComplete: false },
      createdAt: new Date().toISOString(),
    };
    const assistantMsgId = assistantMsg.id;
    activeAssistantIdRef.current = assistantMsgId;

    setMessages((prev) => {
      const idx = prev.findIndex(m => m.id === messageId);
      return idx < 0 ? [...prev, userMsg, assistantMsg] : [...prev.slice(0, idx), userMsg, assistantMsg];
    });
    setStreaming(true);
    lastUserTextRef.current = trimmedText;
    turnStream.reset();
    turnStream.markActive();

    const currentAttachments = opts.getSelectedAttachments();
    const currentSkills = opts.getSelectedSkills();
    const body: ChatRequestBody = {
      agentId: opts.currentAgentId,
      sessionId: opts.currentSessionId,
      messageId: dbMessageId,
      message: trimmedText,
      tenantId: opts.getTenantId(),
      userId: opts.getUserId(),
      attachments: attachments?.length ? attachments : (currentAttachments.length > 0 ? currentAttachments : undefined),
      skills: skills?.length ? skills : (currentSkills.length > 0 ? currentSkills : undefined),
      resources: {
        kbIds: opts.getSelectedKbIds().length > 0 ? opts.getSelectedKbIds() : undefined,
        mcpIds: opts.getSelectedMcpIds().length > 0 ? opts.getSelectedMcpIds() : undefined,
      },
    };
    startSseStream(body, '/api/runtime/task/edit', assistantMsgId);
  }, [streaming, opts, turnStream, startSseStream]);

  return {
    messages,
    setMessages,
    streaming,
    setStreaming,
    abortRef,
    sendMessage,
    stopStream,
    resetChat,
    approveHitl,
    rejectHitl,
    turnStream,
    activeAssistantIdRef,
    pendingAutoResumeRef,
    triggerResumeAfterHitl,
    regenerate,
    editMessage,
  };
}

// =========================================================================
// SSE 事件处理辅助函数（避免闭包膨胀，抽取到 Hook 外独立模块作用域）
// =========================================================================

interface SseEventContext {
  setCurrentSessionId: (id: string) => void;
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
  turnStream: ReturnType<typeof useTurnStream>;
  /** 当前活跃轮次的 assistant message id（事件写入目标） */
  assistantMessageId: string;
  setStreaming: React.Dispatch<React.SetStateAction<boolean>>;
  opts: UseWorkbenchChatOptions;
  setHasContent: () => void;
  setHasError: () => void;
  setHasErrorFlag: (v: boolean) => void;
  getHasContent: () => boolean;
  getHasError: () => boolean;
  pendingAutoResumeRef: React.MutableRefObject<boolean>;
}

interface HitlResumeContext {
  setMessages: React.Dispatch<React.SetStateAction<Message[]>>;
  opts: UseWorkbenchChatOptions;
  setHasContent: () => void;
  setHasError: () => void;
  getHasContent: () => boolean;
  getHasError: () => boolean;
  pendingAutoResumeRef: React.MutableRefObject<boolean>;
  setStreaming: React.Dispatch<React.SetStateAction<boolean>>;
  turnStream: ReturnType<typeof useTurnStream>;
  /** 当前活跃轮次的 assistant message id（resume 也写同轮 events） */
  assistantMessageId: string;
  loadSessions: () => void;
}

function syncSkillDraftFromToolResult(opts: UseWorkbenchChatOptions, result: unknown) {
  try {
    if (result && typeof result === 'object') {
      const resultObj = result as Record<string, unknown>;
      const patch: Partial<SkillDraft> = {};
      let needUpdate = false;

      if ('skillId' in resultObj && resultObj.skillId) {
        opts.setDraftSkillId(String(resultObj.skillId));
      }
      if ('skillName' in resultObj) { patch.skillName = resultObj.skillName as string; needUpdate = true; }
      if ('skillCode' in resultObj) { patch.skillCode = resultObj.skillCode as string; needUpdate = true; }
      if ('description' in resultObj) { patch.description = resultObj.description as string; needUpdate = true; }
      if ('instructions' in resultObj) { patch.instructions = resultObj.instructions as string; needUpdate = true; }
      if ('skillType' in resultObj) {
        const st = resultObj.skillType as string;
        if (st === 'ATOMIC' || st === 'COMPOSITE') {
          patch.skillType = st as SkillType; needUpdate = true;
        }
      }
      if ('category' in resultObj) { patch.category = resultObj.category as string; needUpdate = true; }
      if ('securityLevel' in resultObj) { patch.securityLevel = resultObj.securityLevel as string; needUpdate = true; }
      if ('bindingTools' in resultObj) { patch.bindingTools = resultObj.bindingTools as string; needUpdate = true; }

      if (needUpdate) {
        opts.setSkillDraft((prev: SkillDraft) => ({ ...prev, ...patch }));
      }

      // skill_creator 编排事件（stage/draft.created/draft.updated/debug/package）被打包在
      // tool_result 的 _skillEvents 字段内下发，而非独立 SSE 事件。此处逐个回放，
      // 驱动右侧面板的 stage 进度、文件树、debug 结果渲染。
      const skillEvents = (resultObj as { _skillEvents?: Array<{ event: string; data?: Record<string, unknown> }> })._skillEvents;
      if (Array.isArray(skillEvents)) {
        for (const se of skillEvents) {
          if (!se || !se.event || !se.data) continue;
          const evData = se.data as Record<string, unknown>;
          if (se.event === 'skill.creator.stage') {
            opts.setSkillCreatorStage({
              phase: String(evData.phase ?? 'unknown'),
              description: String(evData.description ?? ''),
              progress: Number(evData.progress ?? 0),
              ts: Date.now(),
            });
            if (evData.skillId) opts.setDraftSkillId(String(evData.skillId));
          } else if (se.event === 'skill.draft.created' || se.event === 'skill.draft.updated') {
            syncSkillDraftFromEvent(opts, evData);
          } else if (se.event === 'skill.debug.result' || se.event === 'skill.creator.debug') {
            opts.setSkillDebugResult({
              success: Boolean(evData.success),
              message: evData.message as string | undefined,
              ts: Date.now(),
            });
          } else if (se.event === 'skill.creator.package' || se.event === 'skill.package.result') {
            const files = (evData.files as Array<{ name: string; type: string; path: string; content?: string; children?: unknown[] }> | undefined);
            if (files && Array.isArray(files) && files.length > 0) {
              const mapped: SkillFileItem[] = files.map(f => ({
                name: f.name, type: (f.type === 'dir' ? 'folder' : 'file') as SkillFileItem['type'],
                path: f.path, content: f.content,
              }));
              opts.setSkillFiles(mapped);
            }
          }
        }
      }
    }
  } catch { /* 非 JSON 结果，忽略 */ }
}

function syncSkillDraftFromEvent(opts: UseWorkbenchChatOptions, data: Record<string, unknown>) {
  const payload = data;
  const patch: Partial<SkillDraft> = {};
  let needUpdate = false;
  if (payload.skillId) {
    opts.setDraftSkillId(String(payload.skillId));
  }
  if (payload.skillName) { patch.skillName = payload.skillName as string; needUpdate = true; }
  if (payload.skillCode) { patch.skillCode = payload.skillCode as string; needUpdate = true; }
  if (payload.description) { patch.description = payload.description as string; needUpdate = true; }
  if (payload.instructions) { patch.instructions = payload.instructions as string; needUpdate = true; }
  if (payload.skillType === 'ATOMIC' || payload.skillType === 'COMPOSITE') {
    patch.skillType = payload.skillType as SkillType; needUpdate = true;
  }
  if (payload.category) { patch.category = payload.category as string; needUpdate = true; }
  if (payload.securityLevel) { patch.securityLevel = payload.securityLevel as string; needUpdate = true; }
  if (payload.bindingTools) { patch.bindingTools = payload.bindingTools as string; needUpdate = true; }
  if (needUpdate) {
    opts.setSkillDraft((prev: SkillDraft) => ({ ...prev, ...patch }));
  }
  if (payload.files && Array.isArray(payload.files) && payload.files.length > 0) {
    const files = payload.files as Array<{ name: string; type: string; path: string; content: string; language?: string }>;
    const mapped: SkillFileItem[] = files.map(f => ({
      name: f.name,
      type: (f.type === 'dir' ? 'folder' : 'file') as SkillFileItem['type'],
      path: f.path,
      content: f.content,
      language: f.language,
    }));
    opts.setSkillFiles(mapped);
  }
}

function handleSseEvent(event: string, data: Record<string, unknown>, ctx: SseEventContext) {
  const { setMessages, turnStream, assistantMessageId, setStreaming, opts, pendingAutoResumeRef, setHasContent, setHasError, getHasContent, getHasError } = ctx;
  switch (event) {
    case 'agent_start':
      if (data.sessionId) ctx.setCurrentSessionId(data.sessionId as string);
      break;
    case 'reasoning.delta': {
      const delta = (data.delta as string) ?? '';
      setHasContent();
      if (delta.includes('\n\n')) {
        const paragraphs = delta.split(/\n\n+/);
        paragraphs.forEach((para, idx) => {
          if (idx > 0) turnStream.finalizeReasoningSegment(assistantMessageId);
          turnStream.appendReasoningDelta(assistantMessageId, para);
        });
      } else {
        turnStream.appendReasoningDelta(assistantMessageId, delta);
      }
      setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, reasoning: (m.reasoning ?? '') + delta })));
      break;
    }
    case 'text.delta': {
      setHasContent();
      const delta = (data.delta as string) ?? '';
      turnStream.finalizeReasoningSegment(assistantMessageId);
      turnStream.appendAnswerChunk(assistantMessageId, delta);
      setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, content: m.content + delta })));
      break;
    }
    case 'tool.call': {
      const toolName = String(data.name ?? '未知工具');
      const toolArgs = data.arguments as Record<string, unknown> | undefined;
      const toolId = String(data.id ?? Date.now());
      const toolCall = { id: toolId, name: toolName, arguments: toolArgs, status: 'running' as const };
      turnStream.appendToolCall(assistantMessageId, toolName, toolId, toolArgs, data.batchId as string | undefined);
      setMessages((prev) => updateLastAssistant(prev, (m) => ({
        ...m,
        toolCalls: [...(m.toolCalls ?? []), toolCall],
      })));
      break;
    }
    case 'tool.result': {
      const callId = String(data.id ?? '');
      const resultStatus = data.status === 'error' ? 'failed' : 'success';
      if (resultStatus === 'success') setHasContent();
      let parsedResult = data.result;
      if (typeof parsedResult === 'string') parsedResult = safeJsonParse(parsedResult, parsedResult);

      
      // DEBUG: 追踪 tool.result 事件里 _skillEvents 是否存在
      const _evs = parsedResult && typeof parsedResult === 'object' ? (parsedResult as any)._skillEvents : undefined;
      console.log('[DEBUG tool.result]', { callId, resultStatus, parsedResultType: typeof parsedResult, hasSkillEvents: Array.isArray(_evs), skillEventsLen: _evs?.length, skillId: (parsedResult as any)?.skillId, skillName: (parsedResult as any)?.skillName, rawResultIsString: typeof data.result === 'string', rawResultSample: typeof data.result === 'string' ? (data.result as string).substring(0, 120) : typeof data.result });syncSkillDraftFromToolResult(opts, parsedResult);

      const toolName = String(data.name ?? data.toolName ?? '未知工具');
      turnStream.appendToolResult(
        assistantMessageId, toolName, callId, parsedResult,
        (resultStatus === 'failed' ? 'FAILED' : 'SUCCESS') as TurnEventStatus,
        data.status === 'error' ? String(data.result ?? data.message ?? '') : undefined,
      );
      setMessages((prev) => updateLastAssistant(prev, (m) => {
        if (!m.toolCalls) return m;
        if (!m.toolCalls.some((t) => t.id === callId)) return m;
        return {
          ...m,
          toolCalls: m.toolCalls.map((t) => t.id === callId
            ? { ...t, status: resultStatus, result: parsedResult, durationMs: data.durationMs as number, error: data.status === 'error' ? String(data.result ?? data.message ?? '') : undefined }
            : t),
        };
      }));
      if (resultStatus === 'failed') {
        setMessages((prev) => [...prev, {
          id: `error-tool-${Date.now()}`,
          sessionId: prev[0]?.sessionId ?? '',
          role: MessageRole.ASSISTANT,
          content: `❌ 执行失败: ${String(data.result ?? data.message ?? '未知错误')}`,
          isError: true,
          createdAt: new Date().toISOString(),
        }]);
      }
      break;
    }
    case 'kb.reference': {
      const rawRefs = (data.refs as Array<Record<string, unknown>>) ?? [];
      if (rawRefs.length > 0) {
        setHasContent();
        const refs = rawRefs.map((r, i) => ({
          id: String(r.id ?? `kb-ref-${i}`),
          knowledgeBaseId: String(r.kbId ?? ''),
          knowledgeBaseName: String(r.kbName ?? r.knowledgeBaseName ?? ''),
          documentName: String(r.docName ?? r.documentName ?? ''),
          snippet: String(r.content ?? r.snippet ?? ''),
          score: typeof r.score === 'number' ? r.score : 0,
        }));
        setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, kbReferences: [...(m.kbReferences ?? []), ...refs] })));
      }
      const skippedKbs = (data.skippedKbs as Array<{ kbId?: unknown; action?: string; reason?: string }>) ?? [];
      if (skippedKbs.length > 0) {
        const skipSummary = skippedKbs.map(s => `[${s.action === 'REJECT' ? '拒绝' : '档位不匹配'}] ${s.reason ?? ''}`).join('；');
        setMessages((prev) => updateLastAssistant(prev, (m) => ({
          ...m,
          content: (m.content && m.content.length > 0 ? `${m.content}\n\n` : '') + `⚠️ 部分引用的知识库未参与检索：${skipSummary}`,
        })));
      }
      break;
    }
    case 'skill.activated': {
      const codes = (data as { skills?: string[] }).skills ?? [];
      setMessages(prev => [...prev, {
        id: `skill-${Date.now()}`,
        sessionId: prev[0]?.sessionId ?? '',
        role: MessageRole.SYSTEM,
        content: `已启用技能：${codes.map(c => `@${c}`).join('、')}`,
        createdAt: new Date().toISOString(),
      }]);
      break;
    }
    case 'skill.rejected': {
      const payload = data as { skills?: string[]; reason?: string };
      const codes = payload.skills ?? [];
      setMessages(prev => [...prev, {
        id: `skill-rej-${Date.now()}`,
        sessionId: prev[0]?.sessionId ?? '',
        role: MessageRole.SYSTEM,
        content: `技能不可用：${codes.map(c => `@${c}`).join('、')}（${payload.reason ?? '未知原因'}）`,
        isError: true,
        createdAt: new Date().toISOString(),
      }]);
      break;
    }
    case 'skill.creator.stage': {
      const payload = data as { phase?: string; description?: string; progress?: number; skillId?: string };
      opts.setSkillCreatorStage({
        phase: String(payload.phase ?? 'unknown'),
        description: String(payload.description ?? ''),
        progress: Number(payload.progress ?? 0),
        ts: Date.now(),
      });
      if (payload.skillId) opts.setDraftSkillId(payload.skillId);
      break;
    }
    case 'skill.draft.created':
    case 'skill.draft.updated': {
      syncSkillDraftFromEvent(opts, data);
      break;
    }
    case 'skill.debug.result': {
      const payload = data as { success?: boolean; message?: string };
      opts.setSkillDebugResult({
        success: Boolean(payload.success),
        message: payload.message,
        ts: Date.now(),
      });
      break;
    }
    case 'skill.creator.debug': {
      const payload = data as unknown as SkillCreatorDebugPayload;
      opts.setSkillDebugResult({
        success: Boolean(payload.success),
        message: payload.message,
        ts: Date.now(),
      });
      break;
    }
    case 'skill.creator.package': {
      const payload = data as unknown as SkillCreatorPackagePayload;
      if (payload.files && Array.isArray(payload.files) && payload.files.length > 0) {
        const mapped: SkillFileItem[] = payload.files.map(f => ({
          name: f.name, type: f.type, path: f.path, content: f.content,
          children: f.children?.map(c => ({ name: c.name, type: c.type, path: c.path, content: c.content })),
        }));
        opts.setSkillFiles(mapped);
      }
      break;
    }
    case 'hitl.request': {
      const replyId = String(data.replyId ?? '');
      const autoApproved = data.autoApproved === true;
      const toolCalls = (data.toolCalls as Array<{ id: string; name: string; input?: Record<string, unknown> }>) ?? [];
      const toolSummary = toolCalls.map((tc) => `${tc.name}(${JSON.stringify(tc.input ?? {})})`).join(', ');
      setMessages((prev) => updateLastAssistant(prev, (m) => ({
        ...m,
        hitl: {
          id: replyId,
          status: HitlStatus.PENDING,
          summary: autoApproved ? `低风险工具·待确认: ${toolSummary}` : `待审批工具: ${toolSummary}`,
          payload: { toolCalls },
          autoApproved,
        },
      })));
      setStreaming(false);
      break;
    }
    case 'task.status': {
      const tokenIn = Number(data.tokenInput ?? 0);
      const tokenOut = Number(data.tokenOutput ?? 0);
      setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, usage: { input: tokenIn, output: tokenOut, total: tokenIn + tokenOut } })));
      break;
    }
    case 'thinking.step': {
      const stepData = data as unknown as { stepIndex?: number; stepTitle?: string; stepDetail?: string; status?: string; durationMs?: number };
      const stepIndex = stepData.stepIndex || 0;
      const stepStatus = (stepData.status as TurnEventStatus) || 'RUNNING';
      turnStream.upsertThinkingStep(assistantMessageId, {
        stepIndex, title: stepData.stepTitle || '思考步骤', detail: stepData.stepDetail, status: stepStatus, durationMs: stepData.durationMs,
      });
      break;
    }
    case 'thinking.summary': {
      break;
    }
    case 'error': {
      setHasError();
      const code = String(data.code ?? '');
      const rawMsg = String(data.message ?? '生成失败');
      const friendlyMsg = friendlyErrorMap(code, rawMsg);
      const isRecoverable = data.recoverable === true;
      const errSessionId = String(data.sessionId ?? '');

      turnStream.appendError(assistantMessageId, friendlyMsg, code, isRecoverable);
      setMessages((prev) => finishRunningToolCalls(prev));

      setMessages((prev) => {
        const lastAssistantIdx = findLastAssistantIdx(prev);
        if (lastAssistantIdx !== -1 && !prev[lastAssistantIdx].content) {
          const updated = [...prev];
          updated[lastAssistantIdx] = { ...updated[lastAssistantIdx], content: friendlyMsg, isError: true, errorCode: code, errorSessionId: errSessionId, recoverable: isRecoverable };
          return updated;
        }
        return [...prev, {
          id: `error-${Date.now()}`,
          sessionId: errSessionId || prev[0]?.sessionId || '',
          role: MessageRole.ASSISTANT,
          content: `❌ ${friendlyMsg}`,
          isError: true,
          errorCode: code,
          errorSessionId: errSessionId,
          recoverable: isRecoverable,
          createdAt: new Date().toISOString(),
        }];
      });
      setStreaming(false);
      break;
    }
    case 'done': {
      setMessages((prev) => finishRunningToolCalls(prev));
      if (!getHasContent() && !getHasError()) {
        setMessages((prev) => {
          const lastAssistantIdx = findLastAssistantIdx(prev);
          if (lastAssistantIdx !== -1 && !prev[lastAssistantIdx].content) {
            const updated = [...prev];
            updated[lastAssistantIdx] = {
              ...updated[lastAssistantIdx],
              content: '智能体未返回有效回复，可能是服务繁忙或内部错误，请稍后重试。',
              isError: true,
              errorCode: 'EMPTY_RESPONSE',
              createdAt: new Date().toISOString(),
            };
            return updated;
          }
          return [...prev, {
            id: `error-empty-${Date.now()}`,
            sessionId: prev[0]?.sessionId ?? '',
            role: MessageRole.ASSISTANT,
            content: '智能体未返回有效回复，可能是服务繁忙或内部错误，请稍后重试。',
            isError: true,
            errorCode: 'EMPTY_RESPONSE',
            createdAt: new Date().toISOString(),
          }];
        });
      }
      setStreaming(false);
      turnStream.markComplete(assistantMessageId);
      if (pendingAutoResumeRef.current) {
        pendingAutoResumeRef.current = false;
        setTimeout(() => { /* 由 sendMessage 闭包在 onClose 外部处理 */ }, 300);
      }
      break;
    }
  }
}

function handleHitlResumeEvent(event: string, data: Record<string, unknown>, ctx: HitlResumeContext) {
  const { setMessages, pendingAutoResumeRef, setStreaming } = ctx;
  switch (event) {
    case 'text.delta': {
      const delta = (data.delta as string) ?? '';
      setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, content: m.content + delta })));
      break;
    }
    case 'tool.call': {
      const toolName = String(data.name ?? '未知工具');
      const toolArgs = data.arguments as Record<string, unknown> | undefined;
      const toolId = String(data.id ?? Date.now());
      setMessages((prev) => updateLastAssistant(prev, (m) => ({
        ...m, toolCalls: [...(m.toolCalls ?? []), { id: toolId, name: toolName, arguments: toolArgs, status: 'running' as const }],
      })));
      break;
    }
    case 'tool.result': {
      const callId = String(data.id ?? '');
      const resultStatus = data.status === 'error' ? 'failed' : 'success';
      setMessages((prev) => updateLastAssistant(prev, (m) => ({
        ...m, toolCalls: (m.toolCalls ?? []).map((tc) =>
          tc.id === callId ? { ...tc, status: resultStatus, result: data.result } : tc
        ),
      })));
      // 恢复流与正常流共用 tool_result 载荷：skill_creator 的元数据与 _skillEvents
      // 编排事件同样在此下发，必须同步驱动右侧技能面板。此前恢复流缺失该逻辑，
      // 导致 HITL 审批通过后文件树不渲染、调试/保存按钮置灰。
      let parsedSkillResult: unknown = data.result;
      if (typeof parsedSkillResult === 'string') {
        try { parsedSkillResult = JSON.parse(parsedSkillResult); } catch { /* 非 JSON，跳过技能同步 */ }
      }
      if (parsedSkillResult && typeof parsedSkillResult === 'object') {
        if (import.meta.env.DEV) {
          const evs = (parsedSkillResult as { _skillEvents?: unknown[] })._skillEvents;
          console.log('[DEBUG resume tool.result]', { callId, hasSkillEvents: Array.isArray(evs), skillEventsLen: Array.isArray(evs) ? evs.length : 0, skillId: (parsedSkillResult as { skillId?: unknown }).skillId });
        }
        syncSkillDraftFromToolResult(ctx.opts, parsedSkillResult);
      }
      break;
    }
    case 'skill.creator.stage': {
      const payload = data as { phase?: string; description?: string; progress?: number; skillId?: string };
      ctx.opts.setSkillCreatorStage({
        phase: String(payload.phase ?? 'unknown'),
        description: String(payload.description ?? ''),
        progress: Number(payload.progress ?? 0),
        ts: Date.now(),
      });
      if (payload.skillId) ctx.opts.setDraftSkillId(payload.skillId);
      break;
    }
    case 'skill.draft.created':
    case 'skill.draft.updated': {
      syncSkillDraftFromEvent(ctx.opts, data);
      break;
    }
    case 'skill.debug.result':
    case 'skill.creator.debug': {
      const payload = data as { success?: boolean; message?: string };
      ctx.opts.setSkillDebugResult({
        success: Boolean(payload.success),
        message: payload.message,
        ts: Date.now(),
      });
      break;
    }
    case 'skill.creator.package': {
      const payload = data as unknown as SkillCreatorPackagePayload;
      if (payload.files && Array.isArray(payload.files) && payload.files.length > 0) {
        const mapped: SkillFileItem[] = payload.files.map(f => ({
          name: f.name, type: f.type, path: f.path, content: f.content,
          children: f.children?.map(c => ({ name: c.name, type: c.type, path: c.path, content: c.content })),
        }));
        ctx.opts.setSkillFiles(mapped);
      }
      break;
    }
    case 'kb.reference': {
      const rawRefs = (data.refs as Array<Record<string, unknown>>) ?? [];
      if (rawRefs.length > 0) {
        const refs = rawRefs.map((r, i) => ({
          id: String(r.id ?? `kb-ref-${i}`),
          knowledgeBaseId: String(r.kbId ?? ''),
          knowledgeBaseName: String(r.kbName ?? r.knowledgeBaseName ?? ''),
          documentName: String(r.docName ?? r.documentName ?? ''),
          snippet: String(r.content ?? r.snippet ?? ''),
          score: typeof r.score === 'number' ? r.score : 0,
        }));
        setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, kbReferences: [...(m.kbReferences ?? []), ...refs] })));
      }
      const skippedKbs = (data.skippedKbs as Array<{ kbId?: unknown; action?: string; reason?: string }>) ?? [];
      if (skippedKbs.length > 0) {
        const skipSummary = skippedKbs.map(s => `[${s.action === 'REJECT' ? '拒绝' : '档位不匹配'}] ${s.reason ?? ''}`).join('；');
        setMessages((prev) => updateLastAssistant(prev, (m) => ({
          ...m,
          content: (m.content && m.content.length > 0 ? `${m.content}\n\n` : '') + `⚠️ 部分引用的知识库未参与检索：${skipSummary}`,
        })));
      }
      break;
    }
    case 'hitl.request': {
      const replyId = String(data.replyId ?? '');
      const autoApproved = data.autoApproved === true;
      const toolCalls = (data.toolCalls as Array<{ id: string; name: string; input?: Record<string, unknown> }>) ?? [];
      const toolSummary = toolCalls.map((tc) => `${tc.name}(${JSON.stringify(tc.input ?? {})})`).join(', ');
      setMessages((prev) => updateLastAssistant(prev, (m) => ({
        ...m,
        hitl: { id: replyId, status: HitlStatus.PENDING, summary: autoApproved ? `低风险工具·待确认: ${toolSummary}` : `待审批工具: ${toolSummary}`, payload: { toolCalls }, autoApproved },
      })));
      setStreaming(false);
      break;
    }
    case 'error': {
      setMessages((prev) => finishRunningToolCalls(prev));
      const code = String(data.code ?? '');
      const rawMsg = String(data.message ?? '生成失败');
      setMessages((prev) => updateLastAssistant(prev, (m) => ({ ...m, content: `❌ ${rawMsg}`, isError: true, errorCode: code })));
      setStreaming(false);
      break;
    }
    case 'done': {
      setMessages((prev) => finishRunningToolCalls(prev));
      setStreaming(false);
      if (pendingAutoResumeRef.current) {
        pendingAutoResumeRef.current = false;
      }
      break;
    }
  }
}
