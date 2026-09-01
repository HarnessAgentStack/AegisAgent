import React, { useState } from 'react';
import { Card, Tag, Typography, Collapse, Space, Tooltip, message, Empty } from 'antd';
import {
  CodeOutlined,
  ClockCircleOutlined,
  InfoCircleOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  CopyOutlined,
  ScissorOutlined,
  WarningOutlined,
  UserOutlined,
  RobotOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import type { StepDetail } from '@/api/observe';
import { formatDuration, formatLength } from '@/utils/format';

const { Text, Paragraph } = Typography;

interface LlmCallFlowCardProps {
  step: StepDetail;
  stepNumber: number;
}

function formatJsonPretty(obj: unknown): string {
  if (obj == null) return '';
  try {
    return JSON.stringify(obj, null, 2);
  } catch {
    return String(obj);
  }
}

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  TIMEOUT: { color: 'warning', text: '超时' },
};

// Role color and icon mapping
const ROLE_CONFIG: Record<string, { color: string; bg: string; icon: React.ReactNode; label: string }> = {
  system: { color: 'default', bg: '#f0f5ff', icon: <SettingOutlined />, label: '系统' },
  user: { color: 'green', bg: '#f6ffed', icon: <UserOutlined />, label: '用户' },
  assistant: { color: 'blue', bg: '#e6f4ff', icon: <RobotOutlined />, label: '助手' },
  tool: { color: 'orange', bg: '#fff7e6', icon: <CodeOutlined />, label: '工具' },
};

