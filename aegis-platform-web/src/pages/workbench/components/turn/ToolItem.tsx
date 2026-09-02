/**
 * @file 工具调用事件项
 * @description 渲染 TurnEvent(tool)：类型色条卡片 + 状态徽章 + 耗时，
 *              支持折叠档位(none/all/readOnly) + 完成动作摘要 + 失败态错误行。
 *              并行批次(P2-1)由 ToolGroup 外层聚合，单 Item 只关心自身。
 *
 * @author Aegis
 * @since 4.0.0
 */
import React, { useEffect, useRef, useState } from 'react';
import { Tag, Tooltip } from 'antd';
import {
  LoadingOutlined,
  CheckOutlined,
  CloseOutlined,
  DownOutlined,
  RightOutlined,
  ExclamationCircleOutlined,
} from '@ant-design/icons';
import type { ToolEvent, TurnEventStatus } from '@/types/turn';
import type { CollapsePolicy } from '@/types/collapsePolicy';
import { formatDuration } from '@/utils/format';
import { getResultSummary } from '@/hooks/useTurnStream';

interface ToolItemProps {
  event: ToolEvent;
  index: number;
  policy: CollapsePolicy;
}

/** 状态视觉 */
const STATUS_VISUAL: Record<TurnEventStatus, { color: string; icon: React.ReactNode; label: string }> = {
  PENDING: { color: 'default', icon: <RightOutlined />, label: '等待' },
  RUNNING: { color: 'processing', icon: <LoadingOutlined spin />, label: '执行中' },
  SUCCESS: { color: 'success', icon: <CheckOutlined />, label: '完成' },
  FAILED: { color: 'error', icon: <CloseOutlined />, label: '失败' },
};

/** 工具类型图标 + 色（迁自旧 ExecutionTimeline.getToolIcon，统一色系） */
const getToolVisual = (name: string): { icon: React.ReactNode; color: string; bar: string } => {
  const lower = name.toLowerCase();
  if (lower.includes('kb') || lower.includes('knowledge') || lower.includes('知识库')) {
    return { icon: <span>📚</span>, color: '#1890ff', bar: '#1890ff' };
  }
  if (lower.includes('web') || lower.includes('search') || lower.includes('搜索')) {
    return { icon: <span>🔍</span>, color: '#13c2c2', bar: '#13c2c2' };
  }
  if (lower.includes('http') || lower.includes('request')) {
    return { icon: <span>🌐</span>, color: '#722ed1', bar: '#722ed1' };
  }
  if (lower.includes('image') || lower.includes('file') || lower.includes('文件')) {
    return { icon: <span>📄</span>, color: '#eb2f96', bar: '#eb2f96' };
  }
  if (lower.includes('skill') || lower.includes('技能')) {
    return { icon: <span>⚡</span>, color: '#faad14', bar: '#faad14' };
  }
  return { icon: <span>🔧</span>, color: '#fa8c16', bar: '#fa8c16' };
};

