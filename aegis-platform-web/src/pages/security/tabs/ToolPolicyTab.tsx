/**
 * @file 工具管控 Tab
 * @description 工具类型 × 安全级别二维决策矩阵、审批规则、运行时监管
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  Modal,
  Popover,
  Row,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { InfoCircleOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  createToolPolicy,
  getToolPolicies,
  updateToolPolicy,
} from '@/api/security';
import {
  LEVEL_LABEL,
  LEVEL_POLICY_DETAILS,
  MATRIX_LEVEL_COLUMNS,
  SECURITY_LEVEL_OPTIONS,
  SECURITY_LEVELS,
  TOOL_ACTION_CYCLE,
  TOOL_ACTION_MAP,
  TOOL_ACTION_OPTIONS,
  TOOL_TYPE_LABEL,
  TOOL_TYPE_OPTIONS,
} from '../constants';
import type { ToolPolicyDTO, ToolPolicyFormValues, ToolMatrixRow } from '../types';

const { Text } = Typography;
const { TextArea } = Input;

const ToolPolicyTab: React.FC = () => {
  const { message } = App.useApp();
  const [toolPolicies, setToolPolicies] = useState<ToolPolicyDTO[]>([]);
  const [toolLoading, setToolLoading] = useState(false);
  const [toolModalVisible, setToolModalVisible] = useState(false);
  const [toolSubmitLoading, setToolSubmitLoading] = useState(false);
  const [selectedToolTypes, setSelectedToolTypes] = useState<React.Key[]>([]);
  const [toolForm] = Form.useForm<ToolPolicyFormValues>();
  const [runtimeSwitches, setRuntimeSwitches] = useState<Record<string, boolean>>({
    audit: true,
    outbound: true,
    mask: true,
    circuit: true,
  });

  /** 加载工具策略 */
  const loadToolPolicies = async () => {
    setToolLoading(true);
    try {
      const res = await getToolPolicies({ page: 1, size: 200 });
      setToolPolicies((res.records || []) as ToolPolicyDTO[]);
    } catch {
      /* 错误已由请求拦截器提示 */
    } finally {
      setToolLoading(false);
    }
  };

  useEffect(() => {
    loadToolPolicies();
  }, []);

  /** 查找指定 toolType + securityLevel 的策略 */
  const findPolicy = (toolType: string, securityLevel: number) =>
    toolPolicies.find(
      (p) => p.toolType === toolType && Number(p.securityLevel) === securityLevel,
    );

  /** 矩阵单元格点击：循环切换 ALLOW -> APPROVE -> REJECT */
  const handleMatrixCellClick = async (toolType: string, securityLevel: number) => {
    const existing = findPolicy(toolType, securityLevel);
    const toolLabel = TOOL_TYPE_LABEL[toolType] ?? toolType;
    const levelLabel = LEVEL_LABEL[securityLevel] ?? `L${securityLevel}`;
    setToolLoading(true);
    try {
      if (existing?.id) {
        const currentIdx = TOOL_ACTION_CYCLE.indexOf(existing.action || 'ALLOW');
        const nextAction = TOOL_ACTION_CYCLE[(currentIdx + 1) % TOOL_ACTION_CYCLE.length];
        await updateToolPolicy(existing.id, { action: nextAction });
        message.success(`${toolLabel} · ${levelLabel} -> ${TOOL_ACTION_MAP[nextAction].text}`);
      } else {
        await createToolPolicy({ toolType, securityLevel, action: 'ALLOW', enabled: true });
        message.success(`${toolLabel} · ${levelLabel} -> 放行`);
      }
      await loadToolPolicies();
    } catch {
      /* 错误已由请求拦截器提示 */
    } finally {
      setToolLoading(false);
    }
  };

  /** 打开新增策略弹窗 */
  const openToolModal = () => {
    toolForm.resetFields();
    toolForm.setFieldsValue({ toolType: 'READONLY', securityLevel: 1, action: 'ALLOW', enabled: true });
    setToolModalVisible(true);
  };

  /** 提交新增策略 */
  const submitToolPolicy = async () => {
    try {
      const values = await toolForm.validateFields();
      setToolSubmitLoading(true);
      await createToolPolicy(values as Partial<ToolPolicyDTO>);
      message.success('策略已创建');
      setToolModalVisible(false);
      await loadToolPolicies();
    } catch (err) {
      if ((err as { errorFields?: unknown })?.errorFields) return; // 表单校验错误
    } finally {
      setToolSubmitLoading(false);
    }
  };

  /** 批量启用/禁用选中工具类型的策略 */
  const handleBatchToggle = async (enabled: boolean) => {
    if (selectedToolTypes.length === 0) {
      message.warning('请先选择工具类型');
      return;
    }
    const targets = toolPolicies.filter((p) =>
      selectedToolTypes.includes(p.toolType || ''),
    );
    if (targets.length === 0) {
      message.warning('所选工具类型暂无策略记录');
      return;
    }
    setToolLoading(true);
    try {
      await Promise.all(
        targets.map((p) => updateToolPolicy(p.id!, { enabled })),
      );
      message.success(`已${enabled ? '启用' : '禁用'} ${targets.length} 条策略`);
      setSelectedToolTypes([]);
      await loadToolPolicies();
    } catch {
      /* 错误已由请求拦截器提示 */
    } finally {
      setToolLoading(false);
    }
  };

  const matrixRows: ToolMatrixRow[] = TOOL_TYPE_OPTIONS.map((o) => ({
    key: o.value,
    toolType: o.value,
  }));

  const matrixColumns: ColumnsType<ToolMatrixRow> = [
    {
      title: '工具类型',
      dataIndex: 'toolType',
      width: 140,
      render: (v: string) => <Text strong>{TOOL_TYPE_LABEL[v] ?? v}</Text>,
    },
    ...MATRIX_LEVEL_COLUMNS.map((col) => ({
      title: col.title,
      key: `level-${col.level}`,
      width: 120,
      align: 'center' as const,
      render: (_v: unknown, record: ToolMatrixRow) => {
        const policy = findPolicy(record.toolType, col.level);
        const action = policy?.action;
        if (action && TOOL_ACTION_MAP[action]) {
          const item = TOOL_ACTION_MAP[action];
          return (
            <Tag
              color={item.color}
              style={{ cursor: 'pointer', margin: 0, userSelect: 'none' }}
              onClick={() => handleMatrixCellClick(record.toolType, col.level)}
            >
              {item.text}
            </Tag>
          );
        }
        return (
          <Tag
            color="#f3f4f6"
            style={{ cursor: 'pointer', margin: 0, userSelect: 'none', color: '#9ca3af', border: '1px dashed #d1d5db' }}
            onClick={() => handleMatrixCellClick(record.toolType, col.level)}
          >
            未配置
          </Tag>
        );
      },
    })),
  ];

  return (
    <div>
      <Card
        title={
          <Space>
            <span>决策矩阵</span>
            <Popover
              placement="right"
              trigger="click"
              content={
                <div style={{ width: 480, maxHeight: 400, overflowY: 'auto' }}>
                  <div style={{ marginBottom: 12 }}>
                    <Text strong>安全级别说明</Text>
                  </div>
                  {SECURITY_LEVELS.map(lv => (
                    <div key={lv.key} style={{ marginBottom: 10, padding: '6px 10px', background: '#fafafa', borderRadius: 4 }}>
                      <Space>
                        <div style={{
                          width: 28, height: 28, borderRadius: 6, background: lv.color,
                          display: 'flex', alignItems: 'center', justifyContent: 'center',
                          color: '#fff', fontWeight: 700, fontSize: 12,
                        }}>{lv.key}</div>
                        <Text strong>{lv.name}</Text>
                      </Space>
                      <div style={{ marginTop: 4, fontSize: 12, color: '#666' }}>{lv.desc}</div>
                    </div>
                  ))}
                  <Table
                    rowKey="key"
                    size="small"
                    pagination={false}
                    dataSource={LEVEL_POLICY_DETAILS}
                    columns={[
                      { title: '级别', dataIndex: 'level', width: 70 },
                      { title: '数据隔离', dataIndex: 'dataIsolation' },
                      { title: '操作权限', dataIndex: 'operationPermission' },
                    ]}
                  />
                </div>
              }
            >
              <InfoCircleOutlined style={{ color: '#1677ff', cursor: 'help', fontSize: 14 }} />
            </Popover>
          </Space>
        }
        extra={
          <Space>
            <Button
              size="small"
              disabled={selectedToolTypes.length === 0}
              onClick={() => handleBatchToggle(true)}
            >
              批量启用
            </Button>
            <Button
              size="small"
              disabled={selectedToolTypes.length === 0}
              onClick={() => handleBatchToggle(false)}
            >
              批量禁用
            </Button>
            <Button type="primary" size="small" icon={<PlusOutlined />} onClick={openToolModal}>
              新增策略
            </Button>
          </Space>
        }
      >
        <Table<ToolMatrixRow>
          rowKey="key"
          columns={matrixColumns}
          dataSource={matrixRows}
          loading={toolLoading}
          pagination={false}
          size="middle"
          bordered
          rowSelection={{
            selectedRowKeys: selectedToolTypes,
            onChange: (keys) => setSelectedToolTypes(keys),
          }}
        />
      </Card>

      <Card title="审批规则" style={{ marginTop: 16 }}>
        <Descriptions column={2} size="small">
          <Descriptions.Item label="审批方式">
            <Space>
              <Switch
                checked
                onChange={(v) => message.info(`人工审批已${v ? '开启' : '关闭'}`)}
              />
              <Text>人工审批（默认）</Text>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="SLA">2 个工作日</Descriptions.Item>
          <Descriptions.Item label="单次有效期">30 天</Descriptions.Item>
          <Descriptions.Item label="审批人配置">
            <Button type="link" size="small" onClick={() => message.info('打开审批人配置')}>
              配置审批人
            </Button>
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="运行时监管机制" style={{ marginTop: 16 }}>
        <Row gutter={16}>
          <Col xs={24} sm={12} md={6}>
            <Card size="small" styles={{ body: { padding: 16 } }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text>调用审计</Text>
                <Switch
                  checked={runtimeSwitches.audit}
                  onChange={(v) => setRuntimeSwitches((s) => ({ ...s, audit: v }))}
                />
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                记录全部工具调用明细
              </Text>
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card size="small" styles={{ body: { padding: 16 } }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text>出站策略</Text>
                <Switch
                  checked={runtimeSwitches.outbound}
                  onChange={(v) => setRuntimeSwitches((s) => ({ ...s, outbound: v }))}
                />
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                拦截非白名单域名调用
              </Text>
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card size="small" styles={{ body: { padding: 16 } }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text>返回脱敏</Text>
                <Switch
                  checked={runtimeSwitches.mask}
                  onChange={(v) => setRuntimeSwitches((s) => ({ ...s, mask: v }))}
                />
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                对返回结果应用脱敏规则
              </Text>
            </Card>
          </Col>
          <Col xs={24} sm={12} md={6}>
            <Card size="small" styles={{ body: { padding: 16 } }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <Text>异常熔断</Text>
                <Switch
                  checked={runtimeSwitches.circuit}
                  onChange={(v) => setRuntimeSwitches((s) => ({ ...s, circuit: v }))}
                />
              </div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                异常率超阈值自动熔断
              </Text>
            </Card>
          </Col>
        </Row>
      </Card>

      <Modal
        title="新增工具策略"
        open={toolModalVisible}
        onCancel={() => setToolModalVisible(false)}
        onOk={submitToolPolicy}
        confirmLoading={toolSubmitLoading}
        width={560}
        okText="创建"
        destroyOnClose
      >
        <Form<ToolPolicyFormValues> form={toolForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="toolType"
                label="工具类型"
                rules={[{ required: true, message: '请选择工具类型' }]}
              >
                <Select options={TOOL_TYPE_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="securityLevel"
                label="安全级别"
                rules={[{ required: true, message: '请选择安全级别' }]}
              >
                <Select options={SECURITY_LEVEL_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                name="action"
                label="处置动作"
                rules={[{ required: true, message: '请选择处置动作' }]}
              >
                <Select options={TOOL_ACTION_OPTIONS} placeholder="请选择" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="enabled" label="启用状态" valuePropName="checked">
                <Switch checkedChildren="启用" unCheckedChildren="停用" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="description" label="策略描述">
                <TextArea rows={3} placeholder="可选，描述该策略的用途" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </div>
  );
};

export default ToolPolicyTab;
