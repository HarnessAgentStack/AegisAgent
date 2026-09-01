/**
 * @file 可观测监控 API 客户端
 * @description 封装执行链路查询、详情、统计等接口
 */
import { http } from './request';

const BASE = '/admin/observe';

export interface SessionSummary {
  sessionId: string;
  agentId?: number;
  agentName?: string;
  userId?: number;
  userName?: string;
  traceCount: number;
  successCount: number;
  failCount: number;
  totalDurationMs?: number;
  totalTokens?: number;
  lastActiveTime?: string;
}

export interface TraceRecord {
  traceId: string;
  sessionId?: string;
  agentId?: number;
  agentName?: string;
  userId?: number;
  userName?: string;
  apiPath?: string;
  status: 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT';
  startTime: string;
  endTime?: string;
  durationMs?: number;
  tokenInput?: number;
  tokenOutput?: number;
  errorMsg?: string;
  spanCount?: number;
  sseEventCount?: number;
}

export interface SpanRecord {
  spanId: string;
  parentSpanId?: string;
  spanType: string;
  name: string;
  status: 'SUCCESS' | 'FAILED' | 'SKIPPED';
  startTime: string;
  endTime?: string;
  durationMs?: number;
  inputSummary?: string;
  outputSummary?: string;
  tokenInput?: number;
  tokenOutput?: number;
  errorMsg?: string;
  meta?: Record<string, unknown>;
  roundIndex?: number;
  stepIndex?: number;
  modelName?: string;
  displayName?: string;
  cacheHitTokens?: number;
  cacheMissTokens?: number;
  reasoningTokens?: number;
  requestBody?: Record<string, unknown>;
  responseBody?: Record<string, unknown>;
}

export interface TraceDetail {
  trace: TraceRecord;
  spans: SpanRecord[];
}

export interface ObserveStats {
  totalTraces: number;
  successRate: number;
  avgDurationMs: number;
  p95DurationMs: number;
  totalTokens: number;
  failureDistribution: Record<string, number>;
}

export interface TraceQuery {
  sessionId?: string;
  userId?: number;
  agentId?: number;
  traceId?: string;
  status?: string;
  startTime?: string;
  endTime?: string;
  page?: number;
  size?: number;
}

// ==================== Session Detail (Session-level) Types ====================

export interface SessionDetailResponse {
  sessionId: string;
  agentId?: number;
  agentName?: string;
  userId?: number;
  userName?: string;
  totalRounds: number;
  totalDurationMs?: number;
  totalTokenInput: number;
  totalTokenOutput: number;
  status: string;
  startTime?: string;
  endTime?: string;
  rounds: RoundDetail[];
}

export interface RoundDetail {
  roundIndex: number;
  roundTitle?: string;
  roundType?: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  llmCallCount: number;
  toolCallCount: number;
  tokenInput: number;
  tokenOutput: number;
  status: string;
  steps: StepDetail[];
}

export interface StepDetail {
  stepIndex: number;
  spanId: string;
  spanType: string;
  status: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  displayName?: string;
  name: string;
  // LLM_CALL specific
  modelName?: string;
  modelVersion?: string;
  tokenInput?: number;
  tokenOutput?: number;
  cachedTokens?: number;
  requestSummary?: Record<string, unknown>;
  requestMessages?: Array<Record<string, unknown>>;
  requestMessagesTruncated?: boolean;
  requestMessagesOriginalCount?: number;
  requestTools?: Array<Record<string, unknown>>;
  requestToolsTruncated?: boolean;
  requestToolsOriginalCount?: number;
  messagesFallback?: boolean;
  responseTextPreview?: string;
  responseText?: string;
  responseTextTruncated?: boolean;
  responseTextOriginalLength?: number;
  reasoningPreview?: string;
  responseToolCalls?: Array<Record<string, unknown>>;
  // TOOL_CALL specific
  toolCallId?: string;
  toolArguments?: Record<string, unknown>;
  toolArgumentsJson?: string;
  toolResultPreview?: string;
  toolResult?: unknown;
  toolResultTruncated?: boolean;
  toolResultOriginalLength?: number;
  toolStatus?: string;
  // Extension
  extraMeta?: Record<string, unknown>;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  pageNum: number;
  pageSize: number;
}

export function getSessions(params?: { page?: number; size?: number }): Promise<PageResult<SessionSummary>> {
  return http.get<PageResult<SessionSummary>>(`${BASE}/sessions`, { params });
}

export function getTraces(params?: TraceQuery): Promise<PageResult<TraceRecord>> {
  return http.get<PageResult<TraceRecord>>(`${BASE}/traces`, { params });
}

export function getTraceDetail(traceId: string): Promise<TraceDetail> {
  return http.get<TraceDetail>(`${BASE}/traces/${traceId}`);
}

export function getSessionTraces(sessionId: string, params?: { page?: number; size?: number }): Promise<PageResult<TraceRecord>> {
  return http.get<PageResult<TraceRecord>>(`${BASE}/sessions/${sessionId}/traces`, { params });
}

export function getSessionDetail(sessionId: string): Promise<SessionDetailResponse> {
  return http.get<SessionDetailResponse>(`${BASE}/sessions/${sessionId}/detail`);
}

export function getUserTraces(userId: number, params?: { page?: number; size?: number }): Promise<PageResult<TraceRecord>> {
  return http.get<PageResult<TraceRecord>>(`${BASE}/users/${userId}/traces`, { params });
}

export function getAgentTraces(agentId: number, params?: { page?: number; size?: number }): Promise<PageResult<TraceRecord>> {
  return http.get<PageResult<TraceRecord>>(`${BASE}/agents/${agentId}/traces`, { params });
}

export function getStats(params?: { scope?: string; scopeValue?: string; startTime?: string; endTime?: string }): Promise<ObserveStats> {
  return http.get<ObserveStats>(`${BASE}/stats`, { params });
}