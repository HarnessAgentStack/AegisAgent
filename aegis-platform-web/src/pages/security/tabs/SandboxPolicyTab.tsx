/**
 * @file 沙箱命令策略 Tab
 * @description 列表展示已配置策略的工具；新建时从系统全部注册工具（res_tool）中选择，
 *              支持按名称/编码搜索、已配置工具置灰，杜绝手工输入编码的误配风险
 * @author wang.zhen
 * @since 2.0.0
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  createSandboxPolicy,
  deleteSandboxPolicy,
  getSandboxPolicies,
  updateSandboxPolicy,
} from '@/api/security';
import { toolApi } from '@/api/resource';
import type { Tool } from '@/types/resource';
import {
  COLOR,
  SANDBOX_EXECUTION_MAP,
  SANDBOX_EXECUTION_OPTIONS,
  TOOL_TYPE_LABEL,
} from '../constants';
import type { SandboxPolicyDTO, SandboxPolicyFormValues } from '../types';

const { Text } = Typography;

/** 工具来源标签映射（res_tool.source_type） */
const SOURCE_TYPE_TAG: Record<string, { color: string; text: string }> = {
  BUILTIN: { color: 'blue', text: '内置' },
  MCP: { color: 'cyan', text: 'MCP' },
};

const SandboxPolicyTab: React.FC = () => {
  const { message } = App.useApp();

  const [policies, setPolicies] = useState<SandboxPolicyDTO[]>([]);
  const [tools, setTools] = useState<Tool[]>([]);
  const [loading, setLoading] = useState(false);
  const [toolsLoading, setToolsLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<SandboxPolicyDTO | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<SandboxPolicyFormValues>();

  /** 当前选中待创建的工具编码（用于展示工具说明） */
  const selectedToolCode = Form.useWatch('toolCode', form);

  /** toolCode → 系统工具信息 映射 */
  const toolMap = useMemo(() => {
    const map = new Map<string, Tool>();
    tools.forEach((t) => map.set(t.toolCode, t));
    return map;
  }, [tools]);

  /** 已配置策略的 toolCode 集合 */
  const configuredCodes = useMemo(
    () => new Set(policies.map((p) => p.toolCode).filter(Boolean) as string[]),
    [policies],
  );

  const loadPolicies = async () => {
    setLoading(true);
    try {
      const res = await getSandboxPolicies({ page: 1, size: 200 });
      setPolicies((res.records || []) as SandboxPolicyDTO[]);
    } catch {
      /* 错误由拦截器提示 */
    } finally {
      setLoading(false);
    }
  };

  /** 加载系统全部注册工具（内置 + MCP 同步） */
  const loadTools = async () => {
    setToolsLoading(true);
    try {
      const res = await toolApi.page({ page: 1, size: 500 });
      setTools((res?.records || []) as Tool[]);
    } catch {
      /* 错误由拦截器提示 */
    } finally {
      setToolsLoading(false);
    }
  };

  useEffect(() => {
    loadPolicies();
    loadTools();
  }, []);

  /** 工具展示块：名称 + 编码 + 类型标签（列表列/编辑弹窗复用） */
  const renderToolBlock = (toolCode: string) => {
    const tool = toolMap.get(toolCode);
    const typeLabel = tool ? TOOL_TYPE_LABEL[tool.toolType] : undefined;
    const sourceCfg = tool ? SOURCE_TYPE_TAG[tool.sourceType] : undefined;
    return (
      <Space direction="vertical" size={4} style={{ display: 'flex' }}>
        <Space size={6} wrap>
          <Text strong={!!tool}>{tool?.toolName ?? '未注册工具'}</Text>
          <Text code style={{ fontSize: 12 }}>{toolCode}</Text>
        </Space>
        <Space size={4} wrap>
          {typeLabel && <Tag style={{ marginInlineEnd: 0 }}>{typeLabel}</Tag>}
          {sourceCfg && <Tag color={sourceCfg.color} style={{ marginInlineEnd: 0 }}>{sourceCfg.text}</Tag>}
          {!tool && <Tag color="#9ca3af" style={{ marginInlineEnd: 0 }}>未在系统工具库注册</Tag>}
        </Space>
      </Space>
    );
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ sandboxExecution: true, enabled: true });
    setModalOpen(true);
  };

  const openEdit = (record: SandboxPolicyDTO) => {
    setEditing(record);
    form.setFieldsValue({
      toolCode: record.toolCode,
      sandboxExecution: record.sandboxExecution ?? true,
      description: record.description,
      enabled: record.enabled ?? true,
    });
    setModalOpen(true);
  };

  const handleSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      if (editing?.id) {
        await updateSandboxPolicy(editing.id, values as unknown as Partial<SandboxPolicyDTO>);
        message.success('策略已更新');
      } else {
        await createSandboxPolicy(values as unknown as Partial<SandboxPolicyDTO>);
        message.success(`已为 ${toolMap.get(values.toolCode)?.toolName ?? values.toolCode} 创建沙箱策略`);
      }
      setModalOpen(false);
      await loadPolicies();
    } catch {
      /* validateFields / 请求异常 */
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteSandboxPolicy(id);
      message.success('策略已删除');
      await loadPolicies();
    } catch {
      /* 异常 */
    }
  };

  const toggleEnabled = async (record: SandboxPolicyDTO) => {
    if (!record.id) return;
    try {
      await updateSandboxPolicy(record.id, { enabled: !record.enabled } as unknown as Partial<SandboxPolicyDTO>);
      await loadPolicies();
    } catch {
      /* 异常 */
    }
  };

  const columns: ColumnsType<SandboxPolicyDTO> = [
    {
      title: '工具',
      dataIndex: 'toolCode',
      key: 'toolCode',
      width: 320,
      fixed: 'left',
      render: (code: string) => renderToolBlock(code),
    },
    {
      title: '沙箱执行决策',
      dataIndex: 'sandboxExecution',
      key: 'sandboxExecution',
      width: 140,
      render: (val: boolean | null) => {
        if (val === null || val === undefined) {
          return <Tag color="#9ca3af">未配置</Tag>;
        }
        const info = SANDBOX_EXECUTION_MAP[String(val)];
        return <Tag color={info.color}>{info.text}</Tag>;
      },
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (desc: string) => desc || '-',
    },
    {
      title: '启用',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled: boolean, record) => (
        <Switch checked={enabled} onChange={() => toggleEnabled(record)} />
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      fixed: 'right',
      render: (_, record) => (
        <Space>
          <Button type="link" size="small" onClick={() => openEdit(record)}>编辑</Button>
          <Popconfirm
            title="确定删除此策略？"
            description="删除后运行时将不再对该工具做沙箱路由判定"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => record.id && handleDelete(record.id)}
          >
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  /** 新建弹窗：工具下拉选项（系统全部工具，已配置置灰） */
  const toolOptions = useMemo(
    () =>
      [...tools]
        .sort((a, b) => a.toolName.localeCompare(b.toolName, 'zh-CN'))
        .map((t) => ({
          value: t.toolCode,
          label: `${t.toolName}（${t.toolCode}）`,
          disabled: configuredCodes.has(t.toolCode),
        })),
    [tools, configuredCodes],
  );

  /** 当前选中工具的详细信息（新建弹窗展示用） */
  const selectedTool = selectedToolCode ? toolMap.get(selectedToolCode) : undefined;
  const allConfigured = tools.length > 0 && tools.every((t) => configuredCodes.has(t.toolCode));

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
        <Text type="secondary" style={{ fontSize: 12 }}>
          已配置 <Text strong>{policies.length}</Text> 项策略 · 系统工具库共 <Text strong>{tools.length}</Text> 个工具（含内置与 MCP 同步）
          {toolsLoading && <Spin size="small" style={{ marginLeft: 8 }} />}
        </Text>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => { loadPolicies(); loadTools(); }}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新建策略
          </Button>
        </Space>
      </div>

      <Table<SandboxPolicyDTO>
        rowKey="id"
        columns={columns}
        dataSource={policies}
        loading={loading}
        pagination={false}
        scroll={{ x: 860 }}
        size="middle"
        locale={{ emptyText: '暂无沙箱策略，点击右上角「新建策略」从系统工具库中选择工具配置' }}
      />

      <Modal
        title={editing ? '编辑沙箱策略' : '新建沙箱策略'}
        open={modalOpen}
        onCancel={() => !submitting && setModalOpen(false)}
        onOk={handleSubmit}
        confirmLoading={submitting}
        okText="保存"
        cancelText="取消"
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" preserve={false}
          initialValues={{ sandboxExecution: true, enabled: true }}>

          {editing ? (
            <Form.Item label="工具（策略唯一键，不可修改）">
              {renderToolBlock(editing.toolCode || '')}
            </Form.Item>
          ) : (
            <Form.Item
              label="选择工具"
              name="toolCode"
              rules={[{ required: true, message: '请选择工具' }]}
              extra={allConfigured
                ? '系统工具库中的工具均已配置策略，可先删除旧策略或刷新工具库'
                : '选项来自系统工具库（res_tool），已配置策略的工具不可重复选择'}
            >
              <Select
                showSearch
                allowClear
                placeholder="搜索工具名称或编码"
                options={toolOptions}
                optionFilterProp="label"
                notFoundContent={toolsLoading ? <Spin size="small" /> : '未找到匹配工具'}
                optionRender={(option) => {
                  const tool = toolMap.get(option.value as string);
                  const typeLabel = tool ? TOOL_TYPE_LABEL[tool.toolType] : undefined;
                  const sourceCfg = tool ? SOURCE_TYPE_TAG[tool.sourceType] : undefined;
                  const disabled = configuredCodes.has(option.value as string);
                  return (
                    <Tooltip title={disabled ? '该工具已配置策略' : tool?.description} placement="left">
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                        <Space size={6} wrap>
                          <span>{tool?.toolName}</span>
                          <Text code style={{ fontSize: 12 }}>{option.value}</Text>
                          {typeLabel && <Tag style={{ marginInlineEnd: 0 }}>{typeLabel}</Tag>}
                          {sourceCfg && <Tag color={sourceCfg.color} style={{ marginInlineEnd: 0 }}>{sourceCfg.text}</Tag>}
                        </Space>
                        {disabled && <Tag color={COLOR.warning} style={{ marginInlineEnd: 0 }}>已配置</Tag>}
                      </div>
                    </Tooltip>
                  );
                }}
              />
            </Form.Item>
          )}

          {selectedTool?.description && !editing && (
            <div style={{ marginBottom: 16, padding: '8px 12px', background: '#fafafa', borderRadius: 6 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>{selectedTool.description}</Text>
            </div>
          )}

          <Form.Item label="沙箱执行决策" name="sandboxExecution"
            rules={[{ required: true, message: '请选择沙箱执行决策' }]}
            extra="强制进沙箱：工具执行将路由至 K8s/Docker 隔离环境；不进沙箱：在宿主运行时直接执行">
            <Select options={SANDBOX_EXECUTION_OPTIONS} />
          </Form.Item>

          <Form.Item label="策略描述" name="description">
            <Input.TextArea rows={3} placeholder="说明此策略用途" maxLength={256} showCount />
          </Form.Item>

          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default SandboxPolicyTab;
