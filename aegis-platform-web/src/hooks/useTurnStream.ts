/**
 * @file useTurnStream Hook
 * @description 单轮（per-message）执行流状态管理：将 SSE 事件转换为按 timestamp 升序的
 *              TurnEvent[]，挂载在对应 assistant Message 上。取代旧的"全局 executionTimeline +
 *              Message.reasoning/toolCalls 双轨"形态，实现思考↔工具↔回答同流交错。
 *
 *              迁移自 useExecutionTimeline 的段管理逻辑（reasoning 切段、tool 开始/结束、
 *              思考段自动 finalize），但收口到 per-message 作用域，并产出统一的 TurnEvent。
 *
 * @author Aegis
 * @since 4.0.0
 */
import { useCallback, useRef } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import type { Message } from '@/types/session';
import {
  genTurnEventId,
  isThinkingEvent,
  isToolEvent,
  isAnswerEvent,
  TURN_AUTO_COLLAPSE_MS,
  RESULT_SUMMARY_MAX,
  type TurnEvent,
  type TurnEventStatus,
  type ThinkingPayload,
  type ToolPayload,
  type TurnMeta,
} from '@/types/turn';
import { safeJsonParse } from '@/utils/number';

/** 生成结果摘要（截断过长内容） —— 迁自旧 ExecutionTimeline，保持行为一致 */
export const getResultSummary = (result: unknown): string => {
  if (result == null) return '';
  let text: string;
  if (typeof result === 'string') {
    const parsed = safeJsonParse(result);
    text = parsed != null ? JSON.stringify(parsed) : result;
  } else if (typeof result === 'object') {
    text = JSON.stringify(result);
  } else {
    text = String(result);
  }
  return text.length > RESULT_SUMMARY_MAX ? text.substring(0, RESULT_SUMMARY_MAX) + '...' : text;
};

/**
 * 单轮执行流管理 Hook（与一条 assistant Message 绑定）。
 *
 * 状态全部托管在 hook 内部 ref/useState；通过 setMessages 把 events 同步回 Message，
 * 供组件层渲染。这样既保留了"单一真相源"（Message.events），又避免高频 setState 抖动。
 */
