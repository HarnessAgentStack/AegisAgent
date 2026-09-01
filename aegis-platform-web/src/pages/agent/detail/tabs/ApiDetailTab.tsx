/**
 * @file API 管理 Tab
 * @description 系统智能体详情页 API 管理标签：密钥管理、在线测试、API 文档（含错误码）、版本管理。
 * @author aegis
 * @since 2.0.0
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Modal,
  Spin,
  Table,
  Tabs,
  Tag,
  Typography,
  App,
} from 'antd';
import {
  UploadOutlined,
  InfoCircleOutlined,
  ApiOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import {
  bumpAgentApiVersion,
  getAgentApiErrorCodes,
  getAgentApiVersion,
  getAgentApiOpenApiSpec,
  initAgentApi,
  listAgentApiByAgent,
} from '@/api/agentApi';
import type { Agent } from '@/types/agent';
import { AgentType } from '@/types/enum';
import type {
  AgentApiConfig,
  AgentApiVersionInfo,
  ApiErrorCode,
  OpenApiSpec,
} from '@/types/agentApi';
import KeyManager from '@/components/api/KeyManager';
import ApiTester from '@/components/api/ApiTester';
import ApiDocViewer from '@/components/api/ApiDocViewer';

const { Text, Paragraph } = Typography;

interface ApiDetailTabProps {
  agent: Agent;
}

const ApiDetailTab: React.FC<ApiDetailTabProps> = ({ agent }) => {
  const { message } = App.useApp();
  const [apiConfig, setApiConfig] = useState<AgentApiConfig | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [initializing, setInitializing] = useState(false);
  const [openApiSpec, setOpenApiSpec] = useState<OpenApiSpec | null>(null);
  const [specLoading, setSpecLoading] = useState(false);
  const [activeSubTab, setActiveSubTab] = useState('keys');
  const [errorCodes, setErrorCodes] = useState<ApiErrorCode[]>([]);
  const [versionInfo, setVersionInfo] = useState<AgentApiVersionInfo | null>(null);
  const [versionLoading, setVersionLoading] = useState(false);
  const [bumpingVersion, setBumpingVersion] = useState(false);

  const fetchApiConfig = useCallback(async () => {
    if (!agent.id) return;
    setLoading(true);
    setLoadError(false);
    try {
      const list = await listAgentApiByAgent(agent.id);
      setApiConfig(list && list.length > 0 ? list[0] : null);
    } catch (err) {
      // 区分"加载失败"与"未配置"：接口异常（网络/权限/服务错）不再静默吞成空态，
      // 避免误导用户以为"API 未配置"
      console.error(err);
      setLoadError(true);
      setApiConfig(null);
    } finally {
      setLoading(false);
    }
  }, [agent.id]);

  useEffect(() => {
    void fetchApiConfig();
  }, [fetchApiConfig]);

  /** 一键初始化/修复 API 配置（后端幂等：已有则启用补齐，缺失则补建） */
  const handleInitApi = useCallback(async () => {
    if (!agent.id) return;
    setInitializing(true);
    try {
      const api = await initAgentApi(agent.id);
      setApiConfig(api);
      message.success('API 配置已初始化并启用');
    } catch (err) {
      message.error('API 配置初始化失败: ' + (err instanceof Error ? err.message : '未知错误'));
    } finally {
      setInitializing(false);
    }
  }, [agent.id, message]);

  const fetchOpenApiSpec = useCallback(async () => {
    if (!apiConfig?.id) return;
    setSpecLoading(true);
    try {
      const spec = await getAgentApiOpenApiSpec(apiConfig.id);
      setOpenApiSpec(spec);
    } catch (err) {
      console.error(err);
      setOpenApiSpec(null);
    } finally {
      setSpecLoading(false);
    }
  }, [apiConfig?.id]);

  const fetchErrorCodes = useCallback(async () => {
    if (!apiConfig?.id) return;
    try {
      const codes = await getAgentApiErrorCodes(apiConfig.id);
      setErrorCodes(codes);
    } catch (err) {
      console.error(err);
      setErrorCodes([]);
    }
  }, [apiConfig?.id]);

  const fetchVersionInfo = useCallback(async () => {
    if (!apiConfig?.id) return;
    setVersionLoading(true);
    try {
      const info = await getAgentApiVersion(apiConfig.id);
      setVersionInfo(info);
    } catch (err) {
      console.error(err);
      setVersionInfo(null);
    } finally {
      setVersionLoading(false);
    }
  }, [apiConfig?.id]);

  useEffect(() => {
    if (apiConfig?.id) {
      void fetchErrorCodes();
      void fetchVersionInfo();
    }
  }, [apiConfig?.id, fetchErrorCodes, fetchVersionInfo]);

  const handleBumpVersion = async () => {
    if (!apiConfig?.id) return;
    Modal.confirm({
      title: '递增 API 版本号',
      content: (
        <div>
          <Paragraph>
            当前版本：<Tag>{apiConfig.version || '1.0.0'}</Tag>
          </Paragraph>
          <Paragraph type="secondary">
            递增后版本号将自动 +0.1（如 1.0.0 → 1.1.0）。版本变更将记录在 OpenAPI 文档中。
          </Paragraph>
        </div>
      ),
      onOk: async () => {
        setBumpingVersion(true);
        try {
          const result = await bumpAgentApiVersion(apiConfig.id);
          message.success(`版本已更新至 ${result.version}`);
          setVersionInfo(result);
          setApiConfig(prev => prev ? { ...prev, version: result.version } : prev);
          setOpenApiSpec(null);
          fetchOpenApiSpec();
        } catch (err) {
          if (err instanceof Error) {
            message.error(err.message);
          }
        } finally {
          setBumpingVersion(false);
        }
      },
    });
  };

  const renderVersionPanel = () => (
    <Card
      title="🏷️ 版本管理"
      size="small"
      extra={
        <Button
          icon={<UploadOutlined />}
          onClick={handleBumpVersion}
          loading={bumpingVersion}
          type="primary"
          disabled={!apiConfig}
        >
          递增版本
        </Button>
      }
    >
      {versionLoading ? (
        <div style={{ textAlign: 'center', padding: 24 }}>
          <Spin size="small" />
        </div>
      ) : versionInfo ? (
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="当前版本">
            <Tag color="blue" style={{ fontSize: 14 }}>{versionInfo.version}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="API 状态">
            <Tag color={versionInfo.status === 'NORMAL' ? 'green' : 'red'}>
              {versionInfo.status === 'NORMAL' ? '正常' : '已禁用'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="API 名称">
            {versionInfo.apiName}
          </Descriptions.Item>
          <Descriptions.Item label="API 路径">
            <Tag>{versionInfo.apiPath}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="最后测试" span={2}>
            {versionInfo.lastTestedAt || <Text type="secondary">未测试</Text>}
          </Descriptions.Item>
          <Descriptions.Item label="并发限制">
            {versionInfo.concurrentLimit || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="限流 (QPS)">
            {versionInfo.rateLimit || '-'}
          </Descriptions.Item>
        </Descriptions>
      ) : (
        <Empty description="暂无版本信息" />
      )}

      <Paragraph type="secondary" style={{ marginTop: 16, fontSize: 12 }}>
        <InfoCircleOutlined /> 版本号遵循语义化版本规范（SemVer）：主版本号.次版本号.修订号。递增操作将次版本号 +1。
      </Paragraph>
    </Card>
  );

  const renderErrorCodesPanel = () => (
    <Card title="⚠️ 错误码说明" size="small">
      {errorCodes.length > 0 ? (
        <Table
          dataSource={errorCodes}
          rowKey="code"
          size="small"
          pagination={false}
          columns={[
            {
              title: 'HTTP 状态码',
              dataIndex: 'httpStatus',
              width: 120,
              render: (status: string) => (
                <Tag color={status.startsWith('2') ? 'green' : 'red'}>{status}</Tag>
              ),
            },
            {
              title: '错误码',
              dataIndex: 'code',
              width: 220,
              render: (code: string) => <Text code>{code}</Text>,
            },
            { title: '说明', dataIndex: 'message', width: 180 },
            { title: '详细描述', dataIndex: 'description' },
          ]}
        />
      ) : (
        <Empty description="暂无错误码信息" />
      )}
    </Card>
  );

  if (loading) {
    return (
      <div style={{ padding: 48, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  if (loadError) {
    return (
      <Card>
        <Alert
          type="error"
          showIcon
          message="API 配置加载失败"
          description="查询 API 发布信息时出错（服务异常或权限不足），请重试。若持续失败请联系管理员。"
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void fetchApiConfig()}>
              重试
            </Button>
          }
        />
      </Card>
    );
  }

  if (!apiConfig) {
    const isSystem = agent.agentType === AgentType.SYSTEM;
    return (
      <Card>
        <Alert
          type={isSystem ? 'warning' : 'info'}
          showIcon
          message="API 未配置"
          description={
            isSystem
              ? '未查询到该智能体的 API 发布记录（可能为创建/审核链路异常或历史数据缺失）。可点击下方按钮一键初始化：系统将自动补建 API 配置、生成密钥并绑定沙箱池。'
              : '该智能体尚未配置 API 发布信息，请先在编辑页完成 API 配置并通过审核。'
          }
        />
        {isSystem && (
          <Button
            style={{ marginTop: 16 }}
            type="primary"
            icon={<ApiOutlined />}
            loading={initializing}
            onClick={() => void handleInitApi()}
          >
            初始化 API 配置
          </Button>
        )}
      </Card>
    );
  }

  return (
    <div>
      <Card title="📋 API 概览" style={{ marginBottom: 16 }} size="small">
        <Descriptions column={3} size="small" bordered>
          <Descriptions.Item label="API 名称">
            <Text strong>{apiConfig.apiName}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="API 路径">
            <Tag color="blue">{apiConfig.apiPath}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="HTTP 方法">
            <Tag color="geekblue">{apiConfig.httpMethod}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={apiConfig.status === 'NORMAL' ? 'green' : 'red'}>
              {apiConfig.status === 'NORMAL' ? '正常' : '已禁用'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="版本">
            <Tag>{apiConfig.version}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="响应模式">
            <Tag color="purple">{apiConfig.responseMode}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="鉴权方式">
            <Tag color={apiConfig.authType === 'BEARER' ? 'blue' : 'default'}>
              {apiConfig.authType}
            </Tag>
          </Descriptions.Item>
          {apiConfig.authType === 'BEARER' && (
            <>
              <Descriptions.Item label="Token 模式">
                <Tag color={apiConfig.bearerTokenMode === 'STATIC' ? 'orange' : 'green'}>
                  {apiConfig.bearerTokenMode || 'PASSTHROUGH'}
                </Tag>
              </Descriptions.Item>
              {apiConfig.bearerTokenMode !== 'STATIC' && (
                <Descriptions.Item label="签名算法">
                  <Tag>{apiConfig.bearerJwtAlgorithm || 'HS256'}</Tag>
                </Descriptions.Item>
              )}
              <Descriptions.Item label="Token 透传">
                <Tag color={apiConfig.bearerPassThrough ? 'green' : 'default'}>
                  {apiConfig.bearerPassThrough ? '已启用' : '未启用'}
                </Tag>
              </Descriptions.Item>
            </>
          )}
          <Descriptions.Item label="限流 (QPS)">
            {apiConfig.rateLimit}
          </Descriptions.Item>
          <Descriptions.Item label="超时 (秒)">
            {apiConfig.timeout}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Tabs
        activeKey={activeSubTab}
        onChange={(key) => {
          setActiveSubTab(key);
          if (key === 'docs' && !openApiSpec) {
            void fetchOpenApiSpec();
          }
          if (key === 'version' && !versionInfo) {
            void fetchVersionInfo();
          }
          if (key === 'errors' && errorCodes.length === 0) {
            void fetchErrorCodes();
          }
        }}
        items={[
          {
            key: 'keys',
            label: '🔑 密钥管理',
            children: <KeyManager apiId={apiConfig.id} agentId={agent.id} />,
          },
          {
            key: 'tester',
            label: '🧪 在线测试',
            children: (
              <ApiTester
                apiId={apiConfig.id}
                agentId={agent.id}
                apiPath={apiConfig.apiPath}
                httpMethod={apiConfig.httpMethod}
              />
            ),
          },
          {
            key: 'docs',
            label: '📖 API 文档',
            children: (
              <ApiDocViewer
                spec={openApiSpec}
                errorCodes={errorCodes}
                loading={specLoading}
              />
            ),
          },
          {
            key: 'version',
            label: '🏷️ 版本管理',
            children: renderVersionPanel(),
          },
          {
            key: 'errors',
            label: '⚠️ 错误码',
            children: renderErrorCodesPanel(),
          },
        ]}
      />
    </div>
  );
};

export default ApiDetailTab;
