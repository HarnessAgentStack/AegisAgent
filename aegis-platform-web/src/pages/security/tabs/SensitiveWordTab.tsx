/**
 * @file 敏感词库 Tab
 * @description 敏感词的增删改查、搜索过滤
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
  createSensitiveWord,
  deleteSensitiveWord,
  getSensitiveWords,
  updateSensitiveWord,
} from '@/api/security';
import {
  renderEnabledTag,
  WORD_ACTION_MAP,
  WORD_ACTION_OPTIONS,
  WORD_CATEGORY_MAP,
  WORD_CATEGORY_OPTIONS,
  WORD_MATCH_MODE_MAP,
  WORD_MATCH_MODE_OPTIONS,
  WORD_SCOPE_MAP,
  WORD_SCOPE_OPTIONS,
} from '../constants';
import type { SensitiveWordDTO, SensitiveWordFormValues } from '../types';

const SensitiveWordTab: React.FC = () => {
  const { message } = App.useApp();
  const [sensitiveWords, setSensitiveWords] = useState<SensitiveWordDTO[]>([]);
  const [wordLoading, setWordLoading] = useState(false);
  const [wordModalVisible, setWordModalVisible] = useState(false);
  const [wordSubmitLoading, setWordSubmitLoading] = useState(false);
  const [wordEditing, setWordEditing] = useState<SensitiveWordDTO | null>(null);
  const [wordKeyword, setWordKeyword] = useState<string>('');
  const [wordCategory, setWordCategory] = useState<string>('all');
  const [wordForm] = Form.useForm<SensitiveWordFormValues>();
  const wordActionWatch = Form.useWatch('action', wordForm);

  /** 加载敏感词 */
  const loadSensitiveWords = async () => {
    setWordLoading(true);
    try {
      const res = await getSensitiveWords({ page: 1, size: 200 });
      setSensitiveWords((res.records || []) as SensitiveWordDTO[]);
    } catch {
      /* 错误已由请求拦截器提示 */
    } finally {
      setWordLoading(false);
    }
  };

  useEffect(() => {
    loadSensitiveWords();
  }, []);

  /** 打开新增/编辑敏感词弹窗 */
  const openWordModal = (record?: SensitiveWordDTO) => {
    if (record) {
      setWordEditing(record);
      wordForm.setFieldsValue({
        word: record.word ?? '',
        category: record.category ?? 'GENERAL',
        matchMode: record.matchMode ?? 'EXACT',
        action: record.action ?? 'BLOCK',
        replaceText: record.replaceText ?? '',
        scope: record.scope ?? 'ALL',
        enabled: record.enabled ?? true,
      });
    } else {
      setWordEditing(null);
      wordForm.resetFields();
      wordForm.setFieldsValue({
        category: 'GENERAL',
        matchMode: 'EXACT',
        action: 'BLOCK',
        scope: 'ALL',
        enabled: true,
      });
    }
    setWordModalVisible(true);
  };

  /** 提交新增/编辑敏感词 */
  const submitSensitiveWord = async () => {
    try {
      const values = await wordForm.validateFields();
      setWordSubmitLoading(true);
      if (wordEditing?.id) {
        await updateSensitiveWord(wordEditing.id, values as Partial<SensitiveWordDTO>);
        message.success('敏感词已更新');
      } else {
        await createSensitiveWord(values as Partial<SensitiveWordDTO>);
        message.success('敏感词已创建');
      }
      setWordModalVisible(false);
      await loadSensitiveWords();
    } catch (err) {
      if ((err as { errorFields?: unknown })?.errorFields) return;
    } finally {
      setWordSubmitLoading(false);
    }
  };

  /** 删除敏感词 */
  const handleDeleteWord = async (id: string) => {
    try {
      await deleteSensitiveWord(id);
      message.success('敏感词已删除');
      await loadSensitiveWords();
    } catch {
      /* 错误已由请求拦截器提示 */
    }
  };

  const wordColumns: ColumnsType<SensitiveWordDTO> = [
    { title: '敏感词', dataIndex: 'word', width: 180 },
    {
      title: '分类',
      dataIndex: 'category',
      width: 100,
      render: (cat: string) => {
        const item = WORD_CATEGORY_MAP[cat] ?? { text: cat, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '匹配模式',
      dataIndex: 'matchMode',
      width: 100,
      render: (m: string) => {
        const item = WORD_MATCH_MODE_MAP[m] ?? { text: m, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 90,
      render: (a: string) => {
        const item = WORD_ACTION_MAP[a] ?? { text: a, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    { title: '替换词', dataIndex: 'replaceText', width: 140, render: (v?: string) => v || '-' },
    {
      title: '作用范围',
      dataIndex: 'scope',
      width: 100,
      render: (s: string) => {
        const item = WORD_SCOPE_MAP[s] ?? { text: s, color: '#6b7280' };
        return <Tag color={item.color}>{item.text}</Tag>;
      },
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
      render: (_v: unknown, record: SensitiveWordDTO) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openWordModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title={`确认删除敏感词「${record.word}」？`}
            description="删除后不可恢复"
            onConfirm={() => record.id && handleDeleteWord(record.id)}
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const filtered = sensitiveWords.filter((w) => {
    const matchKeyword = !wordKeyword || (w.word ?? '').includes(wordKeyword);
    const matchCategory = wordCategory === 'all' || w.category === wordCategory;
    return matchKeyword && matchCategory;
  });

  return (
    <Card>
      <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => openWordModal()}>
          新增敏感词
        </Button>
        <Input.Search
          placeholder="搜索敏感词"
          allowClear
          style={{ width: 220 }}
          value={wordKeyword}
          onChange={(e) => setWordKeyword(e.target.value)}
        />
        <Select
          style={{ width: 140 }}
          value={wordCategory}
          onChange={(v: string) => setWordCategory(v)}
          options={[
            { value: 'all', label: '全部分类' },
            ...WORD_CATEGORY_OPTIONS,
          ]}
        />
      </div>
      <Table<SensitiveWordDTO>
        rowKey="id"
        columns={wordColumns}
        dataSource={filtered}
        loading={wordLoading}
        pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
        size="middle"
        locale={{ emptyText: '暂无敏感词，点击「新增敏感词」添加' }}
      />

      <Modal
        title={wordEditing ? '编辑敏感词' : '新增敏感词'}
        open={wordModalVisible}
        onCancel={() => setWordModalVisible(false)}
        onOk={submitSensitiveWord}
        confirmLoading={wordSubmitLoading}
        width={600}
        okText={wordEditing ? '保存' : '创建'}
        destroyOnClose
      >
        <Form<SensitiveWordFormValues> form={wordForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="word"
                label="敏感词"
                rules={[{ required: true, message: '请输入敏感词' }]}
              >
                <Input placeholder="请输入敏感词" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="category"
                label="分类"
                rules={[{ required: true, message: '请选择分类' }]}
              >
                <Select options={WORD_CATEGORY_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="matchMode"
                label="匹配模式"
                rules={[{ required: true, message: '请选择匹配模式' }]}
              >
                <Select options={WORD_MATCH_MODE_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="action"
                label="处置动作"
                rules={[{ required: true, message: '请选择处置动作' }]}
              >
                <Select options={WORD_ACTION_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            {wordActionWatch === 'REPLACE' && (
              <Col span={12}>
                <Form.Item
                  name="replaceText"
                  label="替换文本"
                  rules={[{ required: true, message: '替换动作需填写替换文本' }]}
                >
                  <Input placeholder="替换后的文本" />
                </Form.Item>
              </Col>
            )}
            <Col span={12}>
              <Form.Item
                name="scope"
                label="作用范围"
                rules={[{ required: true, message: '请选择作用范围' }]}
              >
                <Select options={WORD_SCOPE_OPTIONS} placeholder="请选择" />
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

export default SensitiveWordTab;