export function useTurnStream(setMessages: Dispatch<SetStateAction<Message[]>>) {
  // —— 序号与时间 ——
  const seqRef = useRef(0);
  const startTimeRef = useRef<number>(0);

  // —— 思考段追踪（reasoning.delta 流式） ——
  /** 当前活跃思考事件 ID（null 表无进行中思考段） */
  const activeThinkingIdRef = useRef<string | null>(null);
  /** 当前思考段起始时间 */
  const activeThinkingStartRef = useRef<number>(0);
  /** 当前思考段累积文本 */
  const activeThinkingTextRef = useRef<string>('');
  /** 思考段计数器（标题用） */
  const thinkingSegmentCountRef = useRef<number>(0);

  // —— 工具开始时间（计算耗时） ——
  const toolStartMapRef = useRef<Map<string, number>>(new Map());

  /** 取一个排序键：timestamp + seq 兜底，保证同毫秒内稳定升序 */
  const nextSortKey = useCallback((): number => {
    const now = Date.now();
    // 同毫秒兜底：用 seq 微偏移（< 1ms 不会与真实时间冲突）
    return now;
  }, []);

  /** 把更新写回指定的 assistant Message 的 events */
  const patchMessageEvents = useCallback(
    (messageId: string, updater: (events: TurnEvent[]) => TurnEvent[]) => {
      setMessages((prev) =>
        prev.map((m) => (m.id === messageId ? { ...m, events: updater(m.events ?? []) } : m)),
      );
    },
    [setMessages],
  );

  // ========== 思考段（reasoning.delta） ==========

  /** 结束当前活跃思考段（标记 SUCCESS 并填耗时） */
  const finalizeReasoningSegmentInternal = useCallback(
    (messageId: string) => {
      const eventId = activeThinkingIdRef.current;
      if (!eventId) return;

      const durationMs = Date.now() - activeThinkingStartRef.current;
      const finalText = activeThinkingTextRef.current;

      patchMessageEvents(messageId, (events) =>
        events.map((e) => {
          if (e.id === eventId && isThinkingEvent(e)) {
            return {
              ...e,
              payload: {
                ...e.payload,
                status: 'SUCCESS' as TurnEventStatus,
                durationMs,
                detail: finalText,
                summary: e.payload.summary ?? summarizeThinking(finalText),
              } as ThinkingPayload,
            };
          }
          return e;
        }),
      );

      activeThinkingIdRef.current = null;
      activeThinkingStartRef.current = 0;
      activeThinkingTextRef.current = '';
    },
    [patchMessageEvents],
  );

  /** 开始一个新的思考段（reasoning.delta 到达但无活跃段时调用） */
  const startReasoningSegment = useCallback(
    (messageId: string, initialText?: string) => {
      // 已有活跃段则先结束，保证思考↔工具交错
      if (activeThinkingIdRef.current) {
        finalizeReasoningSegmentInternal(messageId);
      }

      thinkingSegmentCountRef.current += 1;
      const segmentIndex = thinkingSegmentCountRef.current;
      const now = nextSortKey();
      const eventId = genTurnEventId();

      activeThinkingIdRef.current = eventId;
      activeThinkingStartRef.current = now;
      activeThinkingTextRef.current = initialText ?? '';

      patchMessageEvents(messageId, (events) => [
        ...events,
        {
          id: eventId,
          kind: 'thinking',
          timestamp: now,
          payload: {
            title: `思考步骤 ${segmentIndex}`,
            detail: initialText ?? '',
            stepIndex: segmentIndex,
            status: 'RUNNING',
          },
        },
      ]);
      return eventId;
    },
    [finalizeReasoningSegmentInternal, patchMessageEvents, nextSortKey],
  );

  /** 追加 reasoning.delta 到当前思考段；无活跃段则新建 */
  const appendReasoningDelta = useCallback(
    (messageId: string, delta: string) => {
      if (!activeThinkingIdRef.current) {
        startReasoningSegment(messageId, delta);
        return;
      }
      activeThinkingTextRef.current += delta;
      const newText = activeThinkingTextRef.current;

      patchMessageEvents(messageId, (events) =>
        events.map((e) => {
          if (e.id === activeThinkingIdRef.current && isThinkingEvent(e)) {
            return { ...e, payload: { ...e.payload, detail: newText } as ThinkingPayload };
          }
          return e;
        }),
      );
    },
    [startReasoningSegment, patchMessageEvents],
  );

  /** 公共：结束当前活跃思考段 */
  const finalizeReasoningSegment = useCallback(
    (messageId: string) => finalizeReasoningSegmentInternal(messageId),
    [finalizeReasoningSegmentInternal],
  );

  // ========== 显式 thinking.step（upsert） ==========

  /** 追加/更新显式 thinking 步骤（stepIndex 对齐） */
  const upsertThinkingStep = useCallback(
    (messageId: string, step: Partial<ThinkingPayload> & { stepIndex?: number; title: string }) => {
      // 有活跃 reasoning 段则先结束
      if (activeThinkingIdRef.current) {
        finalizeReasoningSegmentInternal(messageId);
      }
      const stepIndex = step.stepIndex;
      patchMessageEvents(messageId, (events) => {
        const existingIdx =
          stepIndex != null
            ? events.findIndex((e) => isThinkingEvent(e) && e.payload.stepIndex === stepIndex)
            : -1;
        if (existingIdx >= 0) {
          const next = [...events];
          const ex = next[existingIdx];
          if (isThinkingEvent(ex)) {
            next[existingIdx] = {
              ...ex,
              payload: {
                ...ex.payload,
                title: step.title ?? ex.payload.title,
                detail: step.detail ?? ex.payload.detail,
                status: step.status ?? ex.payload.status,
                durationMs: step.durationMs ?? ex.payload.durationMs,
                summary: step.summary ?? ex.payload.summary,
              } as ThinkingPayload,
            };
          }
          return next;
        }
        const now = nextSortKey();
        return [
          ...events,
          {
            id: genTurnEventId(),
            kind: 'thinking' as const,
            timestamp: now,
            payload: {
              title: step.title,
              detail: step.detail ?? '',
              stepIndex,
              status: step.status ?? 'RUNNING',
              durationMs: step.durationMs,
              summary: step.summary,
            } as ThinkingPayload,
          },
        ];
      });
    },
    [finalizeReasoningSegmentInternal, patchMessageEvents, nextSortKey],
  );

  // ========== 工具 ==========

  /** 追加工具调用开始（自动结束当前思考段，确保思考→工具时序） */
  const appendToolCall = useCallback(
    (messageId: string, toolName: string, toolId: string, args?: Record<string, unknown>, batchId?: string) => {
      if (activeThinkingIdRef.current) {
        finalizeReasoningSegmentInternal(messageId);
      }
      toolStartMapRef.current.set(toolId, Date.now());
      const now = nextSortKey();
      const event: TurnEvent = {
        id: genTurnEventId(),
        kind: 'tool',
        timestamp: now,
        batchId,
        payload: {
          toolId,
          toolName,
          arguments: args,
          status: 'RUNNING',
        },
      };
      patchMessageEvents(messageId, (events) => [...events, event]);
      return event;
    },
    [finalizeReasoningSegmentInternal, patchMessageEvents, nextSortKey],
  );

  /** 追加工具结果（更新对应 tool 事件，不新增 tool_result 事件，避免重复渲染） */
  const appendToolResult = useCallback(
    (
      messageId: string,
      _toolName: string,
      toolId: string,
      result: unknown,
      status: TurnEventStatus = 'SUCCESS',
      error?: string,
    ) => {
      const start = toolStartMapRef.current.get(toolId);
      const durationMs = start ? Date.now() - start : undefined;
      toolStartMapRef.current.delete(toolId);

      patchMessageEvents(messageId, (events) =>
        events.map((e) => {
          if (isToolEvent(e) && e.payload.toolId === toolId) {
            return {
              ...e,
              payload: {
                ...e.payload,
                status,
                result,
                durationMs,
                error,
                summary:
                  e.payload.summary ??
                  summarizeTool(e.payload.toolName, e.payload.arguments, result, status, error),
              } as ToolPayload,
            };
          }
          return e;
        }),
      );
    },
    [patchMessageEvents],
  );

  // ========== 回答片段 ==========

  /** 追加回答片段：若末尾已有 answer 事件则追加文本，否则新建（避免碎片化） */
  const appendAnswerChunk = useCallback(
    (messageId: string, text: string) => {
      if (activeThinkingIdRef.current) {
        finalizeReasoningSegmentInternal(messageId);
      }
      patchMessageEvents(messageId, (events) => {
        // 末尾若是 answer 事件则续接
        for (let i = events.length - 1; i >= 0; i--) {
          const e = events[i];
          if (isAnswerEvent(e)) {
            const next = [...events];
            next[i] = { ...e, payload: { text: e.payload.text + text } };
            return next;
          }
          // 一旦遇到非 answer 事件（思考/工具），停止回溯——说明有中断，新开 answer 段
          break;
        }
        const now = nextSortKey();
        return [
          ...events,
          { id: genTurnEventId(), kind: 'answer' as const, timestamp: now, payload: { text } },
        ];
      });
    },
    [finalizeReasoningSegmentInternal, patchMessageEvents, nextSortKey],
  );

  // ========== 错误 ==========

  /** 追加错误事件 */
  const appendError = useCallback(
    (messageId: string, message: string, code?: string, recoverable?: boolean) => {
      if (activeThinkingIdRef.current) {
        finalizeReasoningSegmentInternal(messageId);
      }
      const now = nextSortKey();
      patchMessageEvents(messageId, (events) => [
        ...events,
        {
          id: genTurnEventId(),
          kind: 'error' as const,
          timestamp: now,
          payload: { message, code, recoverable },
        },
      ]);
    },
    [finalizeReasoningSegmentInternal, patchMessageEvents, nextSortKey],
  );

  // ========== 元信息 / 生命周期 ==========

  /** 写轮次元信息（增量合并） */
  const patchMeta = useCallback(
    (messageId: string, patch: Partial<TurnMeta>) => {
      setMessages((prev) =>
        prev.map((m) =>
          m.id === messageId
            ? {
                ...m,
                turnMeta: { ...(m.turnMeta ?? {}), ...patch } as TurnMeta,
                // 完成时同步 content（answer 派生），供复制/重新生成
                content: patch.isComplete === true
                  ? (m.events?.filter(isAnswerEvent).map((e) => e.payload.text).join('') ?? m.content ?? '')
                  : m.content,
              }
            : m,
        ),
      );
    },
    [setMessages],
  );

  /** 重置（发送新消息前调用） */
  const reset = useCallback(() => {
    seqRef.current = 0;
    startTimeRef.current = 0;
    toolStartMapRef.current.clear();
    activeThinkingIdRef.current = null;
    activeThinkingStartRef.current = 0;
    activeThinkingTextRef.current = '';
    thinkingSegmentCountRef.current = 0;
  }, []);

  /** 标记轮次开始 */
  const markActive = useCallback(() => {
    reset();
    startTimeRef.current = Date.now();
  }, [reset]);

  /** 标记轮次完成（结束残留思考段 + isComplete） */
  const markComplete = useCallback(
    (messageId: string) => {
      if (activeThinkingIdRef.current) {
        finalizeReasoningSegmentInternal(messageId);
      }
      patchMeta(messageId, { isComplete: true });
    },
    [finalizeReasoningSegmentInternal, patchMeta],
  );

  /** 自动收起延迟常量（供组件层 setTimeout 使用） */
  const autoCollapseMs = TURN_AUTO_COLLAPSE_MS;

  return {
    // 思考
    startReasoningSegment,
    appendReasoningDelta,
    finalizeReasoningSegment,
    upsertThinkingStep,
    // 工具
    appendToolCall,
    appendToolResult,
    // 回答
    appendAnswerChunk,
    // 错误
    appendError,
    // 元信息 / 生命周期
    patchMeta,
    markActive,
    markComplete,
    reset,
    autoCollapseMs,
  };
}

