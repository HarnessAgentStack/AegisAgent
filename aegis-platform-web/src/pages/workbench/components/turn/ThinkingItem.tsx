/**
 * @file 思考事件项
 * @description 渲染 TurnEvent(thinking)：琥珀色条 + 缩进，支持三档折叠
 *              (collapsed/collapsedPreview/fixedScrolling) + 完成摘要。
 *              完成态默认 preview(首句/摘要)；运行态默认 fixedScrolling 跟随增量。
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
  BulbOutlined,
} from '@ant-design/icons';
import type { ThinkingEvent } from '@/types/turn';
import type { ThinkingStyle } from '@/types/collapsePolicy';
import { formatDuration } from '@/utils/format';

interface ThinkingItemProps {
  event: ThinkingEvent;
  /** 序号（流中位置） */
  index: number;
  thinkingStyle: ThinkingStyle;
  /** 精简模式（隐藏图标/缩进） */
  compact?: boolean;
}

export const ThinkingItem: React.FC<ThinkingItemProps> = ({ event, index, thinkingStyle, compact }) => {
  const p = event.payload;
  // 运行中默认展开（看流式思考）；完成态默认折叠（preview 摘要）
  const [expanded, setExpanded] = useState<boolean>(p.status === 'RUNNING');
  // 用户是否手动操作过（手动操作后不再自动收起）
  const userTouchedRef = useRef(false);

  // 完成态 1.5s 后自动收缩为 preview（用户未手动操作时）；运行态由初始 useState 保证展开
  useEffect(() => {
    if ((p.status === 'SUCCESS' || p.status === 'FAILED') && !userTouchedRef.current) {
      const timer = setTimeout(() => setExpanded(false), 1500);
      return () => clearTimeout(timer);
    }
  }, [p.status, p.durationMs]);

  // fixedScrolling：流式增量自动滚到底
  const detailRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (expanded && thinkingStyle === 'fixedScrolling' && detailRef.current) {
      detailRef.current.scrollTop = detailRef.current.scrollHeight;
    }
  }, [expanded, p.detail, thinkingStyle]);

  const hasDetail = !!(p.detail || p.summary);
  const showBody = expanded && hasDetail;
  const indent = compact ? 0 : 24;

  return (
    <div style={{ marginBottom: 2, position: 'relative' }}>
      <div
        onClick={() => {
          if (!hasDetail) return;
          userTouchedRef.current = true;
          setExpanded((v) => !v);
        }}
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 8,
          cursor: hasDetail ? 'pointer' : 'default',
          padding: '3px 8px',
          marginLeft: indent,
          borderRadius: 6,
          borderLeft: '2px solid #fa8c16',
          background: p.status === 'RUNNING' ? 'rgba(255,251,230,0.5)' : 'transparent',
          transition: 'background 0.2s',
        }}
      >
        {/* 状态/序号圆标 */}
        <div
          style={{
            width: 18,
            height: 18,
            borderRadius: '50%',
            background:
              p.status === 'SUCCESS' ? '#fa8c16' : p.status === 'FAILED' ? '#ff4d4f' : p.status === 'RUNNING' ? '#faad14' : '#d9d9d9',
            color: '#fff',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 9,
            flexShrink: 0,
            boxShadow: p.status === 'RUNNING' ? '0 0 0 3px rgba(250,173,20,0.18)' : 'none',
          }}
        >
          {p.status === 'RUNNING' ? (
            <LoadingOutlined spin style={{ fontSize: 8 }} />
          ) : p.status === 'SUCCESS' ? (
            <CheckOutlined style={{ fontSize: 8 }} />
          ) : p.status === 'FAILED' ? (
            <CloseOutlined style={{ fontSize: 8 }} />
          ) : (
            <span style={{ fontSize: 9 }}>{index + 1}</span>
          )}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            {!compact && <BulbOutlined style={{ color: '#fa8c16', fontSize: 11 }} />}
            <span style={{ fontSize: 12, color: '#333', fontWeight: 500 }}>{p.title}</span>
            {p.status === 'RUNNING' && (
              <Tag color="processing" style={{ margin: 0, fontSize: 9, padding: '0 4px', height: 16, lineHeight: '16px' }}>
                <LoadingOutlined spin style={{ marginRight: 2 }} />
                思考中
              </Tag>
            )}
            {p.status === 'SUCCESS' && p.durationMs != null && (
              <Tooltip title={`耗时 ${formatDuration(p.durationMs)}`}>
                <span style={{ fontSize: 10, color: '#999' }}>{formatDuration(p.durationMs)}</span>
              </Tooltip>
            )}
            {hasDetail &&
              (expanded ? (
                <DownOutlined style={{ fontSize: 8, color: '#999', marginLeft: 'auto' }} />
              ) : (
                <RightOutlined style={{ fontSize: 8, color: '#999', marginLeft: 'auto' }} />
              ))}
          </div>

          {/* 展开详情 */}
          {showBody && (
            <div
              ref={detailRef}
              style={{
                marginTop: 4,
                padding: '6px 10px',
                background: '#fffbe6',
                borderRadius: 6,
                fontSize: 11,
                color: '#614700',
                lineHeight: 1.5,
                whiteSpace: 'pre-wrap',
                maxHeight: thinkingStyle === 'fixedScrolling' ? 120 : 220,
                overflowY: 'auto',
                transition: 'all 0.3s ease',
                border: '1px solid #ffe58f',
                wordBreak: 'break-word',
              }}
            >
              {p.detail}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default ThinkingItem;
