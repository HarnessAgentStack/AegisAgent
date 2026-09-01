/**
 * @file OpenAPI 文档查看器
 * @description 基于 OpenAPI 3.0 规范渲染 API 文档，支持结构化展示和原始 JSON 查看。
 * @author aegis
 * @since 2.0.0
 */
import React, { useMemo, useState } from 'react';
import { Card, Typography, Tag, Table, Collapse, Descriptions, Button, Space, Tabs, App } from 'antd';
import {
  DownloadOutlined,
  CopyOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { OpenApiSpec, ApiErrorCode } from '@/types/agentApi';

const { Title, Text, Paragraph } = Typography;

interface ApiDocViewerProps {
  spec: OpenApiSpec | null;
  errorCodes?: ApiErrorCode[];
  loading?: boolean;
}

const METHOD_COLORS: Record<string, string> = {
  get: 'green',
  post: 'blue',
  put: 'orange',
  delete: 'red',
  patch: 'purple',
};

const STATUS_COLORS: Record<string, string> = {
  '200': 'green',
  '400': 'orange',
  '401': 'red',
  '429': 'red',
  '500': 'red',
};

const ApiDocViewer: React.FC<ApiDocViewerProps> = ({ spec, errorCodes = [], loading }) => {
  const [rawMode, setRawMode] = useState(false);
  const { message } = App.useApp();

  const parsedData = useMemo(() => {
    if (!spec) return null;
    return parseOpenApiSpec(spec, errorCodes);
  }, [spec, errorCodes]);

  const handleCopyJson = () => {
    if (spec) {
      navigator.clipboard.writeText(JSON.stringify(spec, null, 2));
      message.success('已复制到剪贴板');
    }
  };

  const handleDownloadJson = () => {
    if (spec) {
      const blob = new Blob([JSON.stringify(spec, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${spec.info?.title || 'api'}-openapi.json`;
      a.click();
      URL.revokeObjectURL(url);
      message.success('下载成功');
    }
  };

  if (loading) {
    return <Card loading={loading}><div style={{ height: 200 }} /></Card>;
  }

  if (!spec || !parsedData) {
    return (
      <Card>
        <div style={{ textAlign: 'center', padding: 40 }}>
          <InfoCircleOutlined style={{ fontSize: 48, color: '#d9d9d9' }} />
          <Paragraph type="secondary" style={{ marginTop: 16 }}>
            暂无 API 文档，请先在 API 配置中完善相关信息
          </Paragraph>
        </div>
      </Card>
    );
  }

  if (rawMode) {
    return (
      <Card
        title="OpenAPI 3.0 JSON"
        extra={
          <Space>
            <Button size="small" icon={<CopyOutlined />} onClick={handleCopyJson}>
              复制
            </Button>
            <Button size="small" icon={<DownloadOutlined />} onClick={handleDownloadJson}>
              下载
            </Button>
            <Button size="small" onClick={() => setRawMode(false)}>
              返回可视化
            </Button>
          </Space>
        }
      >
        <pre
          style={{
            background: '#1e1e1e',
            color: '#d4d4d4',
            padding: 16,
            borderRadius: 8,
            maxHeight: 600,
            overflow: 'auto',
            fontSize: 12,
            lineHeight: 1.6,
            fontFamily: 'Monaco, Consolas, monospace',
          }}
        >
          {JSON.stringify(spec, null, 2)}
        </pre>
      </Card>
    );
  }

  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      <Card
        title={
          <Space>
            <Title level={4} style={{ margin: 0 }}>{parsedData.info.title}</Title>
            <Tag color="blue">v{parsedData.info.version}</Tag>
            <Tag color="green">OpenAPI {parsedData.openapi}</Tag>
          </Space>
        }
        extra={
          <Space>
            <Button size="small" onClick={() => setRawMode(true)}>
              查看原始 JSON
            </Button>
            <Button size="small" icon={<CopyOutlined />} onClick={handleCopyJson}>
              复制 JSON
            </Button>
            <Button size="small" icon={<DownloadOutlined />} onClick={handleDownloadJson}>
              下载
            </Button>
          </Space>
        }
      >
        {parsedData.info.description && (
          <Paragraph type="secondary">{parsedData.info.description}</Paragraph>
        )}

        <Descriptions
          column={2}
          size="small"
          bordered
          style={{ marginTop: 16 }}
        >
          <Descriptions.Item label="API 路径">
            <Space direction="vertical" size={4}>
              {parsedData.paths.map((p, idx) => (
                <Tag key={idx} color={METHOD_COLORS[p.method] || 'default'} style={{ margin: 0 }}>
                  {p.method.toUpperCase()} {p.path}
                </Tag>
              ))}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="鉴权方式">
            {parsedData.security.map((s, idx) => (
              <Tag key={idx} color="purple">
                {s.type.toUpperCase()} ({s.in}: {s.name})
              </Tag>
            ))}
          </Descriptions.Item>
          <Descriptions.Item label="服务地址" span={2}>
            {parsedData.servers.map((s, idx) => (
              <Tag key={idx} color="cyan">{s.url}</Tag>
            ))}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="接口详情" size="small">
        <Collapse
          items={parsedData.paths.map((p, idx) => ({
            key: String(idx),
            label: (
              <Space>
                <Tag color={METHOD_COLORS[p.method] || 'default'}>
                  {p.method.toUpperCase()}
                </Tag>
                <Text strong>{p.path}</Text>
                {p.summary && <Text type="secondary">— {p.summary}</Text>}
              </Space>
            ),
            children: <EndpointDetail pathItem={p} />,
          }))}
        />
      </Card>

      {errorCodes.length > 0 && (
        <Card
          title={
            <Space>
              <ExclamationCircleOutlined style={{ color: '#faad14' }} />
              <span>错误码说明</span>
            </Space>
          }
          size="small"
        >
          <Table
            dataSource={errorCodes}
            rowKey="code"
            size="small"
            pagination={false}
            columns={[
              {
                title: 'HTTP 状态',
                dataIndex: 'httpStatus',
                width: 100,
                render: (status: string) => (
                  <Tag color={STATUS_COLORS[status] || 'default'}>{status}</Tag>
                ),
              },
              {
                title: '错误码',
                dataIndex: 'code',
                width: 200,
                render: (code: string) => <Text code>{code}</Text>,
              },
              { title: '说明', dataIndex: 'message', width: 180 },
              { title: '描述', dataIndex: 'description' },
            ]}
          />
        </Card>
      )}
    </Space>
  );
};

interface ParsedEndpoint {
  method: string;
  path: string;
  summary?: string;
  operationId?: string;
  requestBody?: {
    required: boolean;
    contentType: string;
    schema?: unknown;
  };
  responses: Array<{
    statusCode: string;
    description: string;
    isError?: boolean;
    schema?: unknown;
  }>;
}

interface ParsedSpec {
  openapi: string;
  info: { title: string; version: string; description?: string };
  servers: Array<{ url: string; description?: string }>;
  paths: ParsedEndpoint[];
  security: Array<{ type: string; in: string; name: string }>;
}

function parseOpenApiSpec(spec: OpenApiSpec, _errorCodes: ApiErrorCode[]): ParsedSpec {
  const paths: ParsedEndpoint[] = [];
  const rawPaths = spec.paths || {};

  for (const [pathKey, pathValue] of Object.entries(rawPaths)) {
    if (!pathValue || typeof pathValue !== 'object') continue;

    const methodEntries = Object.entries(pathValue as Record<string, unknown>);
    for (const [method, operation] of methodEntries) {
      if (!operation || typeof operation !== 'object') continue;
      const op = operation as Record<string, unknown>;

      const requestBodyRaw = op.requestBody as Record<string, unknown> | undefined;
      let requestBody: ParsedEndpoint['requestBody'] | undefined;

      if (requestBodyRaw) {
        const content = requestBodyRaw.content as Record<string, Record<string, unknown>> | undefined;
        let schema: unknown = undefined;
        let contentType = 'application/json';
        if (content) {
          const firstKey = Object.keys(content)[0];
          if (firstKey) {
            contentType = firstKey;
            schema = content[firstKey]?.schema;
          }
        }
        requestBody = {
          required: requestBodyRaw.required as boolean ?? true,
          contentType,
          schema,
        };
      }

      const responsesRaw = op.responses as Record<string, Record<string, unknown>> | undefined;
      const responses: ParsedEndpoint['responses'] = [];

      if (responsesRaw) {
        for (const [statusCode, responseData] of Object.entries(responsesRaw)) {
          if (!responseData) continue;
          const respContent = responseData.content as Record<string, Record<string, unknown>> | undefined;
          let respSchema: unknown = undefined;
          if (respContent) {
            const firstKey = Object.keys(respContent)[0];
            if (firstKey) {
              respSchema = respContent[firstKey]?.schema;
            }
          }
          responses.push({
            statusCode,
            description: (responseData.description as string) || '',
            isError: statusCode !== '200',
            schema: respSchema,
          });
        }
      }

      paths.push({
        method,
        path: pathKey,
        summary: op.summary as string | undefined,
        operationId: op.operationId as string | undefined,
        requestBody,
        responses,
      });
    }
  }

  const security: ParsedSpec['security'] = [];
  const components = spec.components as Record<string, unknown> | undefined;
  const securitySchemes = components?.securitySchemes as Record<string, Record<string, unknown>> | undefined;
  if (securitySchemes) {
    for (const scheme of Object.values(securitySchemes)) {
      security.push({
        type: scheme.type as string || 'apiKey',
        in: scheme.in as string || 'header',
        name: scheme.name as string || 'X-API-Key',
      });
    }
  }

  return {
    openapi: spec.openapi,
    info: {
      title: (spec.info as Record<string, string>).title || 'API',
      version: (spec.info as Record<string, string>).version || '1.0.0',
      description: (spec.info as Record<string, string>).description,
    },
    servers: (spec.servers as Array<{ url: string; description?: string }>) || [],
    paths,
    security,
  };
}

const EndpointDetail: React.FC<{ pathItem: ParsedEndpoint }> = ({ pathItem }) => {
  return (
    <Space direction="vertical" style={{ width: '100%' }} size="middle">
      {pathItem.requestBody && (
        <div>
          <Text strong>请求体</Text>
          <Descriptions column={1} size="small" bordered style={{ marginTop: 8 }}>
            <Descriptions.Item label="Content-Type">
              <Tag>{pathItem.requestBody.contentType}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="必填">
              {pathItem.requestBody.required ? (
                <Tag color="red">是</Tag>
              ) : (
                <Tag color="green">否</Tag>
              )}
            </Descriptions.Item>
            {!!pathItem.requestBody.schema && (
              <Descriptions.Item label="Schema">
                <SchemaPreview schema={pathItem.requestBody.schema as Record<string, unknown>} />
              </Descriptions.Item>
            )}
          </Descriptions>
        </div>
      )}

      <div>
        <Text strong>响应</Text>
        <Tabs
          size="small"
          style={{ marginTop: 8 }}
          items={pathItem.responses.map(resp => ({
            key: resp.statusCode,
            label: (
              <Space size={4}>
                {resp.isError ? (
                  <CloseCircleOutlined style={{ color: '#ff4d4f' }} />
                ) : (
                  <CheckCircleOutlined style={{ color: '#52c41a' }} />
                )}
                <Tag color={STATUS_COLORS[resp.statusCode] || 'default'}>
                  {resp.statusCode}
                </Tag>
                <span>{resp.description || (resp.isError ? '错误响应' : '成功响应')}</span>
              </Space>
            ),
            children: resp.schema ? (
              <SchemaPreview schema={resp.schema} />
            ) : (
              <Text type="secondary">无 Schema 定义</Text>
            ),
          }))}
        />
      </div>
    </Space>
  );
};

const SchemaPreview: React.FC<{ schema: unknown }> = ({ schema }) => {
  if (!schema) return <Text type="secondary">无</Text>;

  const schemaObj = schema as Record<string, unknown>;

  if (typeof schemaObj === 'object' && schemaObj !== null && '$ref' in schemaObj) {
    return <Tag color="blue">{schemaObj.$ref as string}</Tag>;
  }

  const type = (schemaObj.type as string) || 'object';
  const properties = schemaObj.properties as Record<string, Record<string, unknown>> | undefined;
  const required = schemaObj.required as string[] | undefined;

  if (type !== 'object' || !properties) {
    return (
      <pre style={{ background: '#f5f5f5', padding: 8, borderRadius: 4, margin: 0, fontSize: 12 }}>
        {JSON.stringify(schema, null, 2)}
      </pre>
    );
  }

  const columns = [
    { title: '字段', dataIndex: 'name', width: 140, render: (v: string, row: { required: boolean }) => (
      <Space>
        <Text code>{v}</Text>
        {row.required && <Tag color="red" style={{ margin: 0 }}>必填</Tag>}
      </Space>
    )},
    { title: '类型', dataIndex: 'type', width: 100, render: (v: string) => <Tag>{v}</Tag> },
    { title: '描述', dataIndex: 'description' },
  ];

  const data = Object.entries(properties).map(([name, prop]) => ({
    name,
    type: (prop.type as string) || 'string',
    description: (prop.description as string) || '-',
    required: required?.includes(name) || false,
  }));

  return (
    <Table
      dataSource={data}
      rowKey="name"
      size="small"
      pagination={false}
      columns={columns}
    />
  );
};

export default ApiDocViewer;
