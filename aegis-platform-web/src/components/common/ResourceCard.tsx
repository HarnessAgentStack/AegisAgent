/**
 * @file 资源卡片
 * @description 原型中的 .agent-card 组件，用于智能体/资源市场卡片网格展示
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Card, Tag } from 'antd';

interface ResourceCardProps {
  icon: string;
  iconBg: string;
  name: string;
  desc: string;
  meta?: { label: string; value: string }[];
  tags?: { text: string; color?: string }[];
  actions?: React.ReactNode[];
  onClick?: () => void;
}

export const ResourceCard: React.FC<ResourceCardProps> = ({
  icon,
  iconBg,
  name,
  desc,
  meta = [],
  tags = [],
  actions = [],
  onClick,
}) => {
  return (
    <Card
      hoverable
      onClick={onClick}
      size="small"
      style={{ height: '100%', borderColor: '#e5e7eb' }}
      bodyStyle={{ padding: 16 }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
        <div
          style={{
            width: 40,
            height: 40,
            minWidth: 40,
            background: iconBg,
            borderRadius: 10,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 20,
          }}
        >
          {icon}
        </div>
        <div style={{ fontWeight: 600, fontSize: 14, flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {name}
        </div>
      </div>
      <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 8, minHeight: 32, lineClamp: 2, overflow: 'hidden' }}>
        {desc}
      </div>
      {meta.length > 0 && (
        <div style={{ display: 'flex', gap: 12, fontSize: 11, color: '#9ca3af', marginBottom: 8 }}>
          {meta.map((m, i) => (
            <span key={i}>{m.label} {m.value}</span>
          ))}
        </div>
      )}
      {tags.length > 0 && (
        <div style={{ display: 'flex', gap: 4, marginBottom: 8, flexWrap: 'wrap' }}>
          {tags.map((t, i) => (
            <Tag key={i} color={t.color} style={{ fontSize: 11 }}>{t.text}</Tag>
          ))}
        </div>
      )}
      {actions.length > 0 && (
        <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>{actions}</div>
      )}
    </Card>
  );
};

export default ResourceCard;
