/**
 * @file useExecutionTimeline Hook
 * @description 管理统一执行时间线的状态，将 SSE 事件流转换为时序化的 TimelineEvent 数组。
 *              支持 reasoning.delta 流式思考与 tool.call 交错，实现真正的时序化展示。
 * @author Aegis
 * @since 3.0.0
 */
import { useState, useCallback, useRef, useMemo } from 'react';
import type {
  TimelineEvent,
  TimelineEventStatus,
  TimelineStats,
  ThinkingPayload,
  ToolCallPayload,
} from '@/types/timeline';

/** 从 SSE 事件创建唯一 ID */
const genId = () => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

/**
 * 执行时间线 Hook。
 *
 * @example
 * ```tsx
 * const {
 *   events, stats, isActive,
 *   markActive, resetTimeline, markComplete,
 *   startReasoningSegment, appendReasoningDelta, finalizeReasoningSegment,
 *   appendToolCall, appendToolResult,
 * } = useExecutionTimeline();
 * ```
 */
export function useExecutionTimeline() {
  const [events, setEvents] = useState<TimelineEvent[]>([]);
  const [isActive, setIsActive] = useState(false);
  const sequenceRef = useRef(0);
  const startTimeRef = useRef<number>(0);
  /** 当前正在执行的工具调用 ID → 开始时间（用于计算耗时） */
  const toolStartMapRef = useRef<Map<string, number>>(new Map());

  /** ========== 流式思考追踪（reasoning.delta） ========== */
  /** 当前活跃的思考事件 ID（null 表示没有正在进行的思考） */
  const activeThinkingIdRef = useRef<string | null>(null);
  /** 当前思考段的起始时间 */
  const activeThinkingStartRef = useRef<number>(0);
  /** 当前思考段累积的文本 */
  const activeThinkingTextRef = useRef<string>('');
  /** 思考段计数器（用于生成步骤序号） */
  const thinkingSegmentCountRef = useRef<number>(0);

  /** 开始一个新的思考段（当 reasoning.delta 到达但没有活跃思考时调用） */
  const startReasoningSegment = useCallback((initialText?: string) => {
    // 如果已有活跃思考段，先结束它
    if (activeThinkingIdRef.current) {
      finalizeReasoningSegmentInternal();
    }

    thinkingSegmentCountRef.current += 1;
    const segmentIndex = thinkingSegmentCountRef.current;
    const now = Date.now();
    const eventId = genId();

    const event: TimelineEvent = {
      id: eventId,
      type: 'thinking',
      sequence: nextSeqInternal(),
      timestamp: now,
      payload: {
        title: `思考步骤 ${segmentIndex}`,
        detail: initialText ?? '',
        stepIndex: segmentIndex,
        status: 'RUNNING',
      },
    };

    activeThinkingIdRef.current = eventId;
    activeThinkingStartRef.current = now;
    activeThinkingTextRef.current = initialText ?? '';

    setEvents(prev => [...prev, event]);
    return eventId;
  }, []);

  /** 追加 reasoning.delta 文本到当前思考段 */
  const appendReasoningDelta = useCallback((delta: string) => {
    if (!activeThinkingIdRef.current) {
      // 没有活跃思考段，创建一个新的
      startReasoningSegment(delta);
      return;
    }

    // 追加文本到当前思考事件
    activeThinkingTextRef.current += delta;
    const newText = activeThinkingTextRef.current;

    setEvents(prev => prev.map(e => {
      if (e.id === activeThinkingIdRef.current) {
        const p = e.payload as ThinkingPayload;
        return {
          ...e,
          payload: {
            ...p,
            detail: newText,
          },
        };
      }
      return e;
    }));
  }, [startReasoningSegment]);

  /** 内部：结束当前活跃的思考段（标记为 SUCCESS） */
  const finalizeReasoningSegmentInternal = useCallback(() => {
    const eventId = activeThinkingIdRef.current;
    if (!eventId) return;

    const durationMs = Date.now() - activeThinkingStartRef.current;

    setEvents(prev => prev.map(e => {
      if (e.id === eventId) {
        const p = e.payload as ThinkingPayload;
        return {
          ...e,
          payload: {
            ...p,
            status: 'SUCCESS' as TimelineEventStatus,
            durationMs,
            detail: activeThinkingTextRef.current,
          },
        };
      }
      return e;
    }));

    activeThinkingIdRef.current = null;
    activeThinkingStartRef.current = 0;
    activeThinkingTextRef.current = '';
  }, []);

  /** 公共：结束当前活跃的思考段 */
  const finalizeReasoningSegment = useCallback(() => {
    finalizeReasoningSegmentInternal();
  }, [finalizeReasoningSegmentInternal]);

  /** ========== 序号管理 ========== */

  /** 获取下一个序号 */
  const nextSeqInternal = useCallback(() => {
    sequenceRef.current += 1;
    return sequenceRef.current;
  }, []);

  /** ========== 追加事件 ========== */

  /** 追加思考步骤（显式 stepIndex 事件，兼容旧版 thinking.step） */
  const appendThinking = useCallback((payload: Omit<ThinkingPayload, 'status'> & { status?: TimelineEventStatus }) => {
    // 如果有活跃的 reasoning 思考段，先结束它
    if (activeThinkingIdRef.current) {
      finalizeReasoningSegmentInternal();
    }

    const event: TimelineEvent = {
      id: genId(),
      type: 'thinking',
      sequence: nextSeqInternal(),
      timestamp: Date.now(),
      payload: {
        title: payload.title,
        detail: payload.detail,
        stepIndex: payload.stepIndex,
        status: payload.status ?? 'RUNNING',
        durationMs: payload.durationMs,
      },
    };
    setEvents(prev => [...prev, event]);
    return event;
  }, [nextSeqInternal, finalizeReasoningSegmentInternal]);

  /** 更新指定思考步骤 */
  const updateThinking = useCallback((stepIndex: number, updates: Partial<ThinkingPayload>) => {
    setEvents(prev => prev.map(e => {
      if (e.type === 'thinking' && (e.payload as ThinkingPayload).stepIndex === stepIndex) {
        return { ...e, payload: { ...e.payload, ...updates } as ThinkingPayload };
      }
      return e;
    }));
  }, []);

  /** 追加工具调用（自动结束当前思考段，确保时序交错） */
  const appendToolCall = useCallback((toolName: string, toolId: string, args?: Record<string, unknown>) => {
    // 关键：先结束当前活跃的思考段，确保思考 → 工具 的时序
    if (activeThinkingIdRef.current) {
      finalizeReasoningSegmentInternal();
    }

    toolStartMapRef.current.set(toolId, Date.now());
    const event: TimelineEvent = {
      id: genId(),
      type: 'tool_call',
      sequence: nextSeqInternal(),
      timestamp: Date.now(),
      payload: {
        id: toolId,
        toolName,
        arguments: args,
        status: 'RUNNING',
      },
    };
    setEvents(prev => [...prev, event]);
    return event;
  }, [nextSeqInternal, finalizeReasoningSegmentInternal]);

  /** 追加工具调用结果 */
  const appendToolResult = useCallback((_toolName: string, toolId: string, result: unknown, status: TimelineEventStatus = 'SUCCESS', error?: string) => {
    const startTime = toolStartMapRef.current.get(toolId);
    const durationMs = startTime ? Date.now() - startTime : undefined;
    toolStartMapRef.current.delete(toolId);

    // 更新对应的 tool_call 事件（不添加额外的 tool_result 事件，避免重复渲染）
    setEvents(prev => prev.map(e => {
      if (e.type === 'tool_call' && (e.payload as ToolCallPayload).id === toolId) {
        return {
          ...e,
          payload: {
            ...e.payload,
            status,
            result,
            durationMs,
            error,
          } as ToolCallPayload,
        };
      }
      return e;
    }));
  }, []);

  /** 追加回答片段 */
  const appendAnswerChunk = useCallback((text: string) => {
    // 结束当前思考段
    if (activeThinkingIdRef.current) {
      finalizeReasoningSegmentInternal();
    }

    const event: TimelineEvent = {
      id: genId(),
      type: 'answer_chunk',
      sequence: nextSeqInternal(),
      timestamp: Date.now(),
      payload: { text },
    };
    setEvents(prev => [...prev, event]);
    return event;
  }, [nextSeqInternal, finalizeReasoningSegmentInternal]);

  /** 追加错误事件 */
  const appendError = useCallback((message: string, code?: string, recoverable?: boolean) => {
    // 结束当前思考段
    if (activeThinkingIdRef.current) {
      finalizeReasoningSegmentInternal();
    }

    const event: TimelineEvent = {
      id: genId(),
      type: 'error',
      sequence: nextSeqInternal(),
      timestamp: Date.now(),
      payload: { message, code, recoverable },
    };
    setEvents(prev => [...prev, event]);
    return event;
  }, [nextSeqInternal, finalizeReasoningSegmentInternal]);

  /** ========== 状态控制 ========== */

  /** 重置时间线 */
  const resetTimeline = useCallback(() => {
    setEvents([]);
    setIsActive(false);
    sequenceRef.current = 0;
    startTimeRef.current = 0;
    toolStartMapRef.current.clear();
    activeThinkingIdRef.current = null;
    activeThinkingStartRef.current = 0;
    activeThinkingTextRef.current = '';
    thinkingSegmentCountRef.current = 0;
  }, []);

  /** 标记为活跃（对话开始） */
  const markActive = useCallback(() => {
    setIsActive(true);
    startTimeRef.current = Date.now();
    sequenceRef.current = 0;
    toolStartMapRef.current.clear();
    activeThinkingIdRef.current = null;
    activeThinkingStartRef.current = 0;
    activeThinkingTextRef.current = '';
    thinkingSegmentCountRef.current = 0;
  }, []);

  /** 标记完成 */
  const markComplete = useCallback(() => {
    // 结束最后的思考段
    if (activeThinkingIdRef.current) {
      finalizeReasoningSegmentInternal();
    }
    setIsActive(false);
  }, [finalizeReasoningSegmentInternal]);

  /** ========== 派生统计 ========== */

  const stats = useMemo<TimelineStats>(() => {
    if (events.length === 0) {
      return {
        totalEvents: 0,
        thinkingCount: 0,
        toolCallCount: 0,
        toolResultCount: 0,
        failureCount: 0,
        totalDurationMs: 0,
        isComplete: false,
      };
    }

    const thinkingCount = events.filter(e => e.type === 'thinking').length;
    const toolCallCount = events.filter(e => e.type === 'tool_call').length;
    const toolResultCount = events.filter(e => e.type === 'tool_result').length;
    const failureCount = events.filter(e => {
      const p = e.payload as { status?: string };
      return p.status === 'FAILED';
    }).length;

    const runningCount = events.filter(e => {
      const p = e.payload as { status?: string };
      return p.status === 'RUNNING';
    }).length;

    // 计算总耗时
    const firstEvent = events[0];
    const lastEvent = events[events.length - 1];
    const totalDurationMs = lastEvent.timestamp - firstEvent.timestamp;

    return {
      totalEvents: events.length,
      thinkingCount,
      toolCallCount,
      toolResultCount,
      failureCount,
      totalDurationMs,
      isComplete: runningCount === 0 && events.length > 0,
    };
  }, [events]);

  return {
    // 状态
    events,
    isActive,
    stats,
    // 生命周期
    markActive,
    resetTimeline,
    markComplete,
    // 思考（流式 reasoning.delta）
    startReasoningSegment,
    appendReasoningDelta,
    finalizeReasoningSegment,
    // 思考（显式 stepIndex）
    appendThinking,
    updateThinking,
    // 工具
    appendToolCall,
    appendToolResult,
    // 其他
    appendAnswerChunk,
    appendError,
  };
}
