/**
 * @file API 密钥管理组件
 * @description 管理系统智能体 API Keys：列表、生成、吊销、轮换。
 * @author aegis
 * @since 2.0.0
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CopyOutlined, EditOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons';
import {
  generateAgentApiKey,
  listAgentApiKeys,
  revokeAgentApiKey,
  rotateAgentApiKey,
} from '@/api/agentApi';
import type { AgentApiKeyInfo } from '@/types/agentApi';
import { ApiValidityType } from '@/types/agentApi';

const { Text } = Typography;

interface KeyManagerProps {
  apiId: string;
  agentId: string;
}

const STATUS_TAG: Record<string, { color: string; text: string }> = {
  ACTIVE: { color: 'green', text: '生效中' },
  REVOKED: { color: 'red', text: '已吊销' },
  EXPIRED: { color: 'orange', text: '已过期' },
};

const validityOptions = [
  { value: ApiValidityType.PERMANENT, label: '永久' },
  { value: ApiValidityType.DAYS_7, label: '7 天' },
  { value: ApiValidityType.DAYS_30, label: '30 天' },
  { value: ApiValidityType.CUSTOM, label: '自定义' },
];

const KeyManager: React.FC<KeyManagerProps> = ({ apiId, agentId: _agentId }) => {
  const { message } = App.useApp();
  const [keys, setKeys] = useState<AgentApiKeyInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [genModalOpen, setGenModalOpen] = useState(false);
  const [genLoading, setGenLoading] = useState(false);
  const [generatedKey, setGeneratedKey] = useState<string | null>(null);
  const [genForm] = Form.useForm();

  const fetchKeys = useCallback(async () => {
    if (!apiId) return;
    setLoading(true);
    try {
      const data = await listAgentApiKeys(apiId);
      setKeys(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [apiId]);

  useEffect(() => {
    void fetchKeys();
  }, [fetchKeys]);

  const handleGenerate = async () => {
    try {
      const values = await genForm.validateFields();
      setGenLoading(true);
      const result = await generateAgentApiKey(apiId, {
        label: values.keyLabel || '主Key',
        validityType: values.validityType || ApiValidityType.PERMANENT,
      });
      setGeneratedKey(result.key);
      message.success('密钥生成成功，请妥善保存');
      await fetchKeys();
    } catch (err) {
      if (err instanceof Error && err.message) {
        message.error(err.message);
      }
    } finally {
      setGenLoading(false);
    }
  };

  const handleCopy = (text: string) => {
    navigator.clipboard.writeText(text).then(() => {
      message.success('已复制到剪贴板');
    });
  };

  const handleRevoke = async (keyId: string) => {
    try {
      await revokeAgentApiKey(keyId);
      message.success('密钥已吊销');
      await fetchKeys();
    } catch {
      // 错误已由拦截器处理
    }
  };

  const handleRotate = async (keyId: string) => {
    try {
      const result = await rotateAgentApiKey(apiId, keyId);
      setGeneratedKey(result.key);
      message.success('密钥已轮换，请妥善保存新密钥');
      await fetchKeys();
    } catch {
      // 错误已由拦截器处理
    }
  };

  const columns: ColumnsType<AgentApiKeyInfo> = [
    {
      title: '密钥标签',
      dataIndex: 'keyLabel',
      key: 'keyLabel',
      render: (label: string, record) => (
        <Space direction="vertical" size={0}>
          <Text strong>{label}</Text>
          {record.keyPreview && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {record.keyPreview}
            </Text>
          )}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const cfg = STATUS_TAG[status] ?? { color: 'default', text: status };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '过期时间',
      dataIndex: 'expiresAt',
      key: 'expiresAt',
      render: (v?: string) => v || <Text type="secondary">永久</Text>,
    },
    {
      title: '最后使用',
      dataIndex: 'lastUsedAt',
      key: 'lastUsedAt',
      render: (v?: string) => v || <Text type="secondary">从未使用</Text>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_: unknown, record) => {
        const isActive = record.status === 'ACTIVE';
        return (
          <Space size={4}>
            <Popconfirm
              title="确认轮换该密钥？"
              description="轮换将生成新密钥并吊销当前密钥，正在使用的调用方需更新密钥。"
              onConfirm={() => handleRotate(record.id)}
              disabled={!isActive}
            >
              <Button
                size="small"
                icon={<ReloadOutlined />}
                disabled={!isActive}
                type="link"
              >
                轮换
              </Button>
            </Popconfirm>
            <Popconfirm
              title="确认吊销该密钥？"
              description="吊销后该密钥将立即失效，不可恢复。"
              onConfirm={() => handleRevoke(record.id)}
              disabled={!isActive}
            >
              <Button
                size="small"
                danger
                icon={<StopOutlined />}
                disabled={!isActive}
                type="link"
              >
                吊销
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <Card
      title="🔑 密钥管理"
      extra={
        <Space>
          <Button icon={<EditOutlined />} onClick={() => setGenModalOpen(true)} type="primary">
            生成新密钥
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void fetchKeys()}>
            刷新
          </Button>
        </Space>
      }
    >
      <Table<AgentApiKeyInfo>
        rowKey="id"
        loading={loading}
        dataSource={keys}
        columns={columns}
        pagination={false}
        locale={{ emptyText: '暂无密钥，点击「生成新密钥」创建' }}
      />

      <Modal
        title="生成新密钥"
        open={genModalOpen}
        onCancel={() => {
          setGenModalOpen(false);
          setGeneratedKey(null);
          genForm.resetFields();
        }}
        footer={
          generatedKey ? (
            <Button type="primary" onClick={() => {
              setGenModalOpen(false);
              setGeneratedKey(null);
              genForm.resetFields();
            }}>
              完成
            </Button>
          ) : [
            <Button key="cancel" onClick={() => setGenModalOpen(false)}>
              取消
            </Button>,
            <Button key="submit" type="primary" loading={genLoading} onClick={() => void handleGenerate()}>
              生成
            </Button>,
          ]
        }
        destroyOnClose
      >
        {generatedKey ? (
          <div>
            <div style={{ marginBottom: 12 }}>
              <Text type="warning" strong>
                ⚠️ 请立即复制保存，此密钥仅展示一次明文
              </Text>
            </div>
            <Input.TextArea
              value={generatedKey}
              readOnly
              autoSize={{ minRows: 2, maxRows: 4 }}
              style={{ fontFamily: 'monospace', marginBottom: 12 }}
            />
            <Button
              icon={<CopyOutlined />}
              onClick={() => handleCopy(generatedKey)}
              block
            >
              复制密钥
            </Button>
          </div>
        ) : (
          <Form form={genForm} layout="vertical" preserve={false}>
            <Form.Item
              name="keyLabel"
              label="密钥标签"
              initialValue="主Key"
              rules={[{ max: 32, message: '标签长度不超过 32 字符' }]}
            >
              <Input placeholder="如 生产环境 / 测试环境" />
            </Form.Item>
            <Form.Item
              name="validityType"
              label="有效期"
              initialValue={ApiValidityType.PERMANENT}
            >
              <Select options={validityOptions} />
            </Form.Item>
          </Form>
        )}
      </Modal>
    </Card>
  );
};

export default KeyManager;
    