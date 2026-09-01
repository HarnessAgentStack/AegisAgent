/**
 * @file 脱敏规则 Tab
 * @description 脱敏规则的增删改查
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  createMaskRule,
  deleteMaskRule,
  getMaskRules,
  updateMaskRule,
} from '@/api/security';
import {
  COLOR,
  MASK_DATA_TYPE_MAP,
  MASK_DATA_TYPE_OPTIONS,
  MASK_WAY_MAP,
  MASK_WAY_OPTIONS,
  renderEnabledTag,
} from '../constants';
import type { MaskRuleDTO, MaskRuleFormValues } from '../types';

const MaskRuleTab: React.FC = () => {
  const { message } = App.useApp();
  const [maskRules, setMaskRules] = useState<MaskRuleDTO[]>([]);
  const [maskLoading, setMaskLoading] = useState(false);
  const [maskModalVisible, setMaskModalVisible] = useState(false);
  const [maskSubmitLoading, setMaskSubmitLoading] = useState(false);
  const [maskEditing, setMaskEditing] = useState<MaskRuleDTO | null>(null);
  const [maskForm] = Form.useForm<MaskRuleFormValues>();

  /** 加载脱敏规则 */
  const loadMaskRules = async () => {
    setMaskLoading(true);
    try {
      const res = await getMaskRules({ page: 1, size: 200 });
      setMaskRules((res.records || []) as MaskRuleDTO[]);
    } catch {
      /* 错误已由请求拦截器提示 */
    } finally {
      setMaskLoading(false);
    }
  };

  useEffect(() => {
    loadMaskRules();
  }, []);

  /** 打开新增/编辑脱敏规则弹窗 */
  const openMaskModal = (record?: MaskRuleDTO) => {
    if (record) {
      setMaskEditing(record);
      maskForm.setFieldsValue({
        dataType: record.dataType ?? 'PHONE',
        regex: record.regex ?? '',
        maskWay: record.maskWay ?? 'MIDDLE4',
        example: record.example ?? '',
        enabled: record.enabled ?? true,
      });
    } else {
      setMaskEditing(null);
      maskForm.resetFields();
      maskForm.setFieldsValue({
        dataType: 'PHONE',
        maskWay: 'MIDDLE4',
        enabled: true,
      });
    }
    setMaskModalVisible(true);
  };

  /** 提交新增/编辑脱敏规则 */
  const submitMaskRule = async () => {
    try {
      const values = await maskForm.validateFields();
      setMaskSubmitLoading(true);
      if (maskEditing?.id) {
        await updateMaskRule(maskEditing.id, values as Partial<MaskRuleDTO>);
        message.success('脱敏规则已更新');
      } else {
        await createMaskRule(values as Partial<MaskRuleDTO>);
        message.success('脱敏规则已创建');
      }
      setMaskModalVisible(false);
      await loadMaskRules();
    } catch (err) {
      if ((err as { errorFields?: unknown })?.errorFields) return;
    } finally {
      setMaskSubmitLoading(false);
    }
  };

  /** 删除脱敏规则 */
  const handleDeleteMask = async (id: string) => {
    try {
      await deleteMaskRule(id);
      message.success('脱敏规则已删除');
      await loadMaskRules();
    } catch {
      /* 错误已由请求拦截器提示 */
    }
  };

  const maskColumns: ColumnsType<MaskRuleDTO> = [
    {
      title: '数据类型',
      dataIndex: 'dataType',
      width: 120,
      render: (t: string) => {
        const item = MASK_DATA_TYPE_MAP[t] ?? { text: t, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '匹配正则',
      dataIndex: 'regex',
      render: (v?: string) => (
        <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v || '-'}</span>
      ),
    },
    {
      title: '脱敏方式',
      dataIndex: 'maskWay',
      width: 120,
      render: (m: string) => {
        const item = MASK_WAY_MAP[m] ?? { text: m, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '示例',
      dataIndex: 'example',
      width: 180,
      render: (v?: string) => (
        <span style={{ fontFamily: 'monospace', color: COLOR.primary }}>{v || '-'}</span>
      ),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (enabled?: boolean) => renderEnabledTag(enabled),
    },
    {
      title: '操作',
      width: 140,
      render: (_v: unknown, record: MaskRuleDTO) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openMaskModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该脱敏规则？"
            description="删除后不可恢复"
            onConfirm={() => record.id && handleDeleteMask(record.id)}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openMaskModal()}>
          新增规则
        </Button>
      </div>
      <Table<MaskRuleDTO>
        rowKey="id"
        columns={maskColumns}
        dataSource={maskRules}
        loading={maskLoading}
        pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
        size="middle"
        locale={{ emptyText: '暂无脱敏规则，点击「新增规则」添加' }}
      />

      <Modal
        title={maskEditing ? '编辑脱敏规则' : '新增脱敏规则'}
        open={maskModalVisible}
        onCancel={() => setMaskModalVisible(false)}
        onOk={submitMaskRule}
        confirmLoading={maskSubmitLoading}
        width={560}
        okText={maskEditing ? '保存' : '创建'}
        destroyOnClose
      >
        <Form<MaskRuleFormValues> form={maskForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="dataType"
                label="数据类型"
                rules={[{ required: true, message: '请选择数据类型' }]}
              >
                <Select options={MASK_DATA_TYPE_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="maskWay"
                label="脱敏方式"
                rules={[{ required: true, message: '请选择脱敏方式' }]}
              >
                <Select options={MASK_WAY_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="regex"
                label="匹配正则"
                rules={[{ required: true, message: '请输入匹配正则' }]}
              >
                <Input placeholder="如 \d{3}\d{4}\d{4}" style={{ fontFamily: 'monospace' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="example" label="脱敏示例">
                <Input placeholder="如 138****1234" style={{ fontFamily: 'monospace' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="enabled" label="启用状态" valuePropName="checked">
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </Card>
  );
};

export default MaskRuleTab;
