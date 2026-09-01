/**
 * @file Span 瀑布图组件
 * @description 纯前端甘特图（div 定位实现，无图表库依赖）
 *              纵轴：span 名称 + 类型图标 + 耗时；横轴：按 start_time 刻度
 *              父子缩进（parent_span_id 决定缩进层级）
 *              着色方案：LLM_CALL 蓝、TOOL_CALL 琥珀、RAG_RETRIEVE 绿、
 *                       HITL_WAIT 紫、SANDBOX_EXEC 青、失败红
 */
import React, { useMemo } from 'react';
import { ToolOutlined, CodeOutlined, SearchOutlined, UserOutlined, ExperimentOutlined, ThunderboltOutlined } from '@ant-design/icons';
import type { SpanRecord } from '@/api/observe';
import { formatDuration } from '@/utils/format';

interface SpanWaterfallProps {
  spans: SpanRecord[];
  selectedSpanId?: string;
  onSelect?: (spanId: string) => void;
}

const TYPE_COLORS: Record<string, { color: string; bg: string; icon: React.ReactNode; label: string }> = {
  AGENT_ASSEMBLY: { color: '#2f54eb', bg: '#f0f5ff', icon: <ThunderboltOutlined />, label: '装配' },
  LLM_CALL: { color: '#1677ff', bg: '#e6f4ff', icon: <CodeOutlined />, label: 'LLM' },
  TOOL_CALL: { color: '#d48806', bg: '#fff7e6', icon: <ToolOutlined />, label: 'TOOL' },
  RAG_RETRIEVE: { color: '#389e0d', bg: '#f6ffed', icon: <SearchOutlined />, label: 'RAG' },
  HITL_WAIT: { color: '#722ed1', bg: '#f9f0ff', icon: <UserOutlined />, label: 'HITL' },
  SANDBOX_EXEC: { color: '#08979c', bg: '#e6fffb', icon: <ExperimentOutlined />, label: 'SANDBOX' },
};

const DEFAULT_TYPE = { color: '#8c8c8c', bg: '#f5f5f5', icon: <ThunderboltOutlined />, label: 'SPAN' };

const FAIL_COLOR = '#ff4d4f';

function getTypeStyle(spanType: string, status: string) {
  const base = TYPE_COLORS[spanType] || DEFAULT_TYPE;
  if (status === 'FAILED') {
    return { color: FAIL_COLOR, bg: '#fff1f0', icon: base.icon, label: base.label };
  }
  return base;
}

interface SpanTreeNode extends SpanRecord {
  children: SpanTreeNode[];
  level: number;
}

function buildSpanTree(spans: SpanRecord[]) {
  const spanMap = new Map<string, SpanTreeNode>();
  const roots: SpanTreeNode[] = [];

  spans.forEach((s) => {
    spanMap.set(s.spanId, { ...s, children: [], level: 0 });
  });

  spanMap.forEach((node) => {
    if (node.parentSpanId && spanMap.has(node.parentSpanId)) {
      spanMap.get(node.parentSpanId)!.children.push(node);
    } else {
      roots.push(node);
    }
  });

  const setLevel = (nodes: SpanTreeNode[], level: number) => {
    nodes.forEach((n) => {
      n.level = level;
      setLevel(n.children, level + 1);
    });
  };
  setLevel(roots, 0);

  const flat: (SpanRecord & { level: number })[] = [];
  const flatten = (nodes: SpanTreeNode[]) => {
    nodes.forEach((n) => {
      const { children: _c, ...rest } = n;
      flat.push(rest);
      flatten(n.children);
    });
  };
  flatten(roots);

  return flat;
}

