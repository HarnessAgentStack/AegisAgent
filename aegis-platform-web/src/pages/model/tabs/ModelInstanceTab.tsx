/**
 * @file 模型实例 Tab
 * @description 模型实例列表、新增/编辑弹窗、启用/禁用
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Col, Form, Input, InputNumber, Modal, Row, Select, Space, Switch, Table, Tag } from 'antd';
import { CheckCircleOutlined, EditOutlined, PlusOutlined, PoweroffOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { ModelTier } from '@/types/enum';
import { modelApi } from '@/api/model';
import {
  MODEL_TIER_OPTIONS,
  MODEL_TYPE_OPTIONS,
  MODEL_TYPE_TAG,
  TIER_TAG,
  type ModelInstanceForm,
  type ModelInstanceRow,
  type ModelCapabilities,
  type ProviderRow,
} from '../constants';

interface ModelInstanceTabProps {
  providers: ProviderRow[];
  models: ModelInstanceRow[];
  setModels: React.Dispatch<React.SetStateAction<ModelInstanceRow[]>>;
  onCountChange?: (count: number) => void;
}

const ModelInstanceTab: React.FC<ModelInstanceTabProps> = ({ providers, models, setModels, onCountChange }) => {
  const { message } = App.useApp();
  const [modalVisible, setModalVisible] = useState(false);
  const [modalLoading, setModalLoading] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [form] = Form.useForm<ModelInstanceForm>();

  useEffect(() => {
    onCountChange?.(models.length);
  }, [models.length, onCountChange]);

  const openModal = (record?: ModelInstanceRow) => {
    form.resetFields();
    if (record) {
      setEditId(record.id);
      const capabilities = record.capabilities || {
        multimodal: { supported: false, imageTypes: [], maxImageSizeKb: 0, maxImagesPerRequest: 0 },
        document: { supported: false, docTypes: [], maxDocSizeKb: 0 },
        visionDescription: { supported: false, description: '' },
        contextWindow: record.contextWindow,
        maxOutputTokens: 4096,
        supportsFunctionCalling: true,
        supportsJsonMode: true,
      };
      form.setFieldsValue({
        modelCode: record.modelCode,
        modelName: record.modelName,
        modelType: record.modelType,
        tier: record.tier,
        providerId: record.providerId,
        contextWindow: record.contextWindow,
        multimodalSupported: capabilities.multimodal?.supported || false,
        multimodalImageTypes: capabilities.multimodal?.imageTypes?.join(',') || '',
        multimodalMaxSize: capabilities.multimodal?.maxImageSizeKb || 0,
        multimodalMaxCount: capabilities.multimodal?.maxImagesPerRequest || 0,
        documentSupported: capabilities.document?.supported || false,
        documentTypes: capabilities.document?.docTypes?.join(',') || '',
        documentMaxSize: capabilities.document?.maxDocSizeKb || 0,
        visionSupported: capabilities.visionDescription?.supported || false,
        visionDescription: capabilities.visionDescription?.description || '',
        maxOutputTokens: capabilities.maxOutputTokens || 4096,
        supportsFunctionCalling: capabilities.supportsFunctionCalling || false,
        supportsJsonMode: capabilities.supportsJsonMode || false,
      });
    } else {
      setEditId(null);
      form.setFieldsValue({
        modelType: 'TEXT',
        tier: ModelTier.STANDARD,
        contextWindow: 32000,
        multimodalSupported: false,
        multimodalImageTypes: '',
        multimodalMaxSize: 0,
        multimodalMaxCount: 0,
        documentSupported: false,
        documentTypes: '',
        documentMaxSize: 0,
        visionSupported: false,
        visionDescription: '',
        maxOutputTokens: 4096,
        supportsFunctionCalling: true,
        supportsJsonMode: true,
      });
    }
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setModalLoading(true);
      const providerName = providers.find((p) => p.id === values.providerId)?.providerName ?? '-';

      // 构建能力矩阵对象
      const capabilities: ModelCapabilities = {
        multimodal: {
          supported: values.multimodalSupported === true,
          imageTypes: values.multimodalImageTypes ? values.multimodalImageTypes.split(',').map((s: string) => s.trim()) : [],
          maxImageSizeKb: values.multimodalMaxSize,
          maxImagesPerRequest: values.multimodalMaxCount,
        },
        document: {
          supported: values.documentSupported === true,
          docTypes: values.documentTypes ? values.documentTypes.split(',').map((s: string) => s.trim()) : [],
          maxDocSizeKb: values.documentMaxSize,
        },
        visionDescription: {
          supported: values.visionSupported === true,
          description: values.visionDescription || '',
        },
        contextWindow: values.contextWindow,
        maxOutputTokens: values.maxOutputTokens,
        supportsFunctionCalling: values.supportsFunctionCalling === true,
        supportsJsonMode: values.supportsJsonMode === true,
      };

      const submitData = {
        ...values,
        capabilities,
      };

      if (editId !== null) {
        await modelApi.updateDef(editId, submitData);
        message.success('模型实例更新成功');
        setModels((prev) =>
          prev.map((m) =>
            m.id === editId
              ? { ...m, ...values, providerName, capabilities }
              : m,
          ),
        );
      } else {
        const newId = await modelApi.createDef(submitData);
        message.success('模型实例新增成功');
        const newModel: ModelInstanceRow = {
          id: String(newId ?? Date.now()),
          ...values,
          providerName,
          status: 'ENABLED',
          capabilities,
        };
        setModels((prev) => [newModel, ...prev]);
      }
      setModalVisible(false);
    } catch (err) {
      console.error(err);
    } finally {
      setModalLoading(false);
    }
  };

  const toggleStatus = async (record: ModelInstanceRow) => {
    const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
    try {
      await modelApi.updateDef(record.id, { status: next });
      message.success(`已${next === 'ENABLED' ? '启用' : '禁用'}「${record.modelName}」`);
      setModels((prev) => prev.map((m) => (m.id === record.id ? { ...m, status: next } : m)));
    } catch {
      /* 弹错已处理 */
    }
  };

  const columns: ColumnsType<ModelInstanceRow> = [
    { title: '模型编码', dataIndex: 'modelCode', width: 180 },
    { title: '模型名称', dataIndex: 'modelName' },
    {
      title: '类型',
      dataIndex: 'modelType',
      width: 110,
      render: (t?: string) => {
        if (!t) return <Tag>未设置</Tag>;
        const cfg = MODEL_TYPE_TAG[t as keyof typeof MODEL_TYPE_TAG] ?? { color: 'default', text: t };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '档位',
      dataIndex: 'tier',
      width: 100,
      render: (t: ModelTier) => {
        const cfg = TIER_TAG[t];
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    { title: '供应商', dataIndex: 'providerName', width: 110 },
    { title: '上下文', dataIndex: 'contextWindow', width: 110, render: (v: number) => `${(v / 1000).toFixed(0)}K` },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: ModelInstanceRow['status']) => (
        <Tag color={s === 'ENABLED' ? 'success' : 'default'}>
          {s === 'ENABLED' ? '启用' : '禁用'}
        </Tag>
      ),
    },
    {
      title: '操作',
      width: 160,
      render: (_: unknown, record: ModelInstanceRow) => (
        <Space size="small">
          <a onClick={() => openModal(record)}><EditOutlined /> 编辑</a>
          {record.status === 'ENABLED' ? (
            <a onClick={() => toggleStatus(record)}><PoweroffOutlined /> 禁用</a>
          ) : (
            <a onClick={() => toggleStatus(record)} style={{ color: '#10b981' }}><CheckCircleOutlined /> 启用</a>
          )}
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ marginBottom: 16, textAlign: 'right' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal()}>
          新增模型
        </Button>
      </div>
      <Table<ModelInstanceRow>
        rowKey="id"
        columns={columns}
        dataSource={models}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        scroll={{ x: 1300 }}
      />
      <Modal
        title={editId !== null ? '编辑模型实例' : '新增模型实例'}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={handleSubmit}
        confirmLoading={modalLoading}
        width={900}
      >
        <Form<ModelInstanceForm> form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="modelCode" label="模型编码" rules={[{ required: true, message: '请输入编码' }]}>
                <Input placeholder="如 gpt-4o" disabled={editId !== null} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="modelName" label="模型名称" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="如 GPT-4o" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="modelType" label="模型类型" rules={[{ required: true, message: '请选择模型类型' }]}>
                <Select options={MODEL_TYPE_OPTIONS} placeholder="请选择模型类型" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="tier" label="档位" rules={[{ required: true }]}>
                <Select options={MODEL_TIER_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="providerId" label="供应商" rules={[{ required: true }]}>
                <Select
                  options={providers.map((p) => ({ value: p.id, label: p.providerName }))}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="contextWindow" label="上下文窗口" rules={[{ required: true }]}>
                <InputNumber min={1024} max={2_000_000} step={1024} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="maxOutputTokens" label="最大输出 Token" rules={[{ required: true }]}>
                <InputNumber min={1024} max={128_000} step={1024} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            </Row>

          <div style={{ marginTop: 24, marginBottom: 16, fontWeight: 500, fontSize: 14 }}>能力矩阵配置</div>

          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="multimodalSupported" label="多模态支持" valuePropName="checked">
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="multimodalImageTypes" label="支持的图片类型（逗号分隔）">
                <Input placeholder="如 png,jpg,jpeg,webp" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="multimodalMaxSize" label="单图最大 KB">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="multimodalMaxCount" label="单次最多图片数">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="documentSupported" label="文档直接输入支持" valuePropName="checked">
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="documentTypes" label="支持的文档类型（逗号分隔）">
                <Input placeholder="如 pdf,docx,xlsx,pptx" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="documentMaxSize" label="单文档最大 KB">
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={24}>
              <Form.Item name="visionSupported" label="视觉描述支持" valuePropName="checked">
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="visionDescription" label="视觉描述能力说明">
                <Input.TextArea rows={2} placeholder="如：支持图片内容理解、OCR 文字识别等" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="supportsFunctionCalling" label="支持 Function Calling" valuePropName="checked">
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="supportsJsonMode" label="支持 JSON Mode" valuePropName="checked">
                <Switch checkedChildren="开启" unCheckedChildren="关闭" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </Card>
  );
};

export default ModelInstanceTab;
