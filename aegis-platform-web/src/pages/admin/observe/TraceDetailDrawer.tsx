/**
 * @file Trace 详情抽屉
 * @description 工作流视图：Trace 概览 + Round 分组 Step 卡片 + Span 瀑布图 + Span 详情
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Drawer,
  Descriptions,
  Tag,
  Spin,
  Empty,
  Typography,
  Divider,
  Button,
  Collapse,
  Space,
} from 'antd';
import {
  WarningOutlined,
  ClockCircleOutlined,
  ThunderboltOutlined,
  CodeOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import type { TraceRecord, SpanRecord, TraceDetail } from '@/api/observe';
import { getTraceDetail } from '@/api/observe';
import SpanWaterfall from './components/SpanWaterfall';
import { formatDuration } from '@/utils/format';

const { Text, Paragraph } = Typography;

interface TraceDetailDrawerProps {
  open: boolean;
  traceId: string | null;
  onClose: () => void;
}

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  TIMEOUT: { color: 'warning', text: '超时' },
};

const TYPE_COLORS: Record<string, string> = {
  LLM_CALL: 'blue',
  TOOL_CALL: 'orange',
  AGENT_ASSEMBLY: 'geekblue',
  RAG_RETRIEVE: 'green',
  HITL_WAIT: 'purple',
  SANDBOX_EXEC: 'cyan',
};

function formatTime(iso?: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return `${d.getFullYear()}-${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}

const TraceDetailDrawer: React.FC<TraceDetailDrawerProps> = ({ open, traceId, onClose }) => {
  const [loading, setLoading] = useState(false);
  const [detail, setDetail] = useState<TraceDetail | null>(null);
  const [selectedSpanId, setSelectedSpanId] = useState<string | undefined>(undefined);

  const fetchDetail = useCallback(async (id: string) => {
    setLoading(true);
    setSelectedSpanId(undefined);
    try {
      const data = await getTraceDetail(id);
      setDetail(data);
      if (data.spans.length > 0) {
        setSelectedSpanId(data.spans[0].spanId);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open && traceId) {
      fetchDetail(traceId);
    } else {
      setDetail(null);
    }
  }, [open, traceId, fetchDetail]);

  const currentSpan: SpanRecord | undefined = selectedSpanId
    ? detail?.spans.find((s) => s.spanId === selectedSpanId)
    : undefined;

  const rounds = useMemo(() => {
    if (!detail?.spans) return [];
    const grouped = new Map<number, SpanRecord[]>();
    detail.spans.forEach((span) => {
      const round = span.roundIndex ?? 0;
      if (!grouped.has(round)) grouped.set(round, []);
      grouped.get(round)!.push(span);
    });
    return Array.from(grouped.entries())
      .sort((a, b) => a[0] - b[0])
      .map(([roundIndex, spans]) => ({
        roundIndex,
        spans: spans.sort((a, b) => (a.stepIndex ?? 0) - (b.stepIndex ?? 0)),
      }));
  }, [detail]);

  const renderOverview = (trace: TraceRecord) => {
    const statusInfo = STATUS_MAP[trace.status] || { color: 'default', text: trace.status };
    return (
      <Descriptions
        column={2}
        size="small"
        bordered
        style={{ background: '#fafafa', borderRadius: 4 }}
        labelStyle={{ width: 120, background: '#f5f5f5' }}
      >
        <Descriptions.Item label="Trace ID" span={2}>
          <Text copyable style={{ fontSize: 12, fontFamily: 'monospace' }}>
            {trace.traceId}
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag color={statusInfo.color}>{statusInfo.text}</Tag>
        </Descriptions.Item>
        <Descriptions.Item label="会话 ID">
          {trace.sessionId ? (
            <Text copyable style={{ fontSize: 12, fontFamily: 'monospace' }}>
              {trace.sessionId}
            </Text>
          ) : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="智能体">
          {trace.agentName || trace.agentId || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="用户">
          {trace.userName || trace.userId || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="入口路径" span={2}>
          {trace.apiPath || '-'}
        </Descriptions.Item>
        <Descriptions.Item label="开始时间">{formatTime(trace.startTime)}</Descriptions.Item>
        <Descriptions.Item label="结束时间">{formatTime(trace.endTime)}</Descriptions.Item>
        <Descriptions.Item label="总耗时">
          <Text strong style={{ color: '#1677ff' }}>
            {formatDuration(trace.durationMs)}
          </Text>
        </Descriptions.Item>
        <Descriptions.Item label="Token 消耗">
          <span>
            <CodeOutlined /> 输入: {trace.tokenInput ?? 0} / 输出: {trace.tokenOutput ?? 0}
          </span>
        </Descriptions.Item>
        <Descriptions.Item label="Span 数量">{trace.spanCount ?? 0}</Descriptions.Item>
        {trace.errorMsg && (
          <Descriptions.Item label="错误信息" span={2}>
            <Text type="danger">{trace.errorMsg}</Text>
          </Descriptions.Item>
        )}
      </Descriptions>
    );
  };

  const renderLlmCard = (span: SpanRecord) => {
    const isSelected = selectedSpanId === span.spanId;
    // Extract model info from meta
    const metaObj = span.meta as Record<string, unknown> | undefined;
    const modelName = (metaObj?.modelName as string) || span.modelName || '模型调用';
    const modelTier = (metaObj?.modelTier as string) || '';
    const contextData = metaObj?.context as Record<string, unknown> | undefined;
    const outputText = contextData?.outputText as string | undefined;
    const toolCalls = contextData?.toolCalls as Array<Record<string, unknown>> | undefined;

    return (
      <div
        onClick={() => setSelectedSpanId(span.spanId)}
        style={{
          background: '#e6f4ff',
          border: isSelected ? '2px solid #1677ff' : '1px solid #91caff',
          borderRadius: 8,
          padding: 12,
          cursor: 'pointer',
          transition: 'border-color 0.2s',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontWeight: 600, color: '#1677ff' }}>
            <CodeOutlined /> {span.displayName || span.name || modelName}
          </span>
          <Space>
            {modelTier && <Tag color="geekblue" style={{ fontSize: 11 }}>{modelTier}</Tag>}
            {span.status && (
              <Tag color={STATUS_MAP[span.status]?.color || 'default'}>
                {STATUS_MAP[span.status]?.text || span.status}
              </Tag>
            )}
            <Tag color="blue">{formatDuration(span.durationMs)}</Tag>
          </Space>
        </div>
        <div style={{ marginTop: 8, display: 'flex', gap: 16, fontSize: 12, flexWrap: 'wrap' }}>
          <span>输入: {span.tokenInput ?? 0}</span>
          <span>输出: {span.tokenOutput ?? 0}</span>
          {span.cacheHitTokens != null && (
            <span style={{ color: '#52c41a' }}>缓存命中: {span.cacheHitTokens}</span>
          )}
          {span.cacheMissTokens != null && (
            <span style={{ color: '#fa8c16' }}>缓存未命中: {span.cacheMissTokens}</span>
          )}
          {span.reasoningTokens != null && <span>推理: {span.reasoningTokens}</span>}
        </div>

        {/* Response text from context */}
        {outputText && (
          <div style={{ marginTop: 8 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              模型输出
            </Text>
            <Paragraph
              style={{
                marginTop: 4,
                padding: 8,
                background: '#f6f8fa',
                borderRadius: 4,
                maxHeight: 120,
                overflow: 'auto',
                fontSize: 12,
                marginBottom: 0,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
              }}
            >
              {outputText}
            </Paragraph>
          </div>
        )}

        {/* Tool calls from context */}
        {toolCalls && toolCalls.length > 0 && (
          <div style={{ marginTop: 8 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              本轮工具调用 ({toolCalls.length})
            </Text>
            <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
              {toolCalls.map((tc, idx) => (
                <Tag key={idx} color="orange" style={{ fontSize: 11 }}>
                  {String((tc.toolCallName as string) || (tc.name as string) || 'tool')}
                </Tag>
              ))}
            </div>
          </div>
        )}

        {(span.requestBody || span.responseBody) && (
          <Collapse ghost style={{ marginTop: 8 }}>
            {span.requestBody && (
              <Collapse.Panel header="Request Body" key="req">
                <pre
                  style={{
                    maxHeight: 200,
                    overflow: 'auto',
                    fontSize: 12,
                    background: '#f6f8fa',
                    padding: 8,
                    borderRadius: 4,
                    margin: 0,
                  }}
                >
                  {JSON.stringify(span.requestBody, null, 2)}
                </pre>
              </Collapse.Panel>
            )}
            {span.responseBody && (
              <Collapse.Panel header="Response Body" key="resp">
                <pre
                  style={{
                    maxHeight: 200,
                    overflow: 'auto',
                    fontSize: 12,
                    background: '#f6f8fa',
                    padding: 8,
                    borderRadius: 4,
                    margin: 0,
                  }}
                >
                  {JSON.stringify(span.responseBody, null, 2)}
                </pre>
              </Collapse.Panel>
            )}
          </Collapse>
        )}
      </div>
    );
  };

  const renderToolCard = (span: SpanRecord) => {
    const isSelected = selectedSpanId === span.spanId;
    return (
      <div
        onClick={() => setSelectedSpanId(span.spanId)}
        style={{
          background: '#fff7e6',
          border: isSelected ? '2px solid #d48806' : '1px solid #ffd591',
          borderRadius: 8,
          padding: 12,
          cursor: 'pointer',
          transition: 'border-color 0.2s',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontWeight: 600, color: '#d48806' }}>
            <ToolOutlined /> {span.name}
          </span>
          <Space>
            <Tag color={span.status === 'SUCCESS' ? 'success' : 'error'}>{span.status}</Tag>
            <span style={{ fontSize: 12, color: '#8c8c8c' }}>{formatDuration(span.durationMs)}</span>
          </Space>
        </div>
        {span.inputSummary && (
          <div style={{ marginTop: 8 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              参数
            </Text>
            <pre
              style={{
                maxHeight: 100,
                overflow: 'auto',
                fontSize: 12,
                background: '#fffbe6',
                padding: 8,
                borderRadius: 4,
                margin: '4px 0 0',
              }}
            >
              {span.inputSummary}
            </pre>
          </div>
        )}
        {span.outputSummary && (
          <div style={{ marginTop: 8 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              结果
            </Text>
            <pre
              style={{
                maxHeight: 100,
                overflow: 'auto',
                fontSize: 12,
                background: '#fffbe6',
                padding: 8,
                borderRadius: 4,
                margin: '4px 0 0',
              }}
            >
              {span.outputSummary}
            </pre>
          </div>
        )}
      </div>
    );
  };

  const renderGenericCard = (span: SpanRecord) => {
    const isSelected = selectedSpanId === span.spanId;
    const typeColor = TYPE_COLORS[span.spanType] || 'default';
    return (
      <div
        onClick={() => setSelectedSpanId(span.spanId)}
        style={{
          background: '#f5f5f5',
          border: isSelected ? '2px solid #1677ff' : '1px solid #e0e0e0',
          borderRadius: 8,
          padding: 12,
          cursor: 'pointer',
          transition: 'border-color 0.2s',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Space>
            <Tag color={typeColor}>{span.spanType}</Tag>
            <Text strong>{span.name}</Text>
          </Space>
          <Space>
            {span.status && (
              <Tag color={STATUS_MAP[span.status]?.color || 'default'}>
                {STATUS_MAP[span.status]?.text || span.status}
              </Tag>
            )}
            <span style={{ fontSize: 12, color: '#8c8c8c' }}>{formatDuration(span.durationMs)}</span>
          </Space>
        </div>
        {(span.inputSummary || span.outputSummary) && (
          <Collapse ghost style={{ marginTop: 8 }}>
            {span.inputSummary && (
              <Collapse.Panel header="输入摘要" key="in">
                <Paragraph style={{ marginBottom: 0, fontFamily: 'monospace', fontSize: 12 }}>
                  {span.inputSummary}
                </Paragraph>
              </Collapse.Panel>
            )}
            {span.outputSummary && (
              <Collapse.Panel header="输出摘要" key="out">
                <Paragraph style={{ marginBottom: 0, fontFamily: 'monospace', fontSize: 12 }}>
                  {span.outputSummary}
                </Paragraph>
              </Collapse.Panel>
            )}
          </Collapse>
        )}
      </div>
    );
  };

  const renderRound = (round: { roundIndex: number; spans: SpanRecord[] }) => {
    const firstSpan = round.spans[0];
    const title = firstSpan
      ? firstSpan.spanType === 'LLM_CALL'
        ? (firstSpan.displayName || firstSpan.name || firstSpan.modelName || 'LLM')
        : firstSpan.spanType === 'TOOL_CALL'
        ? firstSpan.name
        : firstSpan.displayName || firstSpan.name
      : '';
    const roundLabel = round.roundIndex === -1 ? '初始化' : `第 ${round.roundIndex + 1} 轮`;
    return (
      <div
        key={round.roundIndex}
        style={{
          marginBottom: 16,
          border: '1px solid #f0f0f0',
          borderRadius: 8,
          padding: 12,
          background: '#fafafa',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            marginBottom: 12,
            paddingBottom: 8,
            borderBottom: '1px dashed #e0e0e0',
          }}
        >
          <Tag color={round.roundIndex === -1 ? 'geekblue' : 'blue'} style={{ fontWeight: 600 }}>
            {roundLabel}
          </Tag>
          <Text strong>{title}</Text>
          <Text type="secondary" style={{ fontSize: 12 }}>
            ({round.spans.length} 步)
          </Text>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {round.spans.map((span) => {
            if (span.spanType === 'LLM_CALL') return renderLlmCard(span);
            if (span.spanType === 'TOOL_CALL') return renderToolCard(span);
            return renderGenericCard(span);
          })}
        </div>
      </div>
    );
  };

  const renderSpanDetail = (span: SpanRecord) => {
    const typeColor = TYPE_COLORS[span.spanType] || 'default';
    const statusTag = STATUS_MAP[span.status] || { color: 'default', text: span.status };
    return (
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <Tag color={typeColor}>{span.spanType}</Tag>
          <Text strong>{span.name}</Text>
          <Tag color={statusTag.color}>{statusTag.text}</Tag>
        </div>

        <Descriptions column={2} size="small" bordered labelStyle={{ background: '#f5f5f5' }}>
          <Descriptions.Item label="Span ID" span={2}>
            <Text copyable style={{ fontSize: 12, fontFamily: 'monospace' }}>
              {span.spanId}
            </Text>
          </Descriptions.Item>
          <Descriptions.Item label="耗时">
            <ClockCircleOutlined /> {formatDuration(span.durationMs)}
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={statusTag.color}>{statusTag.text}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="开始时间">{formatTime(span.startTime)}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{formatTime(span.endTime)}</Descriptions.Item>
          <Descriptions.Item label="Token">
            输入: {span.tokenInput ?? 0} / 输出: {span.tokenOutput ?? 0}
          </Descriptions.Item>
          </Descriptions>

        {span.inputSummary && (
          <div style={{ marginTop: 12 }}>
            <Text strong>输入摘要</Text>
            <Paragraph
              style={{
                marginTop: 4,
                padding: 8,
                background: '#f6f8fa',
                borderRadius: 4,
                fontFamily: 'monospace',
                fontSize: 12,
                maxHeight: 120,
                overflow: 'auto',
                marginBottom: 0,
              }}
            >
              {span.inputSummary}
            </Paragraph>
          </div>
        )}

        {span.outputSummary && (
          <div style={{ marginTop: 12 }}>
            <Text strong>输出摘要</Text>
            <Paragraph
              style={{
                marginTop: 4,
                padding: 8,
                background: '#f6f8fa',
                borderRadius: 4,
                fontFamily: 'monospace',
                fontSize: 12,
                maxHeight: 120,
                overflow: 'auto',
                marginBottom: 0,
              }}
            >
              {span.outputSummary}
            </Paragraph>
          </div>
        )}

        {span.errorMsg && (
          <div style={{ marginTop: 12 }}>
            <Text strong type="danger">
              <WarningOutlined /> 错误信息
            </Text>
            <Paragraph
              style={{
                marginTop: 4,
                padding: 8,
                background: '#fff1f0',
                borderRadius: 4,
                fontFamily: 'monospace',
                fontSize: 12,
                maxHeight: 120,
                overflow: 'auto',
                marginBottom: 0,
                color: '#ff4d4f',
              }}
            >
              {span.errorMsg}
            </Paragraph>
          </div>
        )}

        {span.meta && Object.keys(span.meta).length > 0 && (
          <div style={{ marginTop: 12 }}>
            <Text strong>Meta 数据</Text>
            <div
              style={{
                marginTop: 4,
                padding: 8,
                background: '#f6f8fa',
                borderRadius: 4,
                maxHeight: 200,
                overflow: 'auto',
              }}
            >
              <pre
                style={{
                  margin: 0,
                  padding: 8,
                  background: '#f6f8fa',
                  borderRadius: 4,
                  fontFamily: 'monospace',
                  fontSize: 12,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-all',
                }}
              >
                {JSON.stringify(span.meta, null, 2)}
              </pre>
            </div>
          </div>
        )}
      </div>
    );
  };

  return (
    <Drawer
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <ThunderboltOutlined style={{ color: '#1677ff' }} />
          <span>执行链路详情 - 工作流视图</span>
        </div>
      }
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={<Button onClick={onClose}>关闭</Button>}
    >
      <Spin spinning={loading}>
        {!detail && !loading && (
          <Empty description="暂无数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        )}

        {detail && (
          <div>
            {renderOverview(detail.trace)}

            <Divider orientation="left" plain style={{ fontSize: 13 }}>
              工作流视图（按轮次）
            </Divider>

            {rounds.length === 0 ? (
              <Empty description="暂无工作流数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ) : (
              <div>{rounds.map((r) => renderRound(r))}</div>
            )}

            <Divider orientation="left" plain style={{ fontSize: 13, marginTop: 24 }}>
              Span 瀑布图
            </Divider>

            <SpanWaterfall
              spans={detail.spans}
              selectedSpanId={selectedSpanId}
              onSelect={setSelectedSpanId}
            />

            <Divider orientation="left" plain style={{ fontSize: 13, marginTop: 24 }}>
              Span 详情
            </Divider>

            {currentSpan ? (
              renderSpanDetail(currentSpan)
            ) : (
              <Empty description="请选择一个 Span" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            )}
          </div>
        )}
      </Spin>
    </Drawer>
  );
};

export default TraceDetailDrawer;