const LlmCallFlowCard: React.FC<LlmCallFlowCardProps> = ({ step, stepNumber }) => {
  const [showFullResponse, setShowFullResponse] = useState(false);
  const [expandedMessages, setExpandedMessages] = useState(false);
  const [expandedTools, setExpandedTools] = useState(false);
  const statusInfo = STATUS_MAP[step.status] || { color: 'default', text: step.status };

  const hasMessages = step.requestMessages && step.requestMessages.length > 0;
  const hasTools = step.requestTools && step.requestTools.length > 0;
  const hasRequestData = hasMessages || hasTools;
  
  // Request summary
  const reqSummary = step.requestSummary as Record<string, number> | undefined;
  const totalMessages = step.requestMessagesOriginalCount ?? reqSummary?.messageCount ?? step.requestMessages?.length ?? 0;
  const systemCount = reqSummary?.systemCount ?? 0;
  const toolCount = reqSummary?.toolCount ?? step.requestTools?.length ?? 0;
  const isMessagesTruncated = step.requestMessagesTruncated ?? false;
  const isToolsTruncated = step.requestToolsTruncated ?? false;
  const totalTools = step.requestToolsOriginalCount ?? toolCount;
  const isFallbackData = step.messagesFallback ?? false;

  // Response
  const responseText = showFullResponse && step.responseText ? step.responseText : (step.responseTextPreview || step.responseText || '');
  const isResponseTruncated = step.responseTextTruncated ?? false;
  const responseOriginalLen = step.responseTextOriginalLength ?? responseText.length;
  const reasoningText = step.reasoningPreview;

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

  // Determine which messages to show
  const displayMessages = hasMessages
    ? (isMessagesTruncated && !expandedMessages
        ? step.requestMessages!.slice(-10)  // Show last 10 when truncated
        : step.requestMessages!)
    : [];

  const hiddenMessageCount = isMessagesTruncated ? Math.max(0, totalMessages - displayMessages.length) : 0;

  // Check if content looks like JSON
  const looksLikeJson = (text: string): boolean => {
    const trimmed = text.trim();
    return (trimmed.startsWith('{') && trimmed.endsWith('}')) || 
           (trimmed.startsWith('[') && trimmed.endsWith(']'));
  };

  const getContentStr = (content: unknown): string => {
    if (typeof content === 'string') return content;
    if (content != null) return JSON.stringify(content, null, 2);
    return '';
  };

  return (
    <Card
      size="small"
      style={{
        border: `2px solid ${statusInfo.color === 'success' ? '#52c41a' : statusInfo.color === 'error' ? '#ff4d4f' : '#1677ff'}`,
        background: '#f0f7ff',
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
          <Tag color="blue" style={{ fontWeight: 600 }}>
            <CodeOutlined /> LLM 调用 #{stepNumber}
          </Tag>
          <Text strong style={{ fontSize: 14 }}>
            {step.displayName || step.name || step.modelName || '模型调用'}
          </Text>
          {step.modelName && (
            <Tag color="geekblue" style={{ fontWeight: 500 }}>
              {step.modelName}
            </Tag>
          )}
          {isFallbackData && (
            <Tag color="warning" style={{ fontSize: 11 }}>
              <WarningOutlined /> 基础信息
            </Tag>
          )}
        </div>
        <Space>
          <Tag color={statusInfo.color}>{statusInfo.text}</Tag>
          <Tag color="blue" icon={<ClockCircleOutlined />}>
            {formatDuration(step.durationMs)}
          </Tag>
        </Space>
      </div>

      {/* Metrics row */}
      <div
        style={{
          display: 'flex',
          gap: 16,
          padding: '8px 12px',
          background: '#fff',
          borderRadius: 6,
          marginBottom: 12,
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>输入</Text>
          <Text strong style={{ color: '#722ed1' }}>{step.tokenInput ?? 0}</Text>
        </div>
        <div style={{ width: 1, background: '#e0e0e0' }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <Text type="secondary" style={{ fontSize: 12 }}>输出</Text>
          <Text strong style={{ color: '#1677ff' }}>{step.tokenOutput ?? 0}</Text>
        </div>
        {step.cachedTokens != null && step.cachedTokens > 0 && (
          <>
            <div style={{ width: 1, background: '#e0e0e0' }} />
            <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>缓存命中</Text>
              <Text strong style={{ color: '#52c41a' }}>{step.cachedTokens}</Text>
            </div>
          </>
        )}
        </div>

      {/* Request & Response */}
      <Collapse
        ghost
        items={[
          {
            key: 'request',
            label: (
              <span style={{ fontWeight: 500, color: '#1677ff' }}>
                <InfoCircleOutlined /> 请求详情
                {hasRequestData && (
                  <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                    ({totalMessages} 条消息{isMessagesTruncated ? ` (已截断, 显示 ${displayMessages.length})` : ''}, {totalTools} 个工具
                    {isToolsTruncated ? ` (已截断)` : ''}
                    {systemCount > 0 && `, ${systemCount} 条系统提示`})
                  </Text>
                )}
                {!hasRequestData && (
                  <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                    (暂无请求详情数据)
                  </Text>
                )}
              </span>
            ),
            children: (
              <div>
                {!hasRequestData && (
                  <div style={{ padding: '20px 0', textAlign: 'center' }}>
                    <Empty 
                      description={
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {isFallbackData ? '仅显示用户输入（SDK 限制）' : '暂无请求详情数据'}
                        </Text>
                      }
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                    />
                  </div>
                )}
                
                {/* Messages Section */}
                {hasMessages && (
                  <div style={{ marginBottom: hasTools ? 16 : 0 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                      <Space size={8}>
                        <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>
                          📨 消息列表 ({totalMessages})
                        </Text>
                        {systemCount > 0 && (
                          <Tag color="default" style={{ fontSize: 11 }}>
                            系统: {systemCount}
                          </Tag>
                        )}
                        {isFallbackData && (
                          <Tag color="warning" style={{ fontSize: 11 }}>
                            <WarningOutlined /> 仅用户消息
                          </Tag>
                        )}
                      </Space>
                      <Space size={8}>
                        {isMessagesTruncated && (
                          <a
                            onClick={() => setExpandedMessages(!expandedMessages)}
                            style={{ fontSize: 12 }}
                          >
                            {expandedMessages ? '收起' : `展开全部 (${totalMessages} 条)`}
                          </a>
                        )}
                        <Tooltip title="复制全部消息">
                          <a
                            onClick={() => {
                              const allText = displayMessages.map((m, i) => 
                                `[${i}] ${m.role || 'unknown'}: ${getContentStr(m.content)}`
                              ).join('\n\n');
                              handleCopy(allText, '消息列表');
                            }}
                            style={{ fontSize: 12 }}
                          >
                            <CopyOutlined /> 复制
                          </a>
                        </Tooltip>
                      </Space>
                    </div>
                    
                    <div style={{ 
                      maxHeight: expandedMessages ? 500 : 300, 
                      overflow: 'auto',
                      paddingRight: 4
                    }}>
                      {displayMessages.map((msg, idx) => {
                        const role = (msg.role || 'user') as string;
                        const config = ROLE_CONFIG[role] || ROLE_CONFIG.user;
                        const content = msg.content;
                        const isContentTruncated = msg.contentTruncated as boolean | undefined;
                        const origLen = msg.contentOriginalLength as number | undefined;
                        const contentStr = getContentStr(content);
                        const isJson = typeof content === 'string' ? looksLikeJson(content) : false;
                        
                        return (
                          <div
                            key={idx}
                            style={{
                              padding: '8px 12px',
                              background: config.bg,
                              borderRadius: 6,
                              marginBottom: 8,
                              fontSize: 12,
                              borderLeft: `3px solid ${
                                role === 'system' ? '#1677ff' :
                                role === 'user' ? '#52c41a' :
                                role === 'assistant' ? '#1890ff' : '#fa8c16'
                              }`,
                            }}
                          >
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                              <Space size={6}>
                                <Tag color={config.color} style={{ fontSize: 11, margin: 0 }}>
                                  {config.icon} {config.label}
                                </Tag>
                                {origLen != null && (
                                  <Text type="secondary" style={{ fontSize: 11 }}>
                                    {formatLength(origLen)}
                                    {isContentTruncated && <ScissorOutlined style={{ marginLeft: 4, color: '#fa8c16' }} />}
                                  </Text>
                                )}
                                {isJson && <Tag color="purple" style={{ fontSize: 10 }}>JSON</Tag>}
                              </Space>
                              <Tooltip title="复制">
                                <a
                                  onClick={() => handleCopy(contentStr, `${role} 消息`)}
                                  style={{ fontSize: 11 }}
                                >
                                  <CopyOutlined />
                                </a>
                              </Tooltip>
                            </div>
                            <Paragraph
                              style={{
                                margin: 0,
                                fontSize: 12,
                                whiteSpace: isJson ? 'pre-wrap' : 'pre-wrap',
                                wordBreak: 'break-all',
                                maxHeight: 120,
                                overflow: 'auto',
                                fontFamily: isJson ? 'monospace' : 'inherit',
                                background: isJson ? '#f6f8fa' : 'transparent',
                                padding: isJson ? 8 : 0,
                                borderRadius: isJson ? 4 : 0,
                              }}
                              ellipsis={{ rows: isJson ? 5 : 4, expandable: true, symbol: '展开' }}
                            >
                              {contentStr}
                            </Paragraph>
                          </div>
                        );
                      })}
                      {hiddenMessageCount > 0 && !expandedMessages && (
                        <div style={{ 
                          textAlign: 'center', 
                          padding: 8,
                          background: '#fafafa',
                          borderRadius: 4,
                          marginTop: 4,
                        }}>
                          <a onClick={() => setExpandedMessages(true)} style={{ fontSize: 12 }}>
                            ... 省略 {hiddenMessageCount} 条消息，点击展开全部
                          </a>
                        </div>
                      )}
                    </div>
                  </div>
                )}
                
                {/* Tools Section */}
                {hasTools && (
                  <div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                      <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>
                        🔧 可用工具 ({totalTools})
                        {isToolsTruncated && <ScissorOutlined style={{ marginLeft: 4, color: '#fa8c16' }} />}
                      </Text>
                      {isToolsTruncated && (
                        <a
                          onClick={() => setExpandedTools(!expandedTools)}
                          style={{ fontSize: 12 }}
                        >
                          {expandedTools ? '收起' : `展开全部 (${totalTools} 个)`}
                        </a>
                      )}
                    </div>
                    <div style={{ 
                      display: 'flex', 
                      flexWrap: 'wrap', 
                      gap: 6,
                      maxHeight: expandedTools ? 300 : 150,
                      overflow: 'auto',
                      padding: 4,
                      background: '#fafafa',
                      borderRadius: 4,
                    }}>
                      {step.requestTools!.map((tool, idx) => {
                        const toolObj = tool as Record<string, unknown>;
                        const toolFunc = toolObj.function as Record<string, unknown> | undefined;
                        const toolName = typeof tool === 'string' 
                          ? tool 
                          : String(toolObj.name ?? toolFunc?.name ?? 'tool');
                        const toolDesc = typeof tool === 'string' 
                          ? '' 
                          : String(toolObj.description ?? toolFunc?.description ?? '');
                        return (
                          <Tooltip 
                            key={idx} 
                            title={
                              <div style={{ maxWidth: 300 }}>
                                <div style={{ fontWeight: 600 }}>{toolName}</div>
                                {toolDesc && <div style={{ marginTop: 4, fontSize: 12 }}>{toolDesc}</div>}
                                <div style={{ marginTop: 4 }}>
                                  <a onClick={() => {
                                    const toolStr = typeof tool === 'string' ? tool : JSON.stringify(tool, null, 2);
                                    handleCopy(toolStr, `工具 ${toolName}`);
                                  }}>
                                    复制详情
                                  </a>
                                </div>
                              </div>
                            }
                          >
                            <Tag 
                              color="orange" 
                              style={{ 
                                fontSize: 12, 
                                cursor: 'pointer',
                                padding: '4px 8px',
                                borderRadius: 4,
                              }}
                            >
                              {toolName}
                            </Tag>
                          </Tooltip>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            ),
          },
          {
            key: 'response',
            label: (
              <span style={{ fontWeight: 500, color: '#52c41a' }}>
                <EyeOutlined /> 响应详情
                {(responseText || reasoningText) && (
                  <Text type="secondary" style={{ fontSize: 12, marginLeft: 8 }}>
                    ({formatLength(responseOriginalLen)})
                    {isResponseTruncated && <ScissorOutlined style={{ marginLeft: 4, color: '#fa8c16' }} />}
                  </Text>
                )}
              </span>
            ),
            children: (
              <div>
                {reasoningText && (
                  <div style={{ marginBottom: 12 }}>
                    <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>
                      🤔 思考过程
                    </Text>
                    <Paragraph
                      style={{
                        marginTop: 4,
                        padding: 8,
                        background: '#fffbe6',
                        borderRadius: 4,
                        fontSize: 12,
                        maxHeight: 150,
                        overflow: 'auto',
                        marginBottom: 0,
                        whiteSpace: 'pre-wrap',
                      }}
                    >
                      {reasoningText}
                    </Paragraph>
                  </div>
                )}
                
                {responseText && (
                  <div style={{ marginBottom: 12 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <Space size={8}>
                        <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>
                          💬 模型输出
                        </Text>
                        {isResponseTruncated && !showFullResponse && (
                          <Tag color="warning" style={{ fontSize: 11 }}>
                            <ScissorOutlined /> 已截断 (显示 {formatLength(responseText.length)})
                          </Tag>
                        )}
                        {isResponseTruncated && showFullResponse && (
                          <Tag color="warning" style={{ fontSize: 11 }}>
                            <ScissorOutlined /> 超长输出
                          </Tag>
                        )}
                      </Space>
                      <Space size={8}>
                        <Tooltip title="复制输出">
                          <a
                            onClick={() => handleCopy(responseText, '模型输出')}
                            style={{ fontSize: 12 }}
                          >
                            <CopyOutlined /> 复制
                          </a>
                        </Tooltip>
                        <Tooltip title={showFullResponse ? '显示摘要' : '显示完整'}>
                          <a
                            onClick={() => setShowFullResponse(!showFullResponse)}
                            style={{ fontSize: 12 }}
                          >
                            {showFullResponse ? <EyeInvisibleOutlined /> : <EyeOutlined />}{' '}
                            {showFullResponse ? '摘要' : '完整'}
                          </a>
                        </Tooltip>
                      </Space>
                    </div>
                    <Paragraph
                      style={{
                        marginTop: 4,
                        padding: 10,
                        background: '#f6f8fa',
                        borderRadius: 6,
                        fontSize: 12,
                        maxHeight: 400,
                        overflow: 'auto',
                        marginBottom: 0,
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-all',
                        fontFamily: looksLikeJson(responseText) ? 'monospace' : 'inherit',
                        lineHeight: 1.6,
                      }}
                    >
                      {responseText}
                    </Paragraph>
                    {isResponseTruncated && !showFullResponse && (
                      <div style={{ textAlign: 'center', marginTop: 8 }}>
                        <a 
                          onClick={() => setShowFullResponse(true)}
                          style={{ fontSize: 12, color: '#fa8c16' }}
                        >
                          展开完整内容 ({formatLength(responseOriginalLen)})
                        </a>
                      </div>
                    )}
                  </div>
                )}
                
                {step.responseToolCalls && step.responseToolCalls.length > 0 && (
                  <div>
                    <Text type="secondary" style={{ fontSize: 12, fontWeight: 500 }}>
                      🔗 本次调用产生的工具调用 ({step.responseToolCalls.length})
                    </Text>
                    <div style={{ marginTop: 6 }}>
                      {step.responseToolCalls.map((tc, idx) => {
                        const tcObj = tc as Record<string, unknown>;
                        const tcFunc = tcObj.function as Record<string, unknown> | undefined;
                        const toolName = String(tcObj.name ?? tcFunc?.name ?? 'tool');
                        const argsStr = tcObj.arguments ? formatJsonPretty(tcObj.arguments) : '';
                        const isArgsLong = argsStr.length > 200;
                        const displayArgs = isArgsLong ? argsStr.substring(0, 200) + '...' : argsStr;
                        return (
                          <div
                            key={idx}
                            style={{
                              padding: '8px 12px',
                              background: '#fff7e6',
                              borderRadius: 6,
                              marginBottom: 6,
                              fontSize: 12,
                              borderLeft: '3px solid #fa8c16',
                            }}
                          >
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                              <Tag color="orange" style={{ marginRight: 0 }}>
                                {toolName}
                              </Tag>
                              <Tooltip title="复制参数">
                                <a
                                  onClick={() => handleCopy(argsStr, `工具 ${toolName} 参数`)}
                                  style={{ fontSize: 11 }}
                                >
                                  <CopyOutlined /> 复制
                                </a>
                              </Tooltip>
                            </div>
                            <div
                              style={{
                                fontFamily: 'monospace',
                                background: '#fff',
                                padding: 6,
                                borderRadius: 4,
                                fontSize: 11,
                                maxHeight: 80,
                                overflow: 'auto',
                                whiteSpace: 'pre-wrap',
                                wordBreak: 'break-all',
                              }}
                            >
                              {displayArgs}
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            ),
          },
        ]}
      />
    </Card>
  );
};

export default LlmCallFlowCard;
