/**
 * @file 供应商管理 Tab
 * @description 供应商列表、新增/编辑弹窗、测试连接
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Col, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd';
import { ApiOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { modelApi } from '@/api/model';
import {
  PROVIDER_STATUS_OPTIONS,
  type ProviderForm,
  type ProviderRow,
} from '../constants';

const { Text } = Typography;

interface ProviderTabProps {
  providers: ProviderRow[];
  setProviders: React.Dispatch<React.SetStateAction<ProviderRow[]>>;
  onCountChange?: (count: number) => void;
}

const ProviderTab: React.FC<ProviderTabProps> = ({ providers, setProviders, onCountChange }) => {
  const { message } = App.useApp();
  const [modalVisible, setModalVisible] = useState(false);
  const [modalLoading, setModalLoading] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [form] = Form.useForm<ProviderForm>();

  useEffect(() => {
    onCountChange?.(providers.length);
  }, [providers.length, onCountChange]);

  const openModal = (record?: ProviderRow) => {
    form.resetFields();
    if (record) {
      setEditId(record.id);
      form.setFieldsValue({
        providerCode: record.providerCode,
        providerName: record.providerName,
        endpoint: record.endpoint,
        status: record.status,
      });
    } else {
      setEditId(null);
      form.setFieldsValue({ status: 'ACTIVE' });
    }
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setModalLoading(true);
      if (editId !== null) {
        await modelApi.updateProvider(editId, values);
        message.success('供应商更新成功');
        setProviders((prev) =>
          prev.map((p) =>
            p.id === editId
              ? {
                  ...p,
                  providerName: values.providerName,
                  endpoint: values.endpoint,
                  status: values.status,
                }
              : p,
          ),
        );
      } else {
        const newId = await modelApi.createProvider(values);
        message.success('供应商新增成功');
        const newProvider: ProviderRow = {
          id: String(newId ?? Date.now()),
          providerCode: values.providerCode,
          providerName: values.providerName,
          endpoint: values.endpoint,
          status: values.status,
          modelCount: 0,
          apiKeyMasked: 'sk-***...***-new',
        };
        setProviders((prev) => [newProvider, ...prev]);
      }
      setModalVisible(false);
    } catch (err) {
      console.error(err);
    } finally {
      setModalLoading(false);
    }
  };

  const testConnection = async (record: ProviderRow) => {
    try {
      await modelApi.testProvider(record.id);
      message.success(`「${record.providerName}」连接测试成功`);
    } catch {
      /* 弹错已处理 */
    }
  };

  const columns: ColumnsType<ProviderRow> = [
    { title: '供应商编码', dataIndex: 'providerCode', width: 130 },
    { title: '供应商名称', dataIndex: 'providerName' },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: ProviderRow['status']) => (
        <Tag color={s === 'ACTIVE' ? 'success' : 'default'}>
          {s === 'ACTIVE' ? '已接入' : '待接入'}
        </Tag>
      ),
    },
    { title: '端点', dataIndex: 'endpoint', width: 280, render: (v: string) => <Text copyable style={{ fontSize: 12 }}>{v}</Text> },
    { title: '模型数', dataIndex: 'modelCount', width: 90 },
    { title: 'API Key', dataIndex: 'apiKeyMasked', width: 180, render: (v?: string) => <Text code style={{ fontSize: 12 }}>{v ?? '-'}</Text> },
    {
      title: '操作',
      width: 240,
      render: (_: unknown, record: ProviderRow) => (
        <Space size="small">
          <a onClick={() => testConnection(record)}><ApiOutlined /> 测试连接</a>
          <a onClick={() => openModal(record)}><EditOutlined /> 编辑</a>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ marginBottom: 16, textAlign: 'right' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal()}>
          新增供应商
        </Button>
      </div>
      <Table<ProviderRow>
        rowKey="id"
        columns={columns}
        dataSource={providers}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        scroll={{ x: 1200 }}
      />
      <Modal
        title={editId !== null ? '编辑供应商' : '新增供应商'}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={handleSubmit}
        confirmLoading={modalLoading}
        width={720}
      >
        <Form<ProviderForm> form={form} layout="vertical" autoComplete="off">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="providerCode" label="供应商编码" rules={[{ required: true, message: '请输入编码' }]}>
                <Input placeholder="如 openai" disabled={editId !== null} autoComplete="off" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="providerName" label="供应商名称" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="如 OpenAI" autoComplete="off" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="status" label="状态" rules={[{ required: true }]}>
                <Select options={PROVIDER_STATUS_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="endpoint" label="API 端点" rules={[{ required: true, message: '请输入端点' }]}>
                <Input placeholder="https://api.openai.com/v1" autoComplete="off" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="apiKey"
                label="API Key"
                tooltip={editId !== null ? '留空则不修改现有 API Key' : undefined}
              >
                <Input.Password placeholder={editId !== null ? '留空则不修改' : 'sk-...'} autoComplete="new-password" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </Card>
  );
};

export default ProviderTab;
