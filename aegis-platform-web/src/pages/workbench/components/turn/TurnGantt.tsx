/**
 * @file 轮次执行甘特概览
 * @description 基于 TurnEvent[] 自绘轻量时间条形图（不依赖 observe spans），
 *              展示思考/工具/回答的时序占用与耗时分布。点击 AssistantTurn 的"概览"按钮弹出。
 *              复杂场景可后续切换为 SpanWaterfall（需 observe 数据）。
 *
 * @author Aegis
 * @since 4.2.0
 */
import React, { useMemo } from 'react';
import { Modal, Tag } from 'antd';
import type { TurnEvent } from '@/types/turn';
import { isThinkingEvent, isToolEvent } from '@/types/turn';
import { formatDuration } from '@/utils/format';

interface TurnGanttProps {
  open: boolean;
  events: TurnEvent[];
  onClose: () => void;
}

/** 类型 → 视觉色 */
const KIND_VISUAL: Record<TurnEvent['kind'], { color: string; label: string }> = {
  thinking: { color: '#fa8c16', label: '思考' },
  tool: { color: '#722ed1', label: '工具' },
  answer: { color: '#10b981', label: '回答' },
  error: { color: '#ff4d4f', label: '错误' },
};

/** 取事件视觉（类型守卫规避控制流 never 窄化） */
function getVisual(e: TurnEvent): { color: string; label: string } {
  switch (e.kind) {
    case 'thinking':
      return KIND_VISUAL.thinking;
    case 'tool':
      return KIND_VISUAL.tool;
    case 'answer':
      return KIND_VISUAL.answer;
    case 'error':
      return KIND_VISUAL.error;
    default:
      return KIND_VISUAL.tool;
  }
}

/** 取事件标签（同上规避 never） */
function getLabel(e: TurnEvent): string {
  switch (e.kind) {
    case 'thinking':
      return e.payload.title;
    case 'tool':
      return e.payload.toolName;
    case 'answer':
      return '回答';
    case 'error':
      return '错误';
    default:
      return '事件';
  }
}

/** 推断事件耗时：thinking/tool 用 durationMs；answer 用到下一事件的时间差兜底 */
function inferDuration(events: TurnEvent[], idx: number): number {
  const e = events[idx];
  const p = e.payload as { durationMs?: number };
  if (p.durationMs != null) return p.durationMs;
  // 无 durationMs：用下一事件起始 - 当前起始（上限 2000ms 避免长尾）
  const next = events[idx + 1];
  if (next) {
    return Math.min(2000, next.timestamp - e.timestamp);
  }
  return 200;
}

export const TurnGantt: React.FC<TurnGanttProps> = ({ open, events, onClose }) => {
  const { minTs, maxTs, total } = useMemo(() => {
    if (events.length === 0) return { minTs: 0, maxTs: 1, total: 0 };
    const min = Math.min(...events.map((e) => e.timestamp));
    const max = Math.max(...events.map((e) => e.timestamp + inferDuration(events, events.indexOf(e))));
    return { minTs: min, maxTs: max > min ? max : min + 1, total: max - min };
  }, [events]);

  const timeToPct = (t: number) => ((t - minTs) / (maxTs - minTs)) * 100;

  return (
    <Modal title="执行时序概览" open={open} onCancel={onClose} footer={null} width={720}>
      {events.length === 0 ? (
        <div style={{ textAlign: 'center', padding: 24, color: '#999' }}>暂无执行事件</div>
      ) : (
        <>
          {/* 图例 */}
          <div style={{ display: 'flex', gap: 12, marginBottom: 12, fontSize: 12 }}>
            {Object.entries(KIND_VISUAL).map(([k, v]) => (
              <div key={k} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ display: 'inline-block', width: 12, height: 12, background: v.color, borderRadius: 2 }} />
                <span>{v.label}</span>
              </div>
            ))}
            <span style={{ marginLeft: 'auto', color: '#8c8c8c' }}>总耗时 {formatDuration(total)}</span>
          </div>

          {/* 时间轴 */}
          <div style={{ position: 'relative', height: 24, marginBottom: 8, borderBottom: '1px solid #f0f0f0' }}>
            {[0, 0.25, 0.5, 0.75, 1].map((r) => (
              <span key={r} style={{ position: 'absolute', left: `${r * 100}%`, transform: 'translateX(-50%)', fontSize: 10, color: '#8c8c8c' }}>
                {formatDuration(total * r)}
              </span>
            ))}
          </div>

          {/* 条形 */}
          <div style={{ position: 'relative' }}>
            {events.map((e, idx) => {
              const visual = getVisual(e);
              const dur = inferDuration(events, idx);
              const left = timeToPct(e.timestamp);
              const width = Math.max(0.5, (dur / (maxTs - minTs)) * 100);
              const label = getLabel(e);
              return (
                <div key={e.id} style={{ display: 'flex', alignItems: 'center', height: 28, marginBottom: 2 }}>
                  <div style={{ position: 'relative', flex: 1, height: 20 }}>
                    <div
                      style={{
                        position: 'absolute',
                        left: `${left}%`,
                        width: `${width}%`,
                        height: 20,
                        borderRadius: 4,
                        background: visual.color,
                        opacity: 0.85,
                        display: 'flex',
                        alignItems: 'center',
                        padding: '0 6px',
                        fontSize: 11,
                        color: '#fff',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                      }}
                    >
                      <span style={{ fontWeight: 500 }}>{label}</span>
                      <span style={{ marginLeft: 'auto', fontSize: 10, opacity: 0.9 }}>{formatDuration(dur)}</span>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
          <div style={{ marginTop: 12 }}>
            <Tag color="blue">{events.length} 个事件</Tag>
            <Tag color="orange">思考 {events.filter(isThinkingEvent).length}</Tag>
            <Tag color="purple">工具 {events.filter(isToolEvent).length}</Tag>
          </div>
        </>
      )}
    </Modal>
  );
};

export default TurnGantt;