const SpanWaterfall: React.FC<SpanWaterfallProps> = ({ spans, selectedSpanId, onSelect }) => {
  const flatSpans = useMemo(() => buildSpanTree(spans), [spans]);

  const { minTime, maxTime } = useMemo(() => {
    if (flatSpans.length === 0) return { minTime: 0, maxTime: 1 };
    let min = Infinity;
    let max = -Infinity;
    flatSpans.forEach((s) => {
      const start = new Date(s.startTime).getTime();
      const end = s.endTime ? new Date(s.endTime).getTime() : start + (s.durationMs || 0);
      if (start < min) min = start;
      if (end > max) max = end;
    });
    if (!isFinite(min)) min = 0;
    if (!isFinite(max)) max = min + 1;
    if (min === max) max = min + 1;
    return { minTime: min, maxTime: max };
  }, [flatSpans]);

  const totalDuration = maxTime - minTime;

  if (flatSpans.length === 0) {
    return (
      <div style={{ textAlign: 'center', padding: 48, color: '#999' }}>
        暂无 Span 数据
      </div>
    );
  }

  const timeToPercent = (t: number) => ((t - minTime) / totalDuration) * 100;

  const gridCount = 5;
  const gridLines = Array.from({ length: gridCount + 1 }, (_, i) => {
    const t = minTime + (totalDuration * i) / gridCount;
    const pct = (i / gridCount) * 100;
    const date = new Date(t);
    const label = `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}:${date.getSeconds().toString().padStart(2, '0')}`;
    return { pct, label };
  });

  return (
    <div style={{ width: '100%', userSelect: 'none' }}>
      <div style={{ position: 'relative', height: 32, marginBottom: 4 }}>
        <div style={{ position: 'absolute', inset: 0, display: 'flex', justifyContent: 'space-between', fontSize: 11, color: '#8c8c8c' }}>
          {gridLines.map((g) => (
            <span key={g.pct} style={{ position: 'absolute', left: `${g.pct}%`, transform: 'translateX(-50%)' }}>
              {g.label}
            </span>
          ))}
        </div>
      </div>

      <div style={{ position: 'relative' }}>
        {gridLines.map((g) => (
          <div
            key={g.pct}
            style={{
              position: 'absolute',
              left: `${g.pct}%`,
              top: 0,
              bottom: 0,
              width: 1,
              background: '#f0f0f0',
            }}
          />
        ))}

        <div style={{ position: 'relative', zIndex: 1 }}>
          {flatSpans.map((span) => {
            const startMs = new Date(span.startTime).getTime();
            const durMs = span.durationMs ?? (span.endTime ? new Date(span.endTime).getTime() - startMs : 0);
            const leftPct = Math.max(0, timeToPercent(startMs));
            const widthPct = Math.max(0.5, (durMs / totalDuration) * 100);
            const style = getTypeStyle(span.spanType, span.status);
            const isSelected = selectedSpanId === span.spanId;

            return (
              <div
                key={span.spanId}
                onClick={() => onSelect?.(span.spanId)}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  height: 36,
                  marginBottom: 2,
                  cursor: 'pointer',
                  paddingLeft: span.level * 20,
                  border: isSelected ? '1px solid #1677ff' : '1px solid transparent',
                  borderRadius: 4,
                  background: isSelected ? '#e6f4ff' : 'transparent',
                  transition: 'background 0.2s, border-color 0.2s',
                }}
              >
                <div style={{ position: 'relative', flex: 1, height: 20 }}>
                  <div
                    style={{
                      position: 'absolute',
                      left: `${leftPct}%`,
                      width: `${widthPct}%`,
                      height: 20,
                      borderRadius: 4,
                      background: style.bg,
                      borderLeft: `3px solid ${style.color}`,
                      display: 'flex',
                      alignItems: 'center',
                      padding: '0 8px',
                      fontSize: 12,
                      color: style.color,
                      whiteSpace: 'nowrap',
                      overflow: 'hidden',
                      gap: 4,
                    }}
                  >
                    <span style={{ fontSize: 12 }}>{style.icon}</span>
                    <span style={{ fontWeight: 500 }}>{span.displayName || span.name}</span>
                    <span style={{ color: '#8c8c8c', marginLeft: 'auto', fontSize: 11 }}>
                      {formatDuration(span.durationMs)}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      <div style={{ marginTop: 12, display: 'flex', flexWrap: 'wrap', gap: 12, fontSize: 12, color: '#595959' }}>
        {Object.entries(TYPE_COLORS).map(([key, val]) => (
          <div key={key} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <span style={{ display: 'inline-block', width: 12, height: 12, background: val.color, borderRadius: 2 }} />
            <span>{val.label}</span>
          </div>
        ))}
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <span style={{ display: 'inline-block', width: 12, height: 12, background: FAIL_COLOR, borderRadius: 2 }} />
          <span>FAILED</span>
        </div>
      </div>
    </div>
  );
};

export default SpanWaterfall;