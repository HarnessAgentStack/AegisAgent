import React, { useCallback, useEffect, useState } from 'react';
import { Drawer, Spin, Empty, Button, Typography, Tag, Space, message } from 'antd';
import {
  ReloadOutlined,
  CloseOutlined,
  ThunderboltOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { SessionDetailResponse } from '@/api/observe';
import { getSessionDetail } from '@/api/observe';
import SessionOverview from './SessionOverview';
import RoundCard from './RoundCard';

const { Text } = Typography;

interface SessionDetailPanelProps {
  open: boolean;
  sessionId: string | null;
  onClose: () => void;
}

const SessionDetailPanel: React.FC<SessionDetailPanelProps> = ({ open, sessionId, onClose }) => {
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<SessionDetailResponse | null>(null);

  const fetchDetail = useCallback(async (id: string) => {
    setLoading(true);
    try {
      const data = await getSessionDetail(id);
      setDetail(data);
    } catch (err) {
      console.error('Failed to load session detail:', err);
      message.error('加载会话详情失败');
      setDetail(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open && sessionId) {
      fetchDetail(sessionId);
    } else if (!open) {
      setDetail(null);
    }
  }, [open, sessionId, fetchDetail]);

  const handleRefresh = () => {
    if (sessionId) {
      fetchDetail(sessionId);
    }
  };

  // Count stats
  const totalLlmCalls = detail?.rounds.reduce((sum, r) => sum + r.llmCallCount, 0) ?? 0;
  const totalToolCalls = detail?.rounds.reduce((sum, r) => sum + r.toolCallCount, 0) ?? 0;

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <ThunderboltOutlined style={{ color: '#1677ff', fontSize: 20 }} />
          <span style={{ fontSize: 16, fontWeight: 600 }}>会话执行详情</span>
          <Tag color="blue" style={{ marginLeft: 8 }}>
            {detail?.totalRounds ?? 0} 轮
          </Tag>
          <Tag color="geekblue">
            {totalLlmCalls} LLM
          </Tag>
          <Tag color="orange">
            {totalToolCalls} Tool
          </Tag>
        </div>
      }
      width={1100}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={handleRefresh} disabled={!sessionId}>
            刷新
          </Button>
          <Button icon={<CloseOutlined />} onClick={onClose}>
            关闭
          </Button>
        </Space>
      }
      bodyStyle={{ padding: 16, overflow: 'auto', background: '#f5f7fa' }}
    >
      <Spin spinning={loading}>
        {!detail && !loading && (
          <div style={{ marginTop: 100 }}>
            <Empty description="请选择一个会话查看详情" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          </div>
        )}

        {detail && (
          <div>
            {/* Overview */}
            <SessionOverview detail={detail} />

            {/* Rounds */}
            <div style={{ marginTop: 20 }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  marginBottom: 12,
                  paddingBottom: 8,
                  borderBottom: '2px solid #1677ff',
                }}
              >
                <ThunderboltOutlined style={{ color: '#1677ff' }} />
                <Text strong style={{ fontSize: 15 }}>
                  执行流程（{detail.totalRounds} 轮）
                </Text>
                <Tag color="blue">按轮次展开</Tag>
                <div style={{ flex: 1 }} />
                <Text type="secondary" style={{ fontSize: 12 }}>
                  <InfoCircleOutlined /> 每个轮次包含 LLM 调用和工具调用步骤
                </Text>
              </div>

              {detail.rounds.length === 0 ? (
                <Empty description="暂无执行流程数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
              ) : (
                <div>
                  {detail.rounds.map((round, idx) => (
                    <RoundCard
                      key={round.roundIndex}
                      round={round}
                      defaultExpanded={idx === 0}
                    />
                  ))}
                </div>
              )}
            </div>
          </div>
        )}
      </Spin>
    </Drawer>
  );
};

export default SessionDetailPanel;