export const ToolItem: React.FC<ToolItemProps> = ({ event, index, policy }) => {
  void index; // 保留序号入参以备未来展示
  const p = event.payload;
  const visual = STATUS_VISUAL[p.status];
  const toolIcon = getToolVisual(p.toolName);
  // 运行中默认展开看进度；完成态默认折叠（仅摘要），none/readOnly 档保持展开
  const [expanded, setExpanded] = useState<boolean>(
    p.status === 'RUNNING' || policy.collapsedTools === 'none' || policy.collapsedTools === 'readOnly',
  );
  const userTouchedRef = useRef(false);

  // 完成态 1s 后自动收缩为摘要（policy=all 且用户未手动操作时）；运行态由初始 useState 保证展开
  useEffect(() => {
    if ((p.status === 'SUCCESS' || p.status === 'FAILED') && !userTouchedRef.current) {
      const target = policy.collapsedTools === 'none' || policy.collapsedTools === 'readOnly';
      if (!target) {
        const timer = setTimeout(() => setExpanded(false), 1000);
        return () => clearTimeout(timer);
      }
    }
  }, [p.status, p.durationMs, policy.collapsedTools]);

  const toggle = () => {
    userTouchedRef.current = true;
    setExpanded((v) => !v);
  };

  const readOnly = policy.collapsedTools === 'readOnly';
  const indent = policy.compact ? 0 : 24;

  return (
    <div style={{ marginBottom: 2, position: 'relative' }}>
      <div
        onClick={toggle}
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 8,
          cursor: 'pointer',
          padding: '4px 10px',
          marginLeft: indent,
          borderRadius: 8,
          background:
            p.status === 'FAILED' ? '#fff1f0' : p.status === 'SUCCESS' ? '#f6ffed' : p.status === 'RUNNING' ? '#fffbe6' : '#f5f5f5',
          border: `1px solid ${p.status === 'FAILED' ? '#ffa39e' : p.status === 'SUCCESS' ? '#b7eb8f' : p.status === 'RUNNING' ? '#ffe58f' : '#e8e8e8'}`,
          borderLeft: `3px solid ${toolIcon.bar}`,
          transition: 'all 0.3s',
          boxShadow: p.status === 'RUNNING' ? '0 2px 8px rgba(250,173,20,0.12)' : 'none',
        }}
      >
        {/* 状态圆标 */}
        <div
          style={{
            width: 18,
            height: 18,
            borderRadius: '50%',
            background: p.status === 'SUCCESS' ? '#52c41a' : p.status === 'FAILED' ? '#ff4d4f' : p.status === 'RUNNING' ? '#faad14' : '#d9d9d9',
            color: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
            fontSize: 10,
            marginTop: 1,
          }}
        >
          {visual.icon}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {!policy.compact && toolIcon.icon}
            <span
              style={{
                fontSize: 12,
                color: '#333',
                fontWeight: 600,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                maxWidth: 180,
              }}
            >
              {p.toolName}
            </span>
            <Tag color={visual.color} style={{ margin: 0, fontSize: 9, padding: '0 5px', height: 16, lineHeight: '16px' }}>
              {visual.label}
            </Tag>
            {p.durationMs != null && p.status !== 'RUNNING' && (
              <Tooltip title={`耗时 ${formatDuration(p.durationMs)}`}>
                <span style={{ fontSize: 10, color: '#999' }}>{formatDuration(p.durationMs)}</span>
              </Tooltip>
            )}
            <span style={{ marginLeft: 'auto' }}>
              {expanded ? <DownOutlined style={{ fontSize: 8, color: '#999' }} /> : <RightOutlined style={{ fontSize: 8, color: '#999' }} />}
            </span>
          </div>

          {/* 展开详情 */}
          {expanded && (
            <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 5, transition: 'all 0.3s ease' }}>
              {/* 调用参数 */}
              {p.arguments && Object.keys(p.arguments).length > 0 &&
                (() => {
                  const argsStr = JSON.stringify(p.arguments, null, 2);
                  return (
                    <div>
                      <div style={{ fontSize: 10, color: '#8c8c8c', marginBottom: 2 }}>📋 调用参数</div>
                      <pre
                        style={{
                          background: '#f5f5f5',
                          padding: '4px 8px',
                          borderRadius: 4,
                          margin: 0,
                          fontSize: 10,
                          maxHeight: 60,
                          overflow: 'auto',
                          color: '#333',
                        }}
                      >
                        {argsStr.length > 500 ? argsStr.substring(0, 500) + '\n...' : argsStr}
                      </pre>
                    </div>
                  );
                })()}

              {/* 执行结果 */}
              {p.result !== undefined && p.result !== null && (
                <div>
                  <div
                    style={{
                      fontSize: 10,
                      color: p.status === 'FAILED' ? '#ff4d4f' : '#52c41a',
                      marginBottom: 2,
                      fontWeight: 500,
                    }}
                  >
                    📤 执行结果 {p.status === 'SUCCESS' ? '✓' : p.status === 'FAILED' ? '✗' : ''}
                  </div>
                  <pre
                    style={{
                      background: p.status === 'FAILED' ? '#fff1f0' : '#f6ffed',
                      padding: '4px 8px',
                      borderRadius: 4,
                      margin: 0,
                      fontSize: 10,
                      maxHeight: readOnly ? 120 : 80,
                      overflow: 'auto',
                      color: p.status === 'FAILED' ? '#cf1322' : '#333',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all',
                    }}
                  >
                    {getResultSummary(p.result)}
                  </pre>
                </div>
              )}

              {/* 错误信息 */}
              {p.status === 'FAILED' && p.error && (
                <div style={{ padding: '4px 8px', background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 4, fontSize: 10, color: '#cf1322' }}>
                  <ExclamationCircleOutlined /> {p.error}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ToolItem;
