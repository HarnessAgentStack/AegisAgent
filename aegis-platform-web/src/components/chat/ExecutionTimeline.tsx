/**
 * @file 统一执行时间线组件
 * @description 将思考、工具调用、回答等所有事件以时序方式交错渲染，
 *              实现"执行录像"式的可视化体验。
 *
 *  视觉效果：
 *    ◉ ① 🧠 思考步骤标题         ← 紧凑一行，状态徽章
 *    ◉ ② 🔧 工具名              ← 展开参数+结果（执行中自动展开）
 *    ◉ ③ 🧠 下一个思考步骤       ← 紧凑一行
 *    ◉ ④ 🔧 下一个工具          ← ...
 *    ...
 *
 * @author Aegis
 * @since 3.0.0
 */
import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Tag, Tooltip } from 'antd';
import {
  LoadingOutlined,
  CheckOutlined,
  CloseOutlined,
  DownOutlined,
  RightOutlined,
  BulbOutlined,
  ToolOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import type {
  TimelineEvent,
  TimelineStats,
  ThinkingPayload,
  ToolCallPayload,
  ErrorPayload,
  TimelineEventStatus,
} from '@/types/timeline';
import { formatDuration } from '@/utils/format';
import { safeJsonParse } from '@/utils/number';

interface ExecutionTimelineProps {
  /** 时间线事件数组（按 sequence 排序） */
  events: TimelineEvent[];
  /** 统计信息 */
  stats?: TimelineStats;
  /** 容器样式 */
  style?: React.CSSProperties;
  /** 默认是否折叠 */
  defaultCollapsed?: boolean;
}

/** 状态 → 视觉配置 */
const STATUS_VISUAL: Record<TimelineEventStatus, { color: string; icon: React.ReactNode; label: string }> = {
  PENDING:  { color: 'default',  icon: <RightOutlined />,     label: '等待' },
  RUNNING:  { color: 'processing', icon: <LoadingOutlined spin />, label: '执行中' },
  SUCCESS:  { color: 'success',  icon: <CheckOutlined />,     label: '完成' },
  FAILED:   { color: 'error',    icon: <CloseOutlined />,     label: '失败' },
};

/** 完成后自动收起延迟 */
const AUTO_COLLAPSE_MS = 3000;

/** 生成结果摘要（截断过长内容） */
const getResultSummary = (result: unknown): string => {
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
  return text.length > 150 ? text.substring(0, 150) + '...' : text;
};

/** 获取工具图标 */
const getToolIcon = (name: string): React.ReactNode => {
  const lower = name.toLowerCase();
  if (lower.includes('kb') || lower.includes('knowledge') || lower.includes('知识库')) {
    return <ApiOutlined style={{ color: '#1890ff' }} />;
  }
  if (lower.includes('web') || lower.includes('search') || lower.includes('搜索')) {
    return <ApiOutlined style={{ color: '#13c2c2' }} />;
  }
  if (lower.includes('http') || lower.includes('request')) {
    return <ApiOutlined style={{ color: '#722ed1' }} />;
  }
  if (lower.includes('image') || lower.includes('file') || lower.includes('文件')) {
    return <ToolOutlined style={{ color: '#eb2f96' }} />;
  }
  if (lower.includes('skill') || lower.includes('技能')) {
    return <ThunderboltOutlined style={{ color: '#faad14' }} />;
  }
  return <ToolOutlined style={{ color: '#fa8c16' }} />;
};

/**
 * 统一执行时间线组件。
 */
export const ExecutionTimeline: React.FC<ExecutionTimelineProps> = ({
  events,
  stats,
  style,
  defaultCollapsed = false,
}) => {
  // 整体折叠状态
  const [isCollapsed, setIsCollapsed] = useState(defaultCollapsed);
  // 各事件展开状态 Map
  const [expandedMap, setExpandedMap] = useState<Map<string, boolean>>(new Map());
  // 自动收起定时器
  const collapseTimersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());
  // 追踪已完成的事件 ID，避免重复触发自动收起
  const completedRef = useRef<Set<string>>(new Set());

  if (!events || events.length === 0) {
    return null;
  }

  /** 监听事件变化，自动展开/收起 */
  useEffect(() => {
    for (const event of events) {
      const e = event.payload as { status?: string };
      const status = e.status as TimelineEventStatus | undefined;

      if (status === 'RUNNING') {
        // 正在执行 → 自动展开
        if (!expandedMap.get(event.id)) {
          setExpandedMap(prev => {
            const next = new Map(prev);
            next.set(event.id, true);
            return next;
          });
        }
        // 清除之前的定时器（如果有）
        const oldTimer = collapseTimersRef.current.get(event.id);
        if (oldTimer) {
          clearTimeout(oldTimer);
          collapseTimersRef.current.delete(event.id);
        }
      } else if ((status === 'SUCCESS' || status === 'FAILED') && !completedRef.current.has(event.id)) {
        // 刚完成 → 展开一段时间后自动收起
        completedRef.current.add(event.id);
        setExpandedMap(prev => {
          const next = new Map(prev);
          next.set(event.id, true);
          return next;
        });
        const timer = setTimeout(() => {
          setExpandedMap(prev => {
            const next = new Map(prev);
            next.set(event.id, false);
            return next;
          });
          collapseTimersRef.current.delete(event.id);
        }, AUTO_COLLAPSE_MS);
        collapseTimersRef.current.set(event.id, timer);
      }
    }

    // 清理已移除的事件
    const currentIds = new Set(events.map(e => e.id));
    collapseTimersRef.current.forEach((timer, id) => {
      if (!currentIds.has(id)) {
        clearTimeout(timer);
        collapseTimersRef.current.delete(id);
      }
    });
  }, [events]); // eslint-disable-line react-hooks/exhaustive-deps

  /** 全部完成后自动折叠整体 */
  useEffect(() => {
    if (stats?.isComplete) {
      const timer = setTimeout(() => {
        setIsCollapsed(true);
      }, 2000);
      return () => clearTimeout(timer);
    }
  }, [stats?.isComplete]);

  /** 组件卸载清理 */
  useEffect(() => {
    return () => {
      collapseTimersRef.current.forEach(t => clearTimeout(t));
      collapseTimersRef.current.clear();
    };
  }, []);

  /** 切换展开 */
  const toggleExpand = (id: string) => {
    const timer = collapseTimersRef.current.get(id);
    if (timer) {
      clearTimeout(timer);
      collapseTimersRef.current.delete(id);
    }
    setExpandedMap(prev => {
      const next = new Map(prev);
      next.set(id, !next.get(id));
      return next;
    });
  };

  /** 渲染单个事件 */
  const renderEvent = (event: TimelineEvent, index: number) => {
    const isExpanded = expandedMap.get(event.id) ?? false;

    // ==== 思考事件 ====
    if (event.type === 'thinking') {
      const p = event.payload as ThinkingPayload;
      const hasDetail = !!p.detail;

      return (
        <div key={event.id} style={{ marginBottom: 2 }}>
          {/* 连接线（除第一个事件外） */}
          {index > 0 && (
            <div style={{
              position: 'absolute',
              left: 11,
              top: -16,
              width: 1,
              height: 16,
              background: '#e8e8e8',
            }} />
          )}

          <div
            onClick={() => hasDetail && toggleExpand(event.id)}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 8,
              cursor: hasDetail ? 'pointer' : 'default',
              padding: '3px 8px',
              borderRadius: 6,
              transition: 'background 0.2s',
              position: 'relative',
            }}
            onMouseEnter={(e) => { if (hasDetail) (e.currentTarget as HTMLElement).style.background = '#f5f5f5'; }}
            onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
          >
            {/* 序号/状态图标 */}
            <div style={{
              width: 22,
              height: 22,
              borderRadius: '50%',
              background: p.status === 'SUCCESS' ? '#52c41a' :
                         p.status === 'FAILED' ? '#ff4d4f' :
                         p.status === 'RUNNING' ? '#faad14' : '#d9d9d9',
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 11,
              flexShrink: 0,
              boxShadow: p.status === 'RUNNING' ? '0 0 0 3px rgba(250,173,20,0.2)' : 'none',
              transition: 'all 0.3s',
            }}>
              {p.status === 'RUNNING' ? <LoadingOutlined spin style={{ fontSize: 9 }} /> :
               p.status === 'SUCCESS' ? <CheckOutlined style={{ fontSize: 9 }} /> :
               p.status === 'FAILED' ? <CloseOutlined style={{ fontSize: 9 }} /> :
               <span style={{ fontSize: 9 }}>{index + 1}</span>}
            </div>

            {/* 内容 */}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <BulbOutlined style={{ color: '#fa8c16', fontSize: 11 }} />
                <span style={{ fontSize: 12, color: '#333', fontWeight: 500 }}>
                  {p.title}
                </span>
                {p.status === 'RUNNING' && (
                  <Tag color="processing" style={{ margin: 0, fontSize: 9, padding: '0 4px', height: 16 }}>
                    <LoadingOutlined spin style={{ marginRight: 2 }} />
                    思考中
                  </Tag>
                )}
                {p.status === 'SUCCESS' && p.durationMs != null && (
                  <span style={{ fontSize: 10, color: '#999' }}>{formatDuration(p.durationMs)}</span>
                )}
                {hasDetail && (
                  isExpanded
                    ? <DownOutlined style={{ fontSize: 8, color: '#999', marginLeft: 'auto' }} />
                    : <RightOutlined style={{ fontSize: 8, color: '#999', marginLeft: 'auto' }} />
                )}
              </div>

              {/* 展开详情 */}
              {hasDetail && isExpanded && (
                <div style={{
                  marginTop: 4,
                  padding: '6px 10px',
                  background: '#fffbe6',
                  borderRadius: 6,
                  fontSize: 11,
                  color: '#614700',
                  lineHeight: 1.5,
                  whiteSpace: 'pre-wrap',
                  maxHeight: 150,
                  overflowY: 'auto',
                  transition: 'all 0.3s ease',
                  border: '1px solid #ffe58f',
                }}>
                  {p.detail}
                </div>
              )}
            </div>
          </div>
        </div>
      );
    }

    // ==== 工具调用事件 ====
    if (event.type === 'tool_call' || event.type === 'tool_result') {
      const p = event.payload as ToolCallPayload;
      const visual = STATUS_VISUAL[p.status];
      // tool_result 事件本身可能没有 arguments/result，从同 ID 的 tool_call 继承
      // 这里直接使用 payload 中的数据即可

      return (
        <div key={event.id} style={{ marginBottom: 2 }}>
          <div
            onClick={() => toggleExpand(event.id)}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 8,
              cursor: 'pointer',
              padding: '4px 10px',
              borderRadius: 8,
              background: p.status === 'FAILED' ? '#fff1f0' :
                         p.status === 'SUCCESS' ? '#f6ffed' :
                         p.status === 'RUNNING' ? '#fffbe6' : '#f5f5f5',
              border: `1px solid ${
                p.status === 'FAILED' ? '#ffa39e' :
                p.status === 'SUCCESS' ? '#b7eb8f' :
                p.status === 'RUNNING' ? '#ffe58f' : '#e8e8e8'
              }`,
              position: 'relative',
              transition: 'all 0.3s',
              boxShadow: p.status === 'RUNNING' ? '0 2px 8px rgba(250,173,20,0.12)' : 'none',
            }}
          >
            {/* 状态图标 */}
            <div style={{
              width: 22,
              height: 22,
              borderRadius: '50%',
              background: p.status === 'SUCCESS' ? '#52c41a' :
                         p.status === 'FAILED' ? '#ff4d4f' :
                         p.status === 'RUNNING' ? '#faad14' : '#d9d9d9',
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
              fontSize: 10,
              marginTop: 1,
            }}>
              {visual.icon}
            </div>

            {/* 工具图标 + 名称 */}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {getToolIcon(p.toolName)}
                <span style={{
                  fontSize: 12,
                  color: '#333',
                  fontWeight: 600,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  maxWidth: 180,
                }}>
                  {p.toolName}
                </span>
                <Tag
                  color={visual.color}
                  style={{ margin: 0, fontSize: 9, padding: '0 5px', height: 16 }}
                >
                  {visual.label}
                </Tag>
                {p.durationMs != null && p.status !== 'RUNNING' && (
                  <Tooltip title={`耗时 ${formatDuration(p.durationMs)}`}>
                    <span style={{ fontSize: 10, color: '#999' }}>
                      {formatDuration(p.durationMs)}
                    </span>
                  </Tooltip>
                )}
                <span style={{ marginLeft: 'auto' }}>
                  {isExpanded
                    ? <DownOutlined style={{ fontSize: 8, color: '#999' }} />
                    : <RightOutlined style={{ fontSize: 8, color: '#999' }} />}
                </span>
              </div>

              {/* 展开详情 */}
              {isExpanded && (
                <div style={{
                  marginTop: 6,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 5,
                  transition: 'all 0.3s ease',
                }}>
                  {/* 调用参数 */}
                  {p.arguments && Object.keys(p.arguments).length > 0 && (() => {
                    const argsStr = JSON.stringify(p.arguments, null, 2);
                    return (
                      <div>
                        <div style={{ fontSize: 10, color: '#8c8c8c', marginBottom: 2 }}>📋 调用参数</div>
                        <pre style={{
                          background: '#f5f5f5',
                          padding: '4px 8px',
                          borderRadius: 4,
                          margin: 0,
                          fontSize: 10,
                          maxHeight: 60,
                          overflow: 'auto',
                          color: '#333',
                        }}>
                          {argsStr.length > 500 ? argsStr.substring(0, 500) + '\n...' : argsStr}
                        </pre>
                      </div>
                    );
                  })()}

                  {/* 执行结果 */}
                  {p.result !== undefined && p.result !== null && (
                    <div>
                      <div style={{
                        fontSize: 10,
                        color: p.status === 'FAILED' ? '#ff4d4f' : '#52c41a',
                        marginBottom: 2,
                        fontWeight: 500,
                      }}>
                        📤 执行结果 {p.status === 'SUCCESS' ? '✓' : p.status === 'FAILED' ? '✗' : ''}
                      </div>
                      <pre style={{
                        background: p.status === 'FAILED' ? '#fff1f0' : '#f6ffed',
                        padding: '4px 8px',
                        borderRadius: 4,
                        margin: 0,
                        fontSize: 10,
                        maxHeight: 80,
                        overflow: 'auto',
                        color: p.status === 'FAILED' ? '#cf1322' : '#333',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-all',
                      }}>
                        {getResultSummary(p.result)}
                      </pre>
                    </div>
                  )}

                  {/* 错误信息 */}
                  {p.status === 'FAILED' && p.error && (
                    <div style={{
                      padding: '4px 8px',
                      background: '#fff2f0',
                      border: '1px solid #ffccc7',
                      borderRadius: 4,
                      fontSize: 10,
                      color: '#cf1322',
                    }}>
                      <ExclamationCircleOutlined /> {p.error}
                    </div>
                  )}
                </div>
              )}

              {/* 折叠时的结果摘要 */}
              {!isExpanded && p.result !== undefined && p.result !== null && (
                <div style={{
                  fontSize: 10,
                  color: p.status === 'FAILED' ? '#cf1322' : '#666',
                  marginTop: 2,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  maxWidth: 400,
                  opacity: 0.7,
                }}>
                  {p.status === 'FAILED' ? '❌ ' : '📤 '}{getResultSummary(p.result)}
                </div>
              )}
            </div>
          </div>
        </div>
      );
    }

    // ==== 错误事件 ====
    if (event.type === 'error') {
      const p = event.payload as ErrorPayload;
      return (
        <div key={event.id} style={{
          padding: '6px 10px',
          background: '#fff2f0',
          border: '1px solid #ffccc7',
          borderRadius: 8,
          fontSize: 12,
          color: '#cf1322',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 2,
        }}>
          <ExclamationCircleOutlined />
          <span>❌ {p.message}</span>
          {p.recoverable && <Tag color="warning" style={{ fontSize: 9 }}>可恢复</Tag>}
        </div>
      );
    }

    // answer_chunk / answer_done 事件不在时间线中展示（这些在消息内容中展示）
    return null;
  };

  // 过滤掉 answer_chunk 事件（在消息气泡中展示）
  const displayEvents = useMemo(
    () => events.filter(e => e.type !== 'answer_chunk' && e.type !== 'answer_done'),
    [events]
  );

  // 计算统计摘要
  const summaryText = useMemo(() => {
    const parts: string[] = [];
    if (stats) {
      parts.push(`${stats.totalEvents} 步骤`);
      if (stats.thinkingCount > 0) parts.push(`🧠 ${stats.thinkingCount}`);
      if (stats.toolCallCount > 0) parts.push(`🔧 ${stats.toolCallCount}`);
      if (stats.failureCount > 0) parts.push(`❌ ${stats.failureCount}`);
      if (stats.totalDurationMs > 0) parts.push(`⏱ ${formatDuration(stats.totalDurationMs)}`);
    } else {
      parts.push(`${displayEvents.length} 步骤`);
    }
    return parts.join(' · ');
  }, [stats, displayEvents.length]);

  return (
    <div style={{ marginBottom: 10, ...style }}>
      {/* 标题栏 */}
      <div
        onClick={() => setIsCollapsed(!isCollapsed)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '6px 12px',
          cursor: 'pointer',
          background: 'linear-gradient(135deg, #f0f4ff 0%, #e6edff 100%)',
          border: '1px solid #d6e0ff',
          borderRadius: '10px 10px 0 0',
          userSelect: 'none',
          fontSize: 12,
          fontWeight: 500,
          color: '#2f54eb',
        }}
      >
        <span style={{ fontSize: 14 }}>⏱️</span>
        <span>执行轨迹</span>

        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>
          {stats?.isComplete ? (
            <Tag color="success" style={{ margin: 0, fontSize: 9 }}>
              ✅ 完成
            </Tag>
          ) : displayEvents.some(e => {
            const p = e.payload as { status?: string };
            return p.status === 'RUNNING';
          }) ? (
            <Tag color="processing" style={{ margin: 0, fontSize: 9 }}>
              <LoadingOutlined spin style={{ marginRight: 3 }} />
              执行中
            </Tag>
          ) : null}
          <span style={{ fontSize: 10, color: '#597ef7' }}>{summaryText}</span>
          {isCollapsed
            ? <RightOutlined style={{ fontSize: 9 }} />
            : <DownOutlined style={{ fontSize: 9 }} />}
        </div>
      </div>

      {/* 事件列表 */}
      {!isCollapsed && (
        <div style={{
          padding: '6px 10px 10px',
          border: '1px solid #d6e0ff',
          borderTop: 'none',
          borderRadius: '0 0 10px 10px',
          background: '#fafcff',
          position: 'relative',
        }}>
          {displayEvents.map((event, idx) => (
            <React.Fragment key={event.id}>
              {/* 连接线 */}
              {idx > 0 && (
                <div style={{
                  position: 'relative',
                  height: 2,
                  marginLeft: 19,
                  marginBottom: 2,
                }}>
                  <div style={{
                    position: 'absolute',
                    left: 0,
                    top: 0,
                    width: 1,
                    height: '100%',
                    background: '#e0e0e0',
                  }} />
                </div>
              )}
              {renderEvent(event, idx)}
            </React.Fragment>
          ))}
        </div>
      )}

      {/* 折叠状态下的概要 */}
      {isCollapsed && (
        <div
          onClick={() => setIsCollapsed(false)}
          style={{
            padding: '6px 12px',
            border: '1px solid #d6e0ff',
            borderTop: 'none',
            borderRadius: '0 0 10px 10px',
            background: '#fafcff',
            fontSize: 11,
            color: '#597ef7',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          {stats?.isComplete
            ? <>✅ 执行完成 · {summaryText}</>
            : <>⏳ {summaryText} · 点击展开详情</>}
        </div>
      )}
    </div>
  );
};

export default ExecutionTimeline;