// ========== 摘要生成器（P1-2 完成摘要） ==========

/**
 * 思考完成摘要：取首句或前 80 字。供 preview 折叠态展示。
 */
function summarizeThinking(text: string): string {
  if (!text) return '';
  const t = text.trim();
  if (!t) return '';
  const firstSentence = t.split(/[。\n！？!?]/)[0]?.trim();
  if (firstSentence && firstSentence.length <= 80) return firstSentence;
  return t.length > 80 ? t.substring(0, 80) + '...' : t;
}

/**
 * 工具完成动作摘要：根据工具名 + 入参 + 结果生成一句话描述。
 * 规则：read_file/list 类→"读取了 N 个文件"；search→"检索到 N 条结果"；其余→结果摘要点。
 */
function summarizeTool(
  toolName: string,
  args: Record<string, unknown> | undefined,
  result: unknown,
  status: TurnEventStatus,
  error?: string,
): string {
  if (status === 'FAILED') return `执行失败${error ? `：${error}` : ''}`;
  const lower = toolName.toLowerCase();
  // 文件类
  if (lower.includes('read_file') || lower.includes('list_file') || lower.includes('readfile') || lower.includes('listfile')) {
    const path = args?.['path'] ?? args?.['file'] ?? args?.['files'];
    if (typeof path === 'string') return `读取 ${path}`;
    if (Array.isArray(path)) return `读取 ${path.length} 个文件`;
  }
  // 搜索类
  if (lower.includes('search') || lower.includes('检索')) {
    const cnt = countResultItems(result);
    if (cnt != null) return `检索到 ${cnt} 条结果`;
  }
  // 知识库
  if (lower.includes('kb') || lower.includes('knowledge')) {
    const cnt = countResultItems(result);
    if (cnt != null) return `检索到 ${cnt} 个知识片段`;
  }
  // 通用：取结果摘要
  const base = getResultSummary(result);
  return base ? `返回：${base}` : '执行完成';
}

/** 数结果条目数（数组/含 list 字段的对象） */
function countResultItems(result: unknown): number | null {
  if (Array.isArray(result)) return result.length;
  if (result && typeof result === 'object') {
    const obj = result as Record<string, unknown>;
    if (Array.isArray(obj.list)) return obj.list.length;
    if (Array.isArray(obj.items)) return obj.items.length;
    if (Array.isArray(obj.results)) return obj.results.length;
  }
  return null;
}
