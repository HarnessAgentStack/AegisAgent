/**
 * @file 助手轮次容器
 * @description 单轮 assistant 回复的统一容器：TurnHeader(元信息+整轮折叠) +
 *              TurnStream(时序执行流) + Footer(知识库引用/HITL/错误恢复) + 操作栏(复制/重新生成)。
 *              取代旧 ChatArea 中"气泡 + 上方独立 ExecutionTimeline"的双块形态，
 *              把执行流内化为轮次主体，回答片段作为流中元素。
 *
 * @author Aegis
 * @since 4.0.0
 */
import React, { useMemo, useState } from 'react';
import { Button, Modal, App, Tag } from 'antd';
import {
  CopyOutlined,
  CloseOutlined,
  CheckOutlined,
  ReloadOutlined,
  ExclamationCircleOutlined,
  BarChartOutlined,
} from '@ant-design/icons';
import type { Message } from '@/types/session';
import { HitlStatus } from '@/types/enum';
import type { CollapsePolicy } from '@/types/collapsePolicy';
import { isErrorEvent, type TurnEvent } from '@/types/turn';
import TurnHeader from './TurnHeader';
import TurnStream from './TurnStream';
import AnswerChunk from './AnswerChunk';
import TurnGantt from './TurnGantt';
import { formatPercent, formatDuration } from '@/utils/format';

interface AssistantTurnProps {
  message: Message;
  agentName?: string;
  streaming: boolean;
  policy: CollapsePolicy;
  markdownStyles: string;
  /** 是否为最后一条 assistant（用于"重新生成"可用性） */
  canRegenerate?: boolean;
  onCopy?: (text: string) => void;
  onApproveHitl?: () => void;
  onRejectHitl?: () => void;
  onRegenerate?: (messageId: string) => void;
  /** CONFLICT 中断并重试 */
  onResumeFromConflict?: (sessionId: string) => Promise<void>;
}

/** 旧消息降级：从 reasoning/toolCalls 构造 events */
function degradeToEvents(msg: Message): TurnEvent[] {
  const events: TurnEvent[] = [];
  let ts = msg.createdAt ? new Date(msg.createdAt).getTime() : Date.now();
  if (msg.reasoning) {
    events.push({
      id: `degrade-thinking-${msg.id}`,
      kind: 'thinking',
      timestamp: ts,
      payload: { title: '思考过程', detail: msg.reasoning, status: 'SUCCESS', durationMs: undefined },
    });
  }
  if (msg.toolCalls && msg.toolCalls.length > 0) {
    msg.toolCalls.forEach((tc) => {
      ts += 1;
      events.push({
        id: `degrade-tool-${tc.id || ts}`,
        kind: 'tool',
        timestamp: ts,
        payload: {
          toolId: tc.id,
          toolName: tc.name,
          arguments: tc.arguments,
          result: tc.result,
          error: tc.error,
          status: tc.status === 'running' ? 'RUNNING' : tc.status === 'failed' ? 'FAILED' : 'SUCCESS',
          durationMs: tc.durationMs,
        },
      });
    });
  }
  if (msg.content) {
    ts += 1;
    events.push({
      id: `degrade-answer-${msg.id}`,
      kind: 'answer',
      timestamp: ts,
      payload: { text: msg.content },
    });
  }
  return events;
}

