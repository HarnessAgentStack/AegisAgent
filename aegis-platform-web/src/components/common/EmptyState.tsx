/**
 * @file 空状态
 * @description 统一的空状态占位组件
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Card, Typography } from 'antd';
import { InboxOutlined } from '@ant-design/icons';

const { Text } = Typography;

interface EmptyStateProps {
  icon?: React.ReactNode;
  title?: string;
  desc?: string;
  actions?: React.ReactNode;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  icon = <InboxOutlined style={{ fontSize: 40, color: '#d1d5db' }} />,
  title = '暂无数据',
  desc,
  actions,
}) => {
  return (
    <Card style={{ textAlign: 'center', padding: '48px 20px' }}>
      <div style={{ marginBottom: 12 }}>{icon}</div>
      <Text strong style={{ display: 'block', marginBottom: 4, color: '#6b7280' }}>{title}</Text>
      {desc && <Text type="secondary" style={{ fontSize: 13 }}>{desc}</Text>}
      {actions && <div style={{ marginTop: 16 }}>{actions}</div>}
    </Card>
  );
};

export default EmptyState;
