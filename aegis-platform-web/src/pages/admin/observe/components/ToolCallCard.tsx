import React, { useState } from 'react';
import { Card, Tag, Typography, Collapse, Space, Tooltip, message, Button } from 'antd';
import {
  ToolOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  CopyOutlined,
  ScissorOutlined,
  ExpandOutlined,
  CompressOutlined,
  FileTextOutlined,
} from '@ant-design/icons';
import type { StepDetail } from '@/api/observe';
import { formatDuration, formatLength } from '@/utils/format';
import { safeJsonParse } from '@/utils/number';

const { Text, Paragraph } = Typography;

interface ToolCallCardProps {
  step: StepDetail;
  stepNumber: number;
}

function looksLikeJson(text: string): boolean {
  const trimmed = text.trim();
  return (trimmed.startsWith('{') && trimmed.endsWith('}')) || 
         (trimmed.startsWith('[') && trimmed.endsWith(']'));
}

function tryFormatJson(text: string): string {
  if (!text) return text;
  if (!looksLikeJson(text)) return text;
  const parsed = safeJsonParse(text);
  return parsed != null ? JSON.stringify(parsed, null, 2) : text;
}

function getRawResult(result: unknown): string {
  if (result == null) return '';
  if (typeof result === 'string') return result;
  try {
    return JSON.stringify(result, null, 2);
  } catch {
    return String(result);
  }
}

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  TIMEOUT: { color: 'warning', text: '超时' },
};