export const AssistantTurn: React.FC<AssistantTurnProps> = ({
  message,
  agentName,
  streaming,
  policy,
  markdownStyles,
  canRegenerate,
  onCopy,
  onApproveHitl,
  onRejectHitl,
  onRegenerate,
  onResumeFromConflict,
}) => {
  const { message: msgApi } = App.useApp();
  const meta = message.turnMeta;
  const isComplete = meta?.isComplete === true;
  const hasStream = (message.events?.length ?? 0) > 0 || !!(message.reasoning || message.toolCalls?.length);
  // 初始折叠态：历史完成轮默认折叠（信息密度优化）；实时进行中默认展开
  const [collapsed, setCollapsed] = useState<boolean>(isComplete && hasStream);

  const toggle = () => {
    setCollapsed((v) => !v);
  };

  // 事件来源：新模型用 message.events；旧数据降级构造
  const events: TurnEvent[] = useMemo(
    () => (message.events && message.events.length > 0 ? message.events : degradeToEvents(message)),
    [message],
  );

  // 错误恢复
  const isErrorMsg = message.isError;
  const recoverable = message.recoverable && message.errorCode === 'CONFLICT';

  // HITL
  const hitl = message.hitl && message.hitl.status === HitlStatus.PENDING ? message.hitl : null;

  // KB 引用展开态
  const [kbExpanded, setKbExpanded] = useState<Set<string>>(new Set());
  // 甘特概览弹层
  const [ganttOpen, setGanttOpen] = useState(false);

  // 折叠态概要
  const summaryText = React.useMemo(() => {
    const toolCount = events.filter((e) => e.kind === 'tool').length;
    const thinkCount = events.filter((e) => e.kind === 'thinking').length;
    const failCount = events.filter((e) => e.kind === 'tool' && e.payload.status === 'FAILED').length;
    const parts: string[] = [];
    if (thinkCount > 0) parts.push(`🧠 ${thinkCount}`);
    if (toolCount > 0) parts.push(`🔧 ${toolCount}`);
    if (failCount > 0) parts.push(`❌ ${failCount}`);
    if (meta?.durationMs != null) parts.push(`⏱ ${formatDuration(meta.durationMs)}`);
    return parts.join(' · ');
  }, [events, meta]);

  return (
    <div style={{ display: 'flex', gap: 10, marginBottom: 20, flexDirection: 'row' }}>
      <div style={{ maxWidth: '78%', minWidth: 0, flex: 1 }}>
        <TurnHeader
          agentName={agentName}
          meta={meta}
          collapsed={collapsed}
          streaming={streaming && !isComplete}
          onToggle={toggle}
        />

        {/* 执行流（折叠时隐藏） */}
        {!collapsed && hasStream && (
          <div style={{ marginTop: 4 }}>
            <TurnStream events={events} policy={policy} streaming={streaming && !isComplete} markdownStyles={markdownStyles} />
          </div>
        )}

        {/* 折叠态概要 */}
        {collapsed && summaryText && (
          <div
            onClick={toggle}
            style={{
              padding: '4px 10px',
              marginTop: 4,
              fontSize: 11,
              color: '#597ef7',
              cursor: 'pointer',
              background: '#fafcff',
              border: '1px solid #d6e0ff',
              borderRadius: 8,
              display: 'inline-flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            ✅ 执行完成 · {summaryText} · 点击展开
          </div>
        )}

        {/* 无执行流的回答气泡（纯文本/旧回答） */}
        {!hasStream && message.content && (
          <div
            className="markdown-body"
            style={{
              marginTop: 6,
              fontSize: 14,
              background: isErrorMsg ? 'var(--color-bg-chat-error)' : 'var(--color-bg-chat-assistant)',
              border: isErrorMsg ? '1px solid var(--color-error)' : 'none',
              padding: '10px 16px',
              borderRadius: 12,
              color: isErrorMsg ? 'var(--color-error)' : 'var(--color-text-on-assistant)',
            }}
          >
            <style>{markdownStyles}</style>
            <AnswerFallback text={message.content} markdownStyles={markdownStyles} />
          </div>
        )}

        {/* 无内容流式占位 */}
        {!hasStream && !message.content && streaming && !isComplete && (
          <div style={{ marginTop: 6, padding: '10px 16px', color: '#9ca3af', fontSize: 14 }}>思考中...</div>
        )}

        {/* 错误恢复 */}
        {isErrorMsg && recoverable && message.errorSessionId && (
          <div style={{ marginTop: 6 }}>
            <Button
              size="small"
              type="primary"
              icon={<CloseOutlined />}
              onClick={async () => {
                if (message.errorSessionId) {
                  try {
                    await onResumeFromConflict?.(message.errorSessionId);
                    msgApi.success('已中断，请重新发送消息');
                  } catch (e) {
                    msgApi.error('中断失败: ' + (e as Error).message);
                  }
                }
              }}
            >
              中断并重试
            </Button>
          </div>
        )}

        {/* 知识库引用 */}
        {message.kbReferences && message.kbReferences.length > 0 && (
          <div style={{ marginTop: 8, padding: '8px 12px', background: 'var(--color-bg-tag-blue)', border: '1px solid var(--color-primary)', borderRadius: 8, fontSize: 12 }}>
            <div style={{ fontWeight: 600, color: 'var(--color-primary)', marginBottom: 4, display: 'flex', alignItems: 'center', gap: 4 }}>
              <span>📚 知识库引用 ({message.kbReferences.length} 个片段)</span>
              {message.kbReferences.length > 3 && (
                <Button type="link" size="small" style={{ fontSize: 11, padding: 0, height: 'auto' }} onClick={() => setKbExpanded(new Set())}>
                  收起
                </Button>
              )}
            </div>
            {message.kbReferences.slice(0, 3).map((ref, i) => {
              const refKey = `${message.id}-${i}`;
              const expanded = kbExpanded.has(refKey);
              return (
                <div key={i} style={{ marginBottom: 6, paddingBottom: 6, borderBottom: i < Math.min(3, message.kbReferences!.length) - 1 ? '1px dashed #d6e4ff' : 'none' }}>
                  <div style={{ display: 'flex', alignItems: 'center', flexWrap: 'wrap', gap: 4 }}>
                    {ref.knowledgeBaseName && <Tag style={{ fontSize: 10, margin: 0 }} color="blue">{ref.knowledgeBaseName}</Tag>}
                    <span style={{ color: '#374151', fontWeight: 500 }}>{ref.documentName ?? `文档${i + 1}`}</span>
                    {ref.score !== undefined && <span style={{ color: '#9ca3af' }}>(相似度: {formatPercent(ref.score, 1)})</span>}
                  </div>
                  {ref.snippet && (
                    <div
                      style={{ color: '#6b7280', marginTop: 4, whiteSpace: 'pre-wrap', lineHeight: 1.6, maxHeight: expanded ? 'none' : 40, overflow: 'hidden', textOverflow: 'ellipsis', background: expanded ? '#fff' : 'transparent', padding: expanded ? '6px 8px' : 0, borderRadius: expanded ? 4 : 0, border: expanded ? '1px solid #e6f0ff' : 'none' }}
                    >
                      {expanded ? ref.snippet : `${ref.snippet.slice(0, 100)}${ref.snippet.length > 100 ? '...' : ''}`}
                    </div>
                  )}
                  {ref.snippet && ref.snippet.length > 100 && (
                    <Button type="link" size="small" style={{ fontSize: 11, padding: 0, height: 'auto' }} onClick={() => setKbExpanded((prev) => { const next = new Set(prev); if (next.has(refKey)) { next.delete(refKey); } else { next.add(refKey); } return next; })}>
                      {expanded ? '收起原文' : '查看原文'}
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* HITL 审批 */}
        {hitl && (
          <div style={{ marginTop: 8, padding: '8px 12px', background: hitl.autoApproved ? '#f6ffed' : '#fffbe6', border: `1px solid ${hitl.autoApproved ? '#b7eb8f' : '#faad14'}`, borderRadius: 8, fontSize: 12 }}>
            <div style={{ fontWeight: 600, color: hitl.autoApproved ? '#389e0d' : '#d48806', marginBottom: 4 }}>
              {hitl.autoApproved ? '✅ 低风险工具·待确认' : '⚠️ 工具调用审批'}
            </div>
            <div style={{ color: '#6b7280', marginBottom: 8 }}>{hitl.summary}</div>
            <div style={{ display: 'flex', gap: 8 }}>
              <Button size="small" type="primary" icon={<CheckOutlined />} onClick={onApproveHitl}>同意</Button>
              <Button size="small" danger icon={<CloseOutlined />} onClick={onRejectHitl}>拒绝</Button>
            </div>
          </div>
        )}

        {/* 错误内联（error 事件已在流中显示，此处兜底 isError 但无 events） */}
        {isErrorMsg && !events.some(isErrorEvent) && message.content && (
          <div style={{ marginTop: 6, padding: '6px 10px', background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, fontSize: 12, color: '#cf1322' }}>
            <ExclamationCircleOutlined /> {message.content}
          </div>
        )}

        {/* 操作栏 */}
        <div style={{ marginTop: 4, display: 'flex', gap: 4, opacity: 0.6, transition: 'opacity 0.15s' }} onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.opacity = '1'; }} onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.opacity = '0.6'; }}>
          {hasStream && (
            <Button size="small" type="text" icon={<BarChartOutlined />} title="执行时序概览" onClick={() => setGanttOpen(true)} />
          )}
          {message.content && (
            <Button
              size="small"
              type="text"
              icon={<CopyOutlined />}
              title="复制原文"
              onClick={async () => {
                const text = message.content ?? '';
                try {
                  if (navigator.clipboard?.writeText) {
                    await navigator.clipboard.writeText(text);
                  } else {
                    const ta = document.createElement('textarea');
                    ta.value = text;
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                  }
                  onCopy?.(text);
                  msgApi.success('已复制原文');
                } catch (e) {
                  msgApi.error('复制失败: ' + (e as Error).message);
                }
              }}
            />
          )}
          {message.content && !isErrorMsg && canRegenerate && onRegenerate && !streaming && (
            <Button
              size="small"
              type="text"
              icon={<ReloadOutlined />}
              title="重新生成"
              onClick={() => {
                Modal.confirm({
                  title: '重新生成？',
                  icon: <ExclamationCircleOutlined />,
                  content: '将删除此回答并基于上一条提问重新生成。',
                  okText: '确认重新生成',
                  cancelText: '取消',
                  onOk: () => onRegenerate(message.id),
                });
              }}
            />
          )}
        </div>

        {/* 执行时序甘特概览（P2-3） */}
        <TurnGantt open={ganttOpen} events={events} onClose={() => setGanttOpen(false)} />
      </div>
    </div>
  );
};

/** 纯回答回退渲染（无 events 时 markdown 气泡） */
const AnswerFallback: React.FC<{ text: string; markdownStyles: string }> = ({ text, markdownStyles }) => {
  const event = useMemo(
    () => ({ id: 'fallback', kind: 'answer' as const, timestamp: 0, payload: { text } }),
    [text],
  );
  return <AnswerChunk event={event} markdownStyles={markdownStyles} />;
};

export default AssistantTurn;
