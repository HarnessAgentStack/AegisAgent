/**
 * @file 轮次执行流
 * @description 渲染 TurnEvent[]：按 timestamp 严格升序交错显示思考/工具/回答片段。
 *              同 batchId 的连续 tool 事件聚合为 ToolGroup（P2-1 并行分组）。
 *              取代旧 ExecutionTimeline"单一整体折叠块"，实现逐事件折叠 + 时序交错。
 *
 * @author Aegis
 * @since 4.0.0
 */
import React, { useMemo } from 'react';
import type { TurnEvent, ToolEvent } from '@/types/turn';
import { isThinkingEvent, isToolEvent, isAnswerEvent, isErrorEvent } from '@/types/turn';
import type { CollapsePolicy } from '@/types/collapsePolicy';
import ThinkingItem from './ThinkingItem';
import ToolItem from './ToolItem';
import AnswerChunk from './AnswerChunk';
import { ExclamationCircleOutlined } from '@ant-design/icons';
import { Tag } from 'antd';

interface TurnStreamProps {
  events: TurnEvent[];
  policy: CollapsePolicy;
  /** 流式中（answer 占位"思考中..."） */
  streaming?: boolean;
  /** markdown 样式表（透传给 AnswerChunk） */
  markdownStyles?: string;
}

/** 渲染单元：单事件或工具组 */
type RenderUnit =
  | { kind: 'single'; event: TurnEvent; index: number }
  | { kind: 'toolgroup'; events: ToolEvent[]; startIndex: number };

/**
 * 把事件流分区：连续且同 batchId 的 tool 事件合为一个 toolgroup；其余为 single。
 * batchId 缺失的工具各自成 single（退化为 ToolItem）。
 */
function partitionEvents(events: TurnEvent[]): RenderUnit[] {
  const units: RenderUnit[] = [];
  let i = 0;
  let globalIdx = 0;
  while (i < events.length) {
    const e = events[i];
    if (isToolEvent(e) && e.batchId) {
      // 收集同 batchId 连续工具
      const batch = e.batchId;
      const group: ToolEvent[] = [e];
      let j = i + 1;
      while (j < events.length) {
        const ej = events[j];
        if (isToolEvent(ej) && ej.batchId === batch) {
          group.push(ej);
          j++;
        } else {
          break;
        }
      }
      if (group.length > 1) {
        units.push({ kind: 'toolgroup', events: group, startIndex: globalIdx });
        globalIdx += group.length;
      } else {
        units.push({ kind: 'single', event: e, index: globalIdx });
        globalIdx++;
      }
      i = j;
    } else {
      units.push({ kind: 'single', event: e, index: globalIdx });
      globalIdx++;
      i++;
    }
  }
  return units;
}

export const TurnStream: React.FC<TurnStreamProps> = ({ events, policy, streaming, markdownStyles }) => {
  const units = useMemo(() => partitionEvents(events), [events]);

  if (units.length === 0) return null;

  return (
    <div style={{ position: 'relative', paddingTop: 4 }}>
      {units.map((unit, idx) => (
        <React.Fragment key={`unit-${idx}`}>
          {/* 连接线（除第一个单元外） */}
          {idx > 0 && (
            <div style={{ position: 'relative', height: 2, marginLeft: policy.compact ? 9 : 33, marginBottom: 2 }}>
              <div style={{ position: 'absolute', left: 0, top: 0, width: 1, height: '100%', background: '#e0e0e0' }} />
            </div>
          )}

          {unit.kind === 'single' ? (
            renderSingle(unit.event, unit.index, policy, streaming, markdownStyles)
          ) : (
            <ToolGroup events={unit.events} startIndex={unit.startIndex} policy={policy} />
          )}
        </React.Fragment>
      ))}
    </div>
  );
};

/** 渲染单个事件 */
function renderSingle(
  event: TurnEvent,
  index: number,
  policy: CollapsePolicy,
  streaming?: boolean,
  markdownStyles?: string,
): React.ReactNode {
  if (isThinkingEvent(event)) {
    return <ThinkingItem event={event} index={index} thinkingStyle={policy.thinkingStyle} compact={policy.compact} />;
  }
  if (isToolEvent(event)) {
    return <ToolItem event={event} index={index} policy={policy} />;
  }
  if (isAnswerEvent(event)) {
    const isError = false;
    return <AnswerChunk event={event} streaming={streaming} isError={isError} markdownStyles={markdownStyles} />;
  }
  if (isErrorEvent(event)) {
    return (
      <div
        style={{
          padding: '6px 10px',
          marginLeft: policy.compact ? 0 : 24,
          background: '#fff2f0',
          border: '1px solid #ffccc7',
          borderRadius: 8,
          fontSize: 12,
          color: '#cf1322',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 2,
        }}
      >
        <ExclamationCircleOutlined />
        <span>❌ {event.payload.message}</span>
        {event.payload.recoverable && <Tag color="warning" style={{ fontSize: 9 }}>可恢复</Tag>}
      </div>
    );
  }
  return null;
}

/** 工具并行组（P2-1）：dashed 容器聚合多 ToolItem */
const ToolGroup: React.FC<{ events: ToolEvent[]; startIndex: number; policy: CollapsePolicy }> = ({
  events,
  startIndex,
  policy,
}) => {
  return (
    <div
      style={{
        marginLeft: policy.compact ? 0 : 24,
        border: '1px dashed #d6e0ff',
        borderRadius: 10,
        padding: '4px 6px',
        background: 'rgba(240,244,255,0.35)',
      }}
    >
      <div style={{ fontSize: 10, color: '#597ef7', marginBottom: 4, fontWeight: 600 }}>
        🔧 并行调用 {events.length} 个工具
      </div>
      {events.map((e, i) => (
        <ToolItem key={e.id} event={e} index={startIndex + i} policy={policy} />
      ))}
    </div>
  );
};

export default TurnStream;
