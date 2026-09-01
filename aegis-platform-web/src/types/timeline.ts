/**
 * @file 执行时间线事件类型定义
 * @description 统一的执行轨迹事件系统，将思考、工具调用、回答等所有事件
 *              合并为一条时序化的执行流，按到达顺序渲染。
 * @author Aegis
 * @since 3.0.0
 */

// ========== 事件类型 ==========

/** 时间线事件类型 */
export type TimelineEventType =
  | 'thinking'       // 思考步骤
  | 'tool_call'      // 工具调用开始
  | 'tool_result'    // 工具调用完成
  | 'answer_chunk'   // AI 回答片段
  | 'answer_done'    // AI 回答完成
  | 'error';         // 执行错误

/** 通用事件状态 */
export type TimelineEventStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

// ========== 负载类型 ==========

/** 思考事件负载 */
export interface ThinkingPayload {
  /** 步骤序号 */
  stepIndex?: number;
  /** 步骤标题 */
  title: string;
  /** 步骤详情 */
  detail?: string;
  /** 状态 */
  status: TimelineEventStatus;
  /** 耗时（完成时才有） */
  durationMs?: number;
}

/** 工具调用事件负载 */
export interface ToolCallPayload {
  /** 调用 ID */
  id: string;
  /** 工具名称 */
  toolName: string;
  /** 调用参数 */
  arguments?: Record<string, unknown>;
  /** 状态 */
  status: TimelineEventStatus;
  /** 耗时（完成时才有） */
  durationMs?: number;
  /** 执行结果 */
  result?: unknown;
  /** 错误信息（失败时） */
  error?: string;
}

/** 回答事件负载 */
export interface AnswerPayload {
  /** 文本片段 */
  text?: string;
  /** 累计完整文本 */
  fullText?: string;
}

/** 错误事件负载 */
export interface ErrorPayload {
  /** 错误码 */
  code?: string;
  /** 错误消息 */
  message: string;
  /** 可恢复 */
  recoverable?: boolean;
}

/** 联合负载类型 */
export type TimelinePayload =
  | ThinkingPayload
  | ToolCallPayload
  | AnswerPayload
  | ErrorPayload;

// ========== 时间线事件 ==========

/** 统一时间线事件 */
export interface TimelineEvent {
  /** 唯一 ID */
  id: string;
  /** 事件类型 */
  type: TimelineEventType;
  /** 时序序号 */
  sequence: number;
  /** 事件时间戳（毫秒） */
  timestamp: number;
  /** 负载数据 */
  payload: TimelinePayload;
}

// ========== 聚合视图 ==========

/** 时间线分组（用于统计） */
export interface TimelineGroup {
  /** 分组类型 */
  type: 'thinking' | 'tool' | 'answer';
  /** 分组内事件 */
  events: TimelineEvent[];
}

/** 时间线统计 */
export interface TimelineStats {
  /** 总事件数 */
  totalEvents: number;
  /** 思考步骤数 */
  thinkingCount: number;
  /** 工具调用数 */
  toolCallCount: number;
  /** 完成的工具调用数 */
  toolResultCount: number;
  /** 失败数 */
  failureCount: number;
  /** 总耗时（毫秒） */
  totalDurationMs: number;
  /** 是否全部完成 */
  isComplete: boolean;
}
