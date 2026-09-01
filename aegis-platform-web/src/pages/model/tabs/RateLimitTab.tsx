/**
 * @file 限流配置 Tab
 * @description 限流配置列表、新增/编辑弹窗
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Col, Form, Input, InputNumber, Modal, Row, Select, Table, Tag } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { modelApi } from '@/api/model';
import type { RateLimitVO } from '@/api/model';
import {
  RATE_LIMIT_ACTION_OPTIONS,
  RATE_LIMIT_ACTION_TAG,
  SCOPE_OPTIONS,
  SCOPE_TAG,
  type RateLimitForm,
  type RateLimitRow,
} from '../constants';

interface RateLimitTabProps {
  onCountChange?: (count: number) => void;
}

const RateLimitTab: React.FC<RateLimitTabProps> = ({ onCountChange }) => {
  const { message } = App.useApp();
  const [rows, setRows] = useState<RateLimitRow[]>([]);
  const [modalVisible, setModalVisible] = useState(false);
  const [modalLoading, setModalLoading] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [form] = Form.useForm<RateLimitForm>();

  useEffect(() => {
    const fetchRateLimits = async () => {
      try {
        const rateLimitList = await modelApi.listRateLimits();
        const rateRows: RateLimitRow[] = (rateLimitList ?? []).map((r: RateLimitVO) => ({
          id: String(r.id),
          scope: (r.scope ?? 'PLATFORM') as RateLimitRow['scope'],
          scopeTargetId: r.scopeTargetId != null ? String(r.scopeTargetId) : '-',
          scopeTargetName: r.scopeTargetId != null ? String(r.scopeTargetId) : '-',
          lightQps: r.lightQps ?? 0,
          standardQps: r.standardQps ?? 0,
          strongQps: r.strongQps ?? 0,
          totalQps: r.totalQps ?? 0,
          action: (r.action ?? 'PASS') as RateLimitRow['action'],
        }));
        setRows(rateRows);
      } catch (err) {
        console.error('加载限流配置数据失败', err);
      }
    };
    fetchRateLimits();
  }, []);

  useEffect(() => {
    onCountChange?.(rows.length);
  }, [rows.length, onCountChange]);

  const openModal = (record?: RateLimitRow) => {
    form.resetFields();
    if (record) {
      setEditId(record.id);
      form.setFieldsValue({
        scope: record.scope,
        scopeTargetId: record.scopeTargetId,
        lightQps: record.lightQps,
        standardQps: record.standardQps,
        strongQps: record.strongQps,
        totalQps: record.totalQps,
        action: record.action,
      });
    } else {
      setEditId(null);
      form.setFieldsValue({
        scope: 'PLATFORM',
        lightQps: 50,
        standardQps: 20,
        strongQps: 5,
        totalQps: 75,
        action: 'ALERT',
      });
    }
    setModalVisible(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setModalLoading(true);
      if (editId !== null) {
        await modelApi.saveRateLimit(values);
        message.success('限流配置更新成功');
        setRows((prev) =>
          prev.map((r) => (r.id === editId ? { ...r, ...values, scopeTargetName: r.scopeTargetName } : r)),
        );
      } else {
        await modelApi.saveRateLimit(values);
        message.success('限流配置新增成功');
        const newRate: RateLimitRow = {
          id: String(Date.now()),
          ...values,
          scopeTargetName: values.scopeTargetId,
        };
        setRows((prev) => [newRate, ...prev]);
      }
      setModalVisible(false);
    } catch (err) {
      console.error(err);
    } finally {
      setModalLoading(false);
    }
  };

  const columns: ColumnsType<RateLimitRow> = [
    {
      title: '作用域',
      dataIndex: 'scope',
      width: 100,
      render: (s: RateLimitRow['scope']) => {
        const cfg = SCOPE_TAG[s] ?? { color: 'default', text: s };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    { title: '目标ID', dataIndex: 'scopeTargetId', width: 120 },
    { title: '目标名称', dataIndex: 'scopeTargetName' },
    { title: '轻量 QPS', dataIndex: 'lightQps', width: 100 },
    { title: '标准 QPS', dataIndex: 'standardQps', width: 100 },
    { title: '高性能 QPS', dataIndex: 'strongQps', width: 110 },
    { title: '总 QPS', dataIndex: 'totalQps', width: 90 },
    {
      title: '触发动作',
      dataIndex: 'action',
      width: 120,
      render: (a: RateLimitRow['action']) => {
        const cfg = RATE_LIMIT_ACTION_TAG[a];
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '操作',
      width: 120,
      render: (_: unknown, record: RateLimitRow) => (
        <a onClick={() => openModal(record)}><EditOutlined /> 配置</a>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ marginBottom: 16, textAlign: 'right' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal()}>
          新增限流配置
        </Button>
      </div>
      <Table<RateLimitRow>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        pagination={{ defaultPageSize: 10, showSizeChanger: true }}
        scroll={{ x: 1200 }}
      />
      <Modal
        title={editId !== null ? '编辑限流配置' : '新增限流配置'}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={handleSubmit}
        confirmLoading={modalLoading}
        width={720}
      >
        <Form<RateLimitForm> form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item name="scope" label="作用域" rules={[{ required: true }]}>
                <Select options={SCOPE_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={16}>
              <Form.Item name="scopeTargetId" label="目标ID" rules={[{ required: true }]}>
                <Input placeholder="如 1 / AGENT-001" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="lightQps" label="轻量QPS" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="standardQps" label="标准QPS" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="strongQps" label="高性能QPS" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="totalQps" label="总QPS" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="action" label="触发动作" rules={[{ required: true }]}>
                <Select options={RATE_LIMIT_ACTION_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </Card>
  );
};

export default RateLimitTab;
