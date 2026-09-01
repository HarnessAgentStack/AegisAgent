/**
 * @file 配置参数Tab
 * @description 展示系统提示词、模型参数、工具、API发布配置等。
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Card, Col, Descriptions, Row, Space, Tag, Typography } from 'antd';
import type { Agent } from '@/types/agent';
import { AgentType } from '@/types/enum';
import { GOVERNANCE_LABEL } from '../constants';
import { safeJsonParse } from '@/utils/number';

const { Text } = Typography;

interface ConfigTabProps {
  agent: Agent;
}

const ConfigTab: React.FC<ConfigTabProps> = ({ agent }) => {
  const isUniversal = agent.agentType === AgentType.UNIVERSAL;
  const isSystem = agent.agentType === AgentType.SYSTEM;

  return (
    <div>
      <Card title="📝 系统提示词（System Prompt）" style={{ marginBottom: 16 }}>
        <div
          style={{
            background: '#f9fafb',
            borderRadius: 6,
            padding: 12,
            fontSize: 12,
            color: '#6b7280',
            lineHeight: 1.7,
            border: '1px solid #e5e7eb',
            whiteSpace: 'pre-wrap',
            minHeight: 80,
          }}
        >
          {agent.systemPrompt || '未配置系统提示词'}
        </div>
      </Card>

      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small">
            <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 4 }}>模型档位</div>
            <div style={{ fontSize: 13, fontWeight: 600 }}>🧠 {agent.modelTier || 'STANDARD'}</div>
            <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 2 }}>
              用户不可见，仅管理员可配
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 4 }}>温度参数</div>
            <div style={{ fontSize: 13, fontWeight: 600 }}>🌡️ {agent.temperature ?? 0.7}</div>
            <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 2 }}>
              越低越确定，越高越发散
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 4 }}>记忆策略</div>
            <div style={{ fontSize: 13, fontWeight: 600 }}>
              🧠 {agent.memoryStrategy || 'SESSION_LEVEL'}
            </div>
            <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 2 }}>
              {isUniversal ? '跨会话保留用户归档' : '共享上下文'}
            </div>
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small">
            <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 4 }}>
              单会话轮次上限
            </div>
            <div style={{ fontSize: 13, fontWeight: 600 }}>
              🔄 {agent.maxTurns ?? 20} 轮
            </div>
            <div style={{ fontSize: 11, color: '#9ca3af', marginTop: 2 }}>
              超出后提示新建任务
            </div>
          </Card>
        </Col>
      </Row>

      <Card title="⚡ 已启用工具" style={{ marginBottom: 16 }}>
        <Space size={[6, 6]} wrap>
          {(() => {
            const toolsJson = agent.enabledTools;
            let toolIds: number[] = [];
            if (toolsJson) {
              const parsed = safeJsonParse<number[]>(toolsJson);
              toolIds = Array.isArray(parsed) ? parsed : [];
            }
            if (toolIds.length === 0) {
              return <Tag>未启用工具</Tag>;
            }
            return toolIds.map((tid) => (
              <Tag color="blue" key={tid}>
                工具 #{tid}
              </Tag>
            ));
          })()}
        </Space>
      </Card>

      {isSystem && (
        <Card title="🔌 系统智能体部署" style={{ marginBottom: 16 }}>
          <Descriptions column={1} bordered size="small">
            <Descriptions.Item label="部署目标（沙箱池）">
              {agent.deploymentPoolCode ? (
                <Tag color="geekblue">{agent.deploymentPoolCode}</Tag>
              ) : (
                <Text type="secondary">审核通过后由平台统一分配</Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="预留副本数">
              {agent.reservedReplicas ?? '-'}
            </Descriptions.Item>
            <Descriptions.Item label="API 访问">
              <Tag color="blue">审核通过后自动开通（API_KEY 鉴权）</Tag>
            </Descriptions.Item>
          </Descriptions>
        </Card>
      )}

      <Card>
        <div
          style={{
            background: '#fff7ed',
            borderLeft: '3px solid #f59e0b',
            borderRadius: 6,
            padding: 12,
            fontSize: 12,
            color: '#9a3412',
            lineHeight: 1.7,
          }}
        >
          <strong>🛡️ 运行时安全监管</strong>：
          治理档位 {GOVERNANCE_LABEL[agent.governanceTier ?? 'STANDARD'] ?? '标准档'} · 敏感词过滤 · 出站白名单 ·
          内容审核 · 操作审计留痕 · HITL 人审节点
        </div>
      </Card>
    </div>
  );
};

export default ConfigTab;