const ToolCallCard: React.FC<ToolCallCardProps> = ({ step, stepNumber }) => {
  const [showFullResult, setShowFullResult] = useState(false);
  const [showFullArgs, setShowFullArgs] = useState(false);
  const statusInfo = STATUS_MAP[step.status] || { color: 'default', text: step.status };
  const isSuccess = step.status === 'SUCCESS';
  
  const isResultTruncated = step.toolResultTruncated ?? false;
  const resultOriginalLen = step.toolResultOriginalLength ?? 0;
  
  const hasResult = step.toolResult != null || !!step.toolResultPreview;
  const hasArgs = !!(step.toolArguments || step.toolArgumentsJson);
  
  // Get formatted result text for display
  const getDisplayResultText = (): string => {
    const raw = showFullResult 
      ? getRawResult(step.toolResult) 
      : (step.toolResultPreview || getRawResult(step.toolResult));
    return tryFormatJson(raw);
  };
  
  // Get formatted args text
  const getDisplayArgsText = (): string => {
    const raw = showFullArgs 
      ? (step.toolArgumentsJson || getRawResult(step.toolArguments))
      : (step.toolArgumentsJson || getRawResult(step.toolArguments));
    return tryFormatJson(raw);
  };
  
  const handleCopy = (text: string, label: string) => {
    if (!text) {
      message.warning('没有可复制的内容');
      return;
    }
    navigator.clipboard.writeText(text).then(() => {
      message.success(`${label}已复制到剪贴板`);
    }).catch(() => {
      message.error('复制失败');
    });
  };
  
  const resultText = getDisplayResultText();
  const argsText = getDisplayArgsText();
  const isArgsLong = (step.toolArgumentsJson?.length ?? 0) > 500 || (step.toolArguments ? JSON.stringify(step.toolArguments).length > 500 : false);
  
  // Content-based truncation for display (not the same as API truncation)
  const DISPLAY_MAX = 3000;  // max chars before UI truncation
  const isResultUiTruncated = !showFullResult && resultText.length > DISPLAY_MAX;
  const displayResult = isResultUiTruncated ? resultText.substring(0, DISPLAY_MAX) + '\n... (内容过长，已折叠)' : resultText;

  return (
    <Card
      size="small"
      style={{
        border: `2px solid ${isSuccess ? '#52c41a' : '#ff4d4f'}`,
        background: '#fffbe6',
        borderRadius: 8,
        marginBottom: 12,
      }}
      bodyStyle={{ padding: 16 }}
    >
      {/* Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          marginBottom: 12,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Tag color="orange" style={{ fontWeight: 600 }}>
            <ToolOutlined /> 工具调用 #{stepNumber}
          </Tag>
          <Text strong style={{ fontSize: 14, color: '#d48806' }}>
            {step.displayName || step.name}
          </Text>
        </div>
        <Space>
          <Tag
            color={statusInfo.color}
            icon={isSuccess ? <CheckCircleOutlined /> : <CloseCircleOutlined />}
          >
            {statusInfo.text}
          </Tag>
          <Tag color="default" icon={<ClockCircleOutlined />}>
            {formatDuration(step.durationMs)}
          </Tag>
        </Space>
      </div>

      {/* Arguments */}
      {hasArgs && (
        <div
          style={{
            padding: '8px 12px',
            background: '#fff',
            borderRadius: 6,
            marginBottom: 12,
          }}
        >
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <Space size={8}>
              <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>
                🔧 工具参数
              </Text>
              <Text type="secondary" style={{ fontSize: 11 }}>
                ({formatLength(argsText.length)})
              </Text>
              {looksLikeJson(argsText) && <Tag color="purple" style={{ fontSize: 10 }}>JSON</Tag>}
            </Space>
            <Space size={8}>
              <Tooltip title="复制参数">
                <a
                  onClick={() => handleCopy(argsText, '工具参数')}
                  style={{ fontSize: 12 }}
                >
                  <CopyOutlined /> 复制
                </a>
              </Tooltip>
              {isArgsLong && (
                <a
                  onClick={() => setShowFullArgs(!showFullArgs)}
                  style={{ fontSize: 12 }}
                >
                  {showFullArgs ? <><CompressOutlined /> 收起</> : <><ExpandOutlined /> 展开</>}
                </a>
              )}
            </Space>
          </div>
          <Paragraph
            style={{
              padding: 10,
              background: '#f6f8fa',
              borderRadius: 4,
              fontSize: 12,
              maxHeight: showFullArgs ? 400 : 200,
              overflow: 'auto',
              marginBottom: 0,
              fontFamily: 'monospace',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
              lineHeight: 1.5,
            }}
          >
            {argsText}
          </Paragraph>
        </div>
      )}

      {/* Result */}
      {hasResult && (
        <Collapse
          ghost
          items={[
            {
              key: 'result',
              label: (
                <span style={{ fontWeight: 500, color: isSuccess ? '#52c41a' : '#ff4d4f' }}>
                  <FileTextOutlined /> 工具结果
                  {resultOriginalLen > 0 && (
                    <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                      (原始: {formatLength(resultOriginalLen)})
                    </Text>
                  )}
                  {isResultTruncated && (
                    <Tag color="warning" style={{ fontSize: 11, marginLeft: 4 }}>
                      <ScissorOutlined /> 后端截断
                    </Tag>
                  )}
                </span>
              ),
              children: (
                <div>
                  {/* Result action bar */}
                  <div style={{ 
                    display: 'flex', 
                    justifyContent: 'space-between', 
                    alignItems: 'center',
                    marginBottom: 8,
                    padding: '6px 10px',
                    background: '#fafafa',
                    borderRadius: 4,
                    border: '1px solid #f0f0f0',
                  }}>
                    <Space size={8}>
                      {isResultTruncated && (
                        <Tag color="warning" style={{ fontSize: 11 }}>
                          <ScissorOutlined /> 
                          {showFullResult 
                            ? `已展开完整内容 (${formatLength(resultText.length)})` 
                            : `后端截断 (显示 ${formatLength(resultText.length)})`}
                        </Tag>
                      )}
                      {!isResultTruncated && resultOriginalLen > 0 && (
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          完整输出: {formatLength(resultOriginalLen)}
                        </Text>
                      )}
                      {isResultUiTruncated && (
                        <Tag color="blue" style={{ fontSize: 11 }}>
                          UI 折叠 (显示前 {formatLength(DISPLAY_MAX)})
                        </Tag>
                      )}
                    </Space>
                    <Space size={8}>
                      <Button 
                        size="small" 
                        icon={<CopyOutlined />}
                        onClick={() => {
                          const fullText = getRawResult(step.toolResult);
                          handleCopy(fullText, '工具结果');
                        }}
                      >
                        复制
                      </Button>
                      {(isResultTruncated || isResultUiTruncated) && (
                        <Button
                          size="small"
                          type="link"
                          icon={showFullResult ? <CompressOutlined /> : <ExpandOutlined />}
                          onClick={() => setShowFullResult(!showFullResult)}
                        >
                          {showFullResult ? '收起' : '展开全部'}
                        </Button>
                      )}
                    </Space>
                  </div>
                  
                  {/* Result content */}
                  <Paragraph
                    style={{
                      padding: 10,
                      background: isSuccess ? '#f6ffed' : '#fff1f0',
                      borderRadius: 4,
                      fontSize: 12,
                      maxHeight: showFullResult ? 600 : 250,
                      overflow: 'auto',
                      marginBottom: 0,
                      fontFamily: looksLikeJson(displayResult) ? 'monospace' : 'inherit',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-all',
                      lineHeight: 1.5,
                    }}
                  >
                    {displayResult}
                  </Paragraph>
                  
                  {/* Expand full button when UI-truncated */}
                  {isResultUiTruncated && !showFullResult && (
                    <div style={{ 
                      textAlign: 'center', 
                      marginTop: 8,
                    }}>
                      <Button 
                        size="small" 
                        type="link"
                        onClick={() => setShowFullResult(true)}
                      >
                        <ExpandOutlined /> 展开完整结果 ({formatLength(resultText.length)})
                      </Button>
                    </div>
                  )}
                </div>
              ),
            },
          ]}
        />
      )}

      {/* Extra meta */}
      {step.extraMeta && Object.keys(step.extraMeta).length > 0 && (
        <Collapse
          ghost
          style={{ marginTop: 8 }}
          items={[
            {
              key: 'meta',
              label: <span style={{ fontSize: 12 }}>元数据 ({Object.keys(step.extraMeta).length} 项)</span>,
              children: (
                <pre
                  style={{
                    padding: 10,
                    background: '#f6f8fa',
                    borderRadius: 4,
                    fontSize: 12,
                    maxHeight: 200,
                    overflow: 'auto',
                    margin: 0,
                    fontFamily: 'monospace',
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-all',
                  }}
                >
                  {JSON.stringify(step.extraMeta, null, 2)}
                </pre>
              ),
            },
          ]}
        />
      )}
    </Card>
  );
};

export default ToolCallCard;
