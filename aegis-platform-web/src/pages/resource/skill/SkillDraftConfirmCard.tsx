/**
 * @file 技能草稿 HITL 确认卡片
 * @description 人在回路（Human-in-the-Loop）确认卡片，展示技能草稿摘要、工具检测、安全扫描结果，
 *              供审核人员确认提交或驳回
 * @author wang.zhen
 * @since 1.0.0
 */
import React from 'react';
import { Alert, Button, Card, Descriptions, Space, Tag, Typography } from 'antd';
import {
  CheckCircleOutlined,
  CloseCircleOutlined,
  SafetyCertificateOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import type { Skill } from '@/types/resource';
import type { ScanResult } from '@/api/resource';
import { SecurityLevelTag } from '@/components/common/SecurityLevelTag';
import { LifeStatusTag } from '@/components/common/LifeStatusTag';
import { SKILL_TYPE_TAG, parseJsonArray, parseJsonObject } from './constants';

const { Text, Paragraph } = Typography;

interface SkillDraftConfirmCardProps {
  visible: boolean;
  skill: Skill | null;
  scanResult?: ScanResult | null;
  onConfirm: () => void;
  onBack: () => void;
  onReject: (reason: string) => void;
  rejectionReason?: string;
}

const SkillDraftConfirmCard: React.FC<SkillDraftConfirmCardProps> = ({
  visible,
  skill,
  scanResult,
  onConfirm,
  onBack,
  onReject,
  rejectionReason,
}) => {
  if (!visible || !skill) return null;

  const tools = parseJsonArray(skill.bindingTools);
  const inputs = parseJsonObject(skill.inputs);
  const outputs = parseJsonObject(skill.outputs);

  const renderScanResult = () => {
    if (!scanResult) return null;

    const { passed, riskLevel, summary, issues } = scanResult;

    return (
      <Card
        size="small"
        title={
          <Space>
            <SafetyCertificateOutlined style={{ color: passed ? '#52c41a' : '#ff4d4f' }} />
            <span>安全扫描结果</span>
            <Tag color={passed ? 'green' : 'red'}>
              {passed ? 'PASS' : `BLOCKED (${riskLevel})`}
            </Tag>
          </Space>
        }
        style={{ marginBottom: 16, borderColor: passed ? '#52c41a' : '#ff4d4f' }}
      >
        <Alert
          message={summary}
          type={passed ? 'success' : 'error'}
          showIcon
          icon={passed ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
          style={{ marginBottom: issues && issues.length > 0 ? 12 : 0 }}
        />
        {issues && issues.length > 0 && (
          <div style={{ maxHeight: 200, overflowY: 'auto' }}>
            {issues.map((issue, idx) => (
              <div
                key={idx}
                style={{
                  padding: '8px 12px',
                  marginBottom: 4,
                  background: issue.riskLevel === 'HIGH' ? '#fff1f0' : '#fffbe6',
                  borderRadius: 4,
                  border: `1px solid ${issue.riskLevel === 'HIGH' ? '#ffa39e' : '#ffe58f'}`,
                }}
              >
                <Space size={8} wrap>
                  <Tag color={issue.riskLevel === 'HIGH' ? 'red' : 'orange'}>
                    {issue.riskLevel}
                  </Tag>
                  <Text strong>{issue.dimension}</Text>
                  {issue.keyword && (
                    <Text code style={{ fontSize: 12 }}>
                      {issue.keyword}
                    </Text>
                  )}
                </Space>
                <div style={{ marginTop: 4 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {issue.message}
                  </Text>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    );
  };

  const renderTools = () => {
    if (tools.length === 0) {
      return (
        <Card size="small" title="检测到的工具" style={{ marginBottom: 16 }}>
          <Text type="secondary">未检测到绑定工具</Text>
        </Card>
      );
    }

    return (
      <Card
        size="small"
        title={
          <Space>
            <ToolOutlined />
            <span>检测到的工具</span>
            <Tag color="blue">{tools.length}</Tag>
          </Space>
        }
        style={{ marginBottom: 16 }}
      >
        <Space size={[8, 8]} wrap>
          {tools.map((tool) => (
            <Tag key={tool} color="cyan">
              {tool}
            </Tag>
          ))}
        </Space>
        <Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
          以上工具已通过安全扫描审批，可随技能一起发布
        </Paragraph>
      </Card>
    );
  };

  const renderSchemaPreview = () => {
    const hasInputs = Object.keys(inputs).length > 0;
    const hasOutputs = Object.keys(outputs).length > 0;

    if (!hasInputs && !hasOutputs) return null;

    return (
      <Card size="small" title="输入/输出 Schema 预览" style={{ marginBottom: 16 }}>
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          {hasInputs && (
            <div>
              <Text strong style={{ display: 'block', marginBottom: 4 }}>
                输入 Schema
              </Text>
              <Text
                code
                style={{
                  display: 'block',
                  background: '#f5f5f5',
                  padding: 8,
                  borderRadius: 4,
                  fontSize: 12,
                  maxHeight: 150,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                }}
              >
                {JSON.stringify(inputs, null, 2)}
              </Text>
            </div>
          )}
          {hasOutputs && (
            <div>
              <Text strong style={{ display: 'block', marginBottom: 4 }}>
                输出 Schema
              </Text>
              <Text
                code
                style={{
                  display: 'block',
                  background: '#f5f5f5',
                  padding: 8,
                  borderRadius: 4,
                  fontSize: 12,
                  maxHeight: 150,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                }}
              >
                {JSON.stringify(outputs, null, 2)}
              </Text>
            </div>
          )}
        </Space>
      </Card>
    );
  };

  return (
    <div>
      <Card size="small" title="技能草稿摘要" style={{ marginBottom: 16 }}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label="技能名称">{skill.skillName}</Descriptions.Item>
          <Descriptions.Item label="技能编码">{skill.skillCode}</Descriptions.Item>
          <Descriptions.Item label="类型">
            <Tag color={SKILL_TYPE_TAG[skill.skillType].color}>
              {SKILL_TYPE_TAG[skill.skillType].text}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="安全级别">
            <SecurityLevelTag level={skill.securityLevel} />
          </Descriptions.Item>
          {skill.lifeStatus && (
            <Descriptions.Item label="状态">
              <LifeStatusTag status={skill.lifeStatus} />
            </Descriptions.Item>
          )}
          <Descriptions.Item label="版本">
            {skill.version ? `v${skill.version}` : '-'}
          </Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>
            {skill.description ?? '无描述'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      {renderTools()}
      {renderSchemaPreview()}
      {renderScanResult()}

      {rejectionReason && (
        <Alert
          message="驳回原因"
          description={rejectionReason}
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
        />
      )}

      <div style={{ textAlign: 'right' }}>
        <Space>
          <Button onClick={onBack}>返回编辑</Button>
          <Button danger onClick={() => onReject('')}>
            驳回
          </Button>
          <Button type="primary" icon={<CheckCircleOutlined />} onClick={onConfirm}>
            确认并提交
          </Button>
        </Space>
      </div>
    </div>
  );
};

export default SkillDraftConfirmCard;