/**
 * @file 在线 API 测试组件
 * @description 类似 Postman 的在线 API 测试工具，支持请求发送、响应查看。
 * @author aegis
 * @since 2.0.0
 */
import React, { useState } from 'react';
import { App, Button, Card, Input, Select, Space, Tag, Typography } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { testAgentApiInvoke } from '@/api/agentApi';
import type { AgentApiInvokeResponse } from '@/types/agentApi';
import { safeJsonParse } from '@/utils/number';

const { Text, Paragraph } = Typography;

interface ApiTesterProps {
  apiId: string;
  agentId: string;
  apiPath?: string;
  httpMethod?: string;
}

const ApiTester: React.FC<ApiTesterProps> = ({ apiId, agentId, apiPath, httpMethod }) => {
  const { message } = App.useApp();
  const [apiKey, setApiKey] = useState('');
  const [requestBody, setRequestBody] = useState(
    JSON.stringify({ input: '你好，请介绍一下自己' }, null, 2),
  );
  const [method, setMethod] = useState(httpMethod || 'POST');
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<AgentApiInvokeResponse | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [jsonError, setJsonError] = useState<string | null>(null);

  const validateJson = (text: string): boolean => {
    try {
      JSON.parse(text);
      setJsonError(null);
      return true;
    } catch (e) {
      setJsonError((e as Error).message);
      return false;
    }
  };

  const handleBodyChange = (value: string) => {
    setRequestBody(value);
    validateJson(value);
  };

  const handleSend = async () => {
    if (!apiKey) {
      message.warning('请先填写 API Key');
      return;
    }
    if (!validateJson(requestBody)) {
      message.error('请求体 JSON 格式不正确');
      return;
    }

    setLoading(true);
    setResponse(null);
    setErrorMsg(null);

    try {
      const body = safeJsonParse<Record<string, unknown>>(requestBody);
      if (!body) throw new Error('Invalid JSON');
      if (agentId && !body.agentId) {
        body.agentId = String(agentId);
      }
      const result = await testAgentApiInvoke(apiId, body, apiKey);
      setResponse(result);
      message.success('请求成功');
    } catch (err) {
      const msg = err instanceof Error ? err.message : '请求失败';
      setErrorMsg(msg);
    } finally {
      setLoading(false);
    }
  };

  const statusColor = response?.status === 'SUCCESS' ? 'green' : 'red';

  return (
    <Card
      title="🧪 在线测试"
      extra={
        <Space>
          <Tag color="blue">
            {method} {apiPath || '/api/v1/agent/{code}/invoke'}
          </Tag>
        </Space>
      }
    >
      <div style={{ marginBottom: 16 }}>
        <Text strong>请求配置</Text>
      </div>

      <div style={{ marginBottom: 12 }}>
        <Space.Compact style={{ width: '100%' }}>
          <Select
            value={method}
            onChange={setMethod}
            style={{ width: 100 }}
            options={[
              { value: 'POST', label: 'POST' },
              { value: 'GET', label: 'GET' },
            ]}
          />
          <Input
            value={apiPath || '/api/v1/agent/{code}/invoke'}
            disabled
            style={{ flex: 1 }}
          />
        </Space.Compact>
      </div>

      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Headers
        </Text>
        <Input
          placeholder="X-API-Key"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          style={{ marginTop: 4 }}
        />
      </div>

      <div style={{ marginBottom: 12 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          Body (JSON)
        </Text>
        <Input.TextArea
          value={requestBody}
          onChange={(e) => handleBodyChange(e.target.value)}
          autoSize={{ minRows: 6, maxRows: 12 }}
          style={{
            marginTop: 4,
            fontFamily: 'monospace',
            fontSize: 12,
          }}
        />
        {jsonError && (
          <Text type="danger" style={{ fontSize: 12 }}>
            JSON 格式错误: {jsonError}
          </Text>
        )}
      </div>

      <Button
        type="primary"
        icon={<SendOutlined />}
        loading={loading}
        onClick={() => void handleSend()}
        style={{ marginBottom: 16 }}
      >
        发送请求
      </Button>

      <div style={{ marginTop: 16 }}>
        <Text strong>响应结果</Text>
      </div>

      {loading && (
        <div style={{ padding: '24px 0', textAlign: 'center', color: '#6b7280' }}>
          请求中...
        </div>
      )}

      {!loading && response && (
        <div
          style={{
            background: '#f9fafb',
            border: '1px solid #e5e7eb',
            borderRadius: 6,
            padding: 12,
            marginTop: 8,
          }}
        >
          <Space size={16} style={{ marginBottom: 8 }}>
            <Tag color={statusColor}>{response.status}</Tag>
            <Text type="secondary">
              耗时: {response.latencyMs} ms
            </Text>
            <Text type="secondary">
              Request ID: {response.requestId}
            </Text>
          </Space>
          {response.errorMessage && (
            <Paragraph type="danger" style={{ marginBottom: 8 }}>
              错误信息: {response.errorMessage}
            </Paragraph>
          )}
          <pre
            style={{
              background: '#fff',
              border: '1px solid #e5e7eb',
              borderRadius: 4,
              padding: 12,
              fontSize: 12,
              maxHeight: 400,
              overflow: 'auto',
              margin: 0,
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
            }}
          >
            {response.answer || '(无响应内容)'}
          </pre>
          {response.usage && (
            <div style={{ marginTop: 8 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                Token 用量: {JSON.stringify(response.usage)}
              </Text>
            </div>
          )}
        </div>
      )}

      {!loading && errorMsg && (
        <div
          style={{
            background: '#fef2f2',
            border: '1px solid #fecaca',
            borderRadius: 6,
            padding: 12,
            marginTop: 8,
          }}
        >
          <Tag color="red">请求失败</Tag>
          <Paragraph type="danger" style={{ marginTop: 4, marginBottom: 0 }}>
            {errorMsg}
          </Paragraph>
        </div>
      )}

      {!loading && !response && !errorMsg && (
        <div
          style={{
            background: '#f9fafb',
            border: '1px dashed #d1d5db',
            borderRadius: 6,
            padding: 24,
            textAlign: 'center',
            marginTop: 8,
            color: '#9ca3af',
          }}
        >
          点击「发送请求」查看响应结果
        </div>
      )}
    </Card>
  );
};

export default ApiTester;