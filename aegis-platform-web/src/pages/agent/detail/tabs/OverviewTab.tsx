/**
 * @file 概览Tab
 * @description 智能体概览信息：统计卡片、描述、基本信息、能力标签、安全策略、审核历史。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  Card,
  Col,
  Descriptions,
  Empty,
  Row,
  Space,
  Statistic,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { getAgentReviews } from '@/api/agent';
import type { AgentReviewRecord } from '@/api/agent';
import type { Agent } from '@/types/agent';
import { AgentType } from '@/types/enum';
import { GOVERNANCE_COLOR, GOVERNANCE_LABEL } from '../constants';

const { Text, Paragraph } = Typography;

interface OverviewTabProps {
  agent: Agent;
}

const OverviewTab: React.FC<OverviewTabProps> = ({ agent }) => {
  const [reviewRecords, setReviewRecords] = useState<AgentReviewRecord[]>([]);

  const isSystem = agent.agentType === AgentType.SYSTEM;

  const fetchReviewRecords = async () => {
    try {
      const records = await getAgentReviews(agent.id);
      setReviewRecords(records);
    } catch (err) {
      console.error(err);
    }
  };

  useEffect(() => {
    void fetchReviewRecords();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.id]);

  return (
    <div>
      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={8}>
          <Card>
            <Statistic
              title="订阅数"
              value={agent.subsCount ?? 0}
              valueStyle={{ color: '#4f46e5' }}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="治理档位"
              value={GOVERNANCE_LABEL[agent.governanceTier ?? 'STANDARD'] ?? '标准档'}
              valueStyle={{ color: '#f59e0b' }}
            />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic
              title="单会话上限"
              value={agent.maxTurns ?? 20}
              suffix="轮"
              valueStyle={{ color: '#10b981' }}
            />
          </Card>
        </Col>
      </Row>

      <Card title="描述" style={{ marginBottom: 16 }}>
        <Paragraph style={{ marginBottom: 0, color: '#6b7280', lineHeight: 1.7 }}>
          {agent.description || '暂无描述'}
        </Paragraph>
      </Card>

      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={12}>
          <Card>
            <Descriptions column={1}>
              <Descriptions.Item label="提供者">
                <Text strong>平台官方</Text>
              </Descriptions.Item>
              <Descriptions.Item label="分类">
                {agent.category || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {agent.createTime || '-'}
              </Descriptions.Item>
              {isSystem && (
                <Descriptions.Item label="智能体类型">
                  <Tag color="geekblue">系统智能体</Tag>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>
        </Col>
        <Col span={12}>
          <Card>
            <Descriptions column={1}>
              <Descriptions.Item label="治理档位">
                <Tag color={GOVERNANCE_COLOR[agent.governanceTier ?? 'STANDARD'] ?? 'default'}>
                  {GOVERNANCE_LABEL[agent.governanceTier ?? 'STANDARD'] ?? '标准档'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="可见范围">
                本租户可见
              </Descriptions.Item>
              <Descriptions.Item label="发布时间">
                {agent.publishedTime || '-'}
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      <Card title="能力标签" style={{ marginBottom: 16 }}>
        <Space size={[6, 6]} wrap>
          <Tag>问答</Tag>
          <Tag>执行</Tag>
          <Tag>多轮对话</Tag>
          <Tag>工具调用</Tag>
          {agent.memoryStrategy?.includes('LONG') && <Tag>长期记忆</Tag>}
        </Space>
      </Card>

      {isSystem && (
        <Card title="🚀 系统智能体特性" style={{ marginBottom: 16 }}>
          <div
            style={{
              background: '#eef2ff',
              borderLeft: '3px solid #6366f1',
              borderRadius: 6,
              padding: 12,
              fontSize: 12,
              color: '#3730a3',
              lineHeight: 1.8,
            }}
          >
            <Space size={[6, 6]} wrap>
              <Tag color="geekblue">常驻 K8S POD</Tag>
              <Tag color="blue">API 发布</Tag>
              <Tag color="cyan">系统回调</Tag>
            </Space>
            <div style={{ marginTop: 8 }}>
              该智能体面向业务系统发布，常驻 K8S POD 运行，可发布为标准
              API 供外部系统调用，支持系统回调与指定输出格式（JSON / TEXT / XML）。
            </div>
          </div>
        </Card>
      )}

      <Card>
        <div
          style={{
            background: '#eff6ff',
            borderLeft: '3px solid #3b82f6',
            borderRadius: 6,
            padding: 12,
            fontSize: 12,
            color: '#1e40af',
            lineHeight: 1.7,
          }}
        >
          <strong>🛡️ 安全策略</strong>：
          治理档位 {GOVERNANCE_LABEL[agent.governanceTier ?? 'STANDARD'] ?? '标准档'} ·
          敏感词过滤 · 出站白名单 · 内容审核 · 操作审计留痕
        </div>
      </Card>

      {/* 审核历史时间线 */}
      <Card title="📋 审核历史" style={{ marginTop: 16 }}>
        {reviewRecords.length === 0 ? (
          <Empty description="暂无审核记录" />
        ) : (
          <Timeline
            items={reviewRecords.map((r) => {
              const isApproved = r.reviewStatus === 'APPROVED' || r.reviewStatus === 'PUBLISHED';
              const isRejected = r.reviewStatus === 'REJECTED';
              return {
                color: isApproved ? 'green' : isRejected ? 'red' : 'blue',
                children: (
                  <div>
                    <div style={{ marginBottom: 4 }}>
                      <Tag color={isApproved ? 'green' : isRejected ? 'red' : 'processing'}>
                        {isApproved ? '通过' : isRejected ? '驳回' : '提交审核'}
                      </Tag>
                      <span style={{ fontSize: 11, color: '#9ca3af', marginLeft: 8 }}>
                        {r.submitTime}
                      </span>
                    </div>
                    <div style={{ fontSize: 12, color: '#6b7280' }}>
                      申请人ID: {r.applicantUserId}
                      {r.reviewerUserId && ` · 审核人ID: ${r.reviewerUserId}`}
                      {r.reviewTime && ` · 审核时间: ${r.reviewTime}`}
                    </div>
                    {r.rejectReason && (
                      <div
                        style={{
                          marginTop: 4,
                          padding: '6px 10px',
                          background: '#fef2f2',
                          borderRadius: 6,
                          fontSize: 12,
                          color: '#991b1b',
                          borderLeft: '3px solid #ef4444',
                        }}
                      >
                        驳回原因：{r.rejectReason}
                      </div>
                    )}
                  </div>
                ),
              };
            })}
          />
        )}
      </Card>
    </div>
  );
};

export default OverviewTab;
