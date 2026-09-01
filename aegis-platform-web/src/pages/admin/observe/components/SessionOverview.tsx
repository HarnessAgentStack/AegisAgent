import React from 'react';
import { Card, Row, Col, Tag, Typography, Statistic } from 'antd';
import {
  ThunderboltOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  UserOutlined,
  RobotOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import type { SessionDetailResponse } from '@/api/observe';
import { formatDuration } from '@/utils/format';

const { Text } = Typography;

interface SessionOverviewProps {
  detail: SessionDetailResponse;
}

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  TIMEOUT: { color: 'warning', text: '超时' },
};

function formatTime(iso?: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return `${d.getFullYear()}-${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}

const SessionOverview: React.FC<SessionOverviewProps> = ({ detail }) => {
  const statusInfo = STATUS_MAP[detail.status] || { color: 'default', text: detail.status };

  return (
    <Card
      style={{
        background: 'linear-gradient(135deg, #f6f8fa 0%, #eef2f7 100%)',
        border: '1px solid #e6e8eb',
      }}
    >
      {/* Header with status */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          marginBottom: 16,
          paddingBottom: 12,
          borderBottom: '2px solid #1677ff',
        }}
      >
        <PlayCircleOutlined style={{ fontSize: 32, color: '#1677ff' }} />
        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <Text strong style={{ fontSize: 16 }}>
              会话详情
            </Text>
            <Tag color={statusInfo.color} style={{ fontWeight: 600 }}>
              {statusInfo.text}
            </Tag>
          </div>
          <Text copyable style={{ fontSize: 12, color: '#8c8c8c', fontFamily: 'monospace' }}>
            Session ID: {detail.sessionId}
          </Text>
        </div>
      </div>

      {/* Identity row */}
      <Row gutter={[16, 12]} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <RobotOutlined style={{ color: '#1677ff' }} />
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>智能体</span>
            <Text strong>{detail.agentName || (detail.agentId ? `Agent#${detail.agentId}` : '-')}</Text>
          </div>
        </Col>
        <Col span={8}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <UserOutlined style={{ color: '#722ed1' }} />
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>用户</span>
            <Text strong>{detail.userName || (detail.userId ? `User#${detail.userId}` : '-')}</Text>
          </div>
        </Col>
        <Col span={8}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <ClockCircleOutlined style={{ color: '#52c41a' }} />
            <span style={{ color: '#8c8c8c', fontSize: 12 }}>时间段</span>
            <Text strong style={{ fontSize: 12 }}>
              {formatTime(detail.startTime)} ~ {formatTime(detail.endTime)}
            </Text>
          </div>
        </Col>
      </Row>

      {/* Stats grid - 3 items now, use span=8 each */}
      <Row gutter={[12, 12]}>
        <Col xs={8} sm={8}>
          <Card size="small" style={{ background: '#fff', textAlign: 'center' }}>
            <Statistic
              title={<span style={{ color: '#8c8c8c', fontSize: 12 }}>总轮次</span>}
              value={detail.totalRounds}
              prefix={<ThunderboltOutlined style={{ color: '#fa8c16' }} />}
              valueStyle={{ color: '#fa8c16', fontSize: 24 }}
            />
          </Card>
        </Col>
        <Col xs={8} sm={8}>
          <Card size="small" style={{ background: '#fff', textAlign: 'center' }}>
            <Statistic
              title={<span style={{ color: '#8c8c8c', fontSize: 12 }}>总耗时</span>}
              value={formatDuration(detail.totalDurationMs)}
              prefix={<ClockCircleOutlined style={{ color: '#1677ff' }} />}
              valueStyle={{ color: '#1677ff', fontSize: 20 }}
            />
          </Card>
        </Col>
        <Col xs={8} sm={8}>
          <Card size="small" style={{ background: '#fff', textAlign: 'center' }}>
            <Statistic
              title={<span style={{ color: '#8c8c8c', fontSize: 12 }}>Token 消耗</span>}
              value={`${detail.totalTokenInput.toLocaleString()} / ${detail.totalTokenOutput.toLocaleString()}`}
              prefix={<CodeOutlined style={{ color: '#722ed1' }} />}
              suffix={<span style={{ fontSize: 12, color: '#8c8c8c' }}>入/出</span>}
              valueStyle={{ color: '#722ed1', fontSize: 18 }}
            />
          </Card>
        </Col>
      </Row>
    </Card>
  );
};

export default SessionOverview;
