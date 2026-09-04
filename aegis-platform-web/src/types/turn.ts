/**
 * @file 轮次执行流时序数据模型
 * @description 将"思考 / 工具调用 / 回答片段 / 错误"统一建模为按 timestamp 严格交错的
 *              同一条 TurnEvent 流，取代旧的"思考独立 blob + 工具扁平列表"分组形态。
 *              实时流与历史轮次共用同一模型，渲染路径合一（见 AssistantTurn 组件）。
 *
 * 设计要点：
 *   1. 事件的唯一真相源是 events[]（按 timestamp 升序），answerText 由 answer 事件派生。
 *   2. 思考段在 tool.call / text.delta 到达时自动 finalize，保证思考↔工具交错。
 *   3. 同一次 LLM 返回的多个 tool_call 共用 batchId，供 ToolGroup 并行聚合（P2）。
 *
 * @author Aegis
 * @since 4.0.0
 */

// ========== 通用状态 ==========

/** 事件状态（思考/工具通用） */
export type TurnEventStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';

// ========== 负载类型 ==========

/** 思考事件负载 */
export interface ThinkingPayload {
  /** 步骤标题 */
  title: string;
  /** 完整详情（流式累积） */
  detail: string;
  /** 完成后摘要（≤ 80 字，用于 preview 折叠态；为空时由前端按规则生成） */
  summary?: string;
  /** 状态 */
  status: TurnEventStatus;
  /** 耗时（完成时填充） */
  durationMs?: number;
  /** 步骤序号（thinking.step 事件携带时用于 upsert） */
  stepIndex?: number;
}

/** 工具调用事件负载 */
export interface ToolPayload {
  /** 调用 ID（对齐 SSE tool.call.id） */
  toolId: string;
  /** 工具名称 */
  toolName: string;
  /** 调用参数 */
  arguments?: Record<string, unknown>;
  /** 执行结果 */
  result?: unknown;
  /** 错误信息（失败时） */
  error?: string;
  /** 状态 */
  status: TurnEventStatus;
  /** 耗时（完成时填充） */
  durationMs?: number;
  /** 完成后动作摘要（如"读取了 3 个文件"） */
  summary?: string;
}

/** 回答片段负载 */
export interface AnswerPayload {
  /** 片段文本（流式中累积；一次轮次可能有多段，被工具调用分隔） */
  text: string;
}

/** 错误事件负载 */
export interface ErrorPayload {
  /** 错误消息（已友好化） */
  message: string;
  /** 错误码 */
  code?: string;
  /** 是否可恢复 */
  recoverable?: boolean;
}

// ========== 判别联合事件 ==========

/** 思考事件 */
export interface ThinkingEvent {
  id: string;
  kind: 'thinking';
  timestamp: number;
  payload: ThinkingPayload;
}

/** 工具调用事件 */
export interface ToolEvent {
  id: string;
  kind: 'tool';
  timestamp: number;
  /** 并行批次 ID：同一次 LLM 返回的多个 tool_call 共用，用于 ToolGroup 聚合 */
  batchId?: string;
  payload: ToolPayload;
}

/** 回答片段事件 */
export interface AnswerEvent {
  id: string;
  kind: 'answer';
  timestamp: number;
  payload: AnswerPayload;
}

/** 错误事件（TurnEvent 联合与 isErrorEvent 内用） */
interface ErrorEvent {
  id: string;
  kind: 'error';
  timestamp: number;
  payload: ErrorPayload;
}

/** 统一执行流事件（判别联合，kind 为判别字段） */
export type TurnEvent = ThinkingEvent | ToolEvent | AnswerEvent | ErrorEvent;

// ========== 轮次聚合 ==========

/** 轮次元信息（显示在 TurnHeader） */
export interface TurnMeta {
  /** 模型名 */
  model?: string;
  /** 总耗时（ms） */
  durationMs?: number;
  /** 输入 Token */
  tokenIn?: number;
  /** 输出 Token */
  tokenOut?: number;
  /** 步骤数 */
  stepCount?: number;
  /** 最后一帧是否已 done */
  isComplete?: boolean;
}

// ========== 用户消息上下文快照 ==========

/** 用户消息发送时的资源引用快照（用于历史回显"当时引用了什么"） */
export interface MessageContext {
  /** 知识库 ID 列表 */
  kbIds?: string[];
  /** MCP 服务 ID 列表 */
  mcpIds?: string[];
  /** 附件列表（仅显示字段：fileName/name） */
  attachments?: Array<{ fileId?: string; fileName?: string; name?: string }>;
  /** 技能引用 */
  skills?: string[];
}

// ========== 常量 ==========

/** 默认自动收起延迟（事件完成后） */
export const TURN_AUTO_COLLAPSE_MS = 1500;

/** 结果摘要截断长度 */
export const RESULT_SUMMARY_MAX = 150;

// ========== 派生工具 ==========

/**
 * 生成事件唯一 ID。
 * 与旧 useExecutionTimeline.genId 保持同构（时间戳+随机），避免事件间碰撞。
 */
export const genTurnEventId = (): string => `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

/**
 * 判别是否为思考事件（类型守卫，供组件分发使用）。
 */
export const isThinkingEvent = (e: TurnEvent): e is ThinkingEvent => e.kind === 'thinking';

/** 是否为工具事件 */
export const isToolEvent = (e: TurnEvent): e is ToolEvent => e.kind === 'tool';

/** 是否为回答片段事件 */
export const isAnswerEvent = (e: TurnEvent): e is AnswerEvent => e.kind === 'answer';

/** 是否为错误事件 */
export const isErrorEvent = (e: TurnEvent): e is ErrorEvent => e.kind === 'error';

/**
 * 从事件流派生聚合回答文本（answer 事件按 timestamp 顺序串联）。
 * 同时作为"消息内容"的真相来源（复制/重新生成均基于此）。
 */
export const deriveAnswerText = (events: TurnEvent[] | undefined): string => {
  if (!events || events.length === 0) return '';
  return events
    .filter(isAnswerEvent)
    .map((e) => e.payload.text)
    .join('');
};

/**
 * 从事件流派生思考全文（兼容旧 reasoning blob 降级场景）。
 */
export const deriveReasoningText = (events: TurnEvent[] | undefined): string => {
  if (!events || events.length === 0) return '';
  return events
    .filter(isThinkingEvent)
    .map((e) => (e.payload.detail || e.payload.summary || '').trim())
    .filter(Boolean)
    .join('\n\n');
};

/**
 * 从事件流派生工具调用列表（兼容旧 toolCalls 字段降级/历史展示）。
 */
export const deriveToolCalls = (events: TurnEvent[] | undefined): ToolPayload[] => {
  if (!events || events.length === 0) return [];
  return events.filter(isToolEvent).map((e) => e.payload);
};
