/**
 * @file 轮次头部
 * @description 展示 AI 头像 + 名称 + 元信息(模型/耗时/Token/步数) + 整轮折叠按钮。
 *
 * @author Aegis
 * @since 4.1.0
 */
import React from 'react';
import { Avatar, Tag, Tooltip } from 'antd';
import { RobotOutlined, DownOutlined, RightOutlined, LoadingOutlined } from '@ant-design/icons';
import type { TurnMeta } from '@/types/turn';
import { formatDuration } from '@/utils/format';

interface TurnHeaderProps {
  agentName?: string;
  meta?: TurnMeta;
  collapsed: boolean;
  streaming?: boolean;
  onToggle: () => void;
}

export const TurnHeader: React.FC<TurnHeaderProps> = ({ agentName, meta, collapsed, streaming, onToggle }) => {
  const parts: string[] = [];
  if (meta?.model) parts.push(`🤖 ${meta.model}`);
  if (meta?.durationMs != null && meta.isComplete) parts.push(`⏱ ${formatDuration(meta.durationMs)}`);
  if (meta?.tokenIn != null || meta?.tokenOut != null) {
    parts.push(` Tokens ${meta?.tokenIn ?? 0}/${meta?.tokenOut ?? 0}`);
  }
  if (meta?.stepCount != null) parts.push(`${meta.stepCount} 步`);

  return (
    <div
      onClick={onToggle}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        cursor: 'pointer',
        padding: '4px 6px',
        borderRadius: 8,
        userSelect: 'none',
        transition: 'background 0.15s',
      }}
      onMouseEnter={(e) => {
        (e.currentTarget as HTMLElement).style.background = '#f5f5f5';
      }}
      onMouseLeave={(e) => {
        (e.currentTarget as HTMLElement).style.background = 'transparent';
      }}
    >
      <Avatar size={28} icon={<RobotOutlined />} style={{ background: '#10b981', minWidth: 28, flexShrink: 0 }} />
      <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--color-text-primary)' }}>{agentName ?? 'AI'}</span>
      {streaming && !meta?.isComplete ? (
        <Tag color="processing" style={{ margin: 0, fontSize: 10, lineHeight: '16px', height: 16, padding: '0 4px' }}>
          <LoadingOutlined spin style={{ marginRight: 3 }} />
          生成中
        </Tag>
      ) : meta?.isComplete ? (
        <Tag color="success" style={{ margin: 0, fontSize: 10, lineHeight: '16px', height: 16, padding: '0 4px' }}>完成</Tag>
      ) : null}
      {parts.length > 0 && (
        <Tooltip title={parts.join(' · ')}>
          <span style={{ fontSize: 11, color: 'var(--color-text-tertiary)' }}>{parts.join(' · ')}</span>
        </Tooltip>
      )}
      <span style={{ marginLeft: 'auto' }}>
        {collapsed ? <RightOutlined style={{ fontSize: 10, color: '#999' }} /> : <DownOutlined style={{ fontSize: 10, color: '#999' }} />}
      </span>
    </div>
  );
};

export default TurnHeader;
