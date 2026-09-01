import React from 'react';
import { Collapse, Tag, Typography, Space, Divider } from 'antd';
import {
  ThunderboltOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  ToolOutlined,
  PlayCircleOutlined,
} from '@ant-design/icons';
import type { RoundDetail, StepDetail } from '@/api/observe';
import { formatDuration } from '@/utils/format';
import LlmCallFlowCard from './LlmCallFlowCard';
import ToolCallCard from './ToolCallCard';

const { Text } = Typography;

interface RoundCardProps {
  round: RoundDetail;
  defaultExpanded?: boolean;
}

const ROUND_TYPE_MAP: Record<string, string> = {
  USER_INPUT: '用户输入',
  USER_QUERY: '用户请求',
  SYSTEM_INIT: '系统初始化',
  AGENT_REASONING: '智能体推理',
  TOOL_EXECUTION: '工具执行',
  RESPONSE_GENERATION: '响应生成',
  FINAL_RESPONSE: '最终响应',
};

const RoundCard: React.FC<RoundCardProps> = ({ round, defaultExpanded = false }) => {
  const roundTypeLabel = ROUND_TYPE_MAP[round.roundType || ''] || round.roundType || '执行轮次';
  const totalTokens = round.tokenInput + round.tokenOutput;

  const renderStep = (step: StepDetail, index: number) => {
    if (step.spanType === 'LLM_CALL') {
      return <LlmCallFlowCard key={step.spanId} step={step} stepNumber={index + 1} />;
    }
    if (step.spanType === 'TOOL_CALL') {
      return <ToolCallCard key={step.spanId} step={step} stepNumber={index + 1} />;
    }
    // Generic step
    return (
      <div
        key={step.spanId}
        style={{
          padding: '8px 12px',
          background: '#f5f5f5',
          borderRadius: 6,
          marginBottom: 8,
          border: '1px solid #e0e0e0',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space>
            <Tag color="default">{step.spanType}</Tag>
            <Text strong>{step.displayName || step.name}</Text>
          </Space>
          <Text type="secondary" style={{ fontSize: 12 }}>
            {formatDuration(step.durationMs)}
          </Text>
        </div>
        {step.responseTextPreview && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            {step.responseTextPreview.slice(0, 150)}
          </Text>
        )}
      </div>
    );
  };

  return (
    <Collapse
      bordered
      defaultActiveKey={defaultExpanded ? [`round-${round.roundIndex}`] : []}
      items={[
        {
          key: `round-${round.roundIndex}`,
          label: (
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                width: '100%',
                flexWrap: 'wrap',
              }}
            >
              <Tag color="blue" style={{ fontWeight: 600 }}>
                <PlayCircleOutlined /> 第 {round.roundIndex + 1} 轮
              </Tag>
              <Text strong style={{ fontSize: 14 }}>
                {round.roundTitle || roundTypeLabel}
              </Text>
              <Tag color="geekblue">{roundTypeLabel}</Tag>

              <div style={{ flex: 1 }} />

              <Space size={12} style={{ fontSize: 12, color: '#8c8c8c' }}>
                <span>
                  <ClockCircleOutlined /> {formatDuration(round.durationMs)}
                </span>
                <span>
                  <CodeOutlined /> {totalTokens.toLocaleString()} tokens
                </span>
                <span>
                  <ThunderboltOutlined /> {round.llmCallCount} LLM /{' '}
                  <ToolOutlined /> {round.toolCallCount} Tool
                </span>
              </Space>
            </div>
          ),
          children: (
            <div
              style={{
                padding: '12px 8px',
                background: '#fafafa',
                borderRadius: 6,
              }}
            >
              {/* Sub-stats */}
              <div
                style={{
                  display: 'flex',
                  gap: 16,
                  marginBottom: 16,
                  padding: '8px 12px',
                  background: '#fff',
                  borderRadius: 6,
                  border: '1px solid #e6e8eb',
                  fontSize: 12,
                }}
              >
                <span>
                  <Text type="secondary">耗时：</Text>
                  <Text strong>{formatDuration(round.durationMs)}</Text>
                </span>
                <Divider type="vertical" />
                <span>
                  <Text type="secondary">Token：</Text>
                  输入 <Text strong style={{ color: '#722ed1' }}>{round.tokenInput}</Text> /
                  输出 <Text strong style={{ color: '#1677ff' }}>{round.tokenOutput}</Text>
                </span>
                <Divider type="vertical" />
                <span>
                  <Text type="secondary">LLM 调用：</Text>
                  <Text strong>{round.llmCallCount}</Text>
                </span>
                <Divider type="vertical" />
                <span>
                  <Text type="secondary">工具调用：</Text>
                  <Text strong>{round.toolCallCount}</Text>
                </span>
              </div>

              {/* Steps */}
              <div>
                {round.steps.length === 0 ? (
                  <Text type="secondary">暂无步骤数据</Text>
                ) : (
                  round.steps.map((step, idx) => {
                    // Calculate step number relative to step type
                    if (step.spanType === 'LLM_CALL') {
                      const llmSteps = round.steps.filter(
                        (s) => s.spanType === 'LLM_CALL',
                      );
                      const llmIndex = llmSteps.findIndex((s) => s.spanId === step.spanId);
                      return <LlmCallFlowCard key={step.spanId} step={step} stepNumber={llmIndex + 1} />;
                    }
                    if (step.spanType === 'TOOL_CALL') {
                      const toolSteps = round.steps.filter(
                        (s) => s.spanType === 'TOOL_CALL',
                      );
                      const toolIndex = toolSteps.findIndex((s) => s.spanId === step.spanId);
                      return <ToolCallCard key={step.spanId} step={step} stepNumber={toolIndex + 1} />;
                    }
                    return renderStep(step, idx);
                  })
                )}
              </div>
            </div>
          ),
        },
      ]}
    />
  );
};

export default RoundCard;
