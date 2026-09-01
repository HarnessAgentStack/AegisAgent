/**
 * @file 出站策略 Tab
 * @description 出站白名单/黑名单的增删改查 + 出站流量概览 + 默认策略配置
 * @author wang.zhen
 * @since 2.0.0
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
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
  Segmented,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CloudUploadOutlined,
  CloudServerOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import {
  createOutboundPolicy,
  deleteOutboundPolicy,
  getOutboundPolicies,
  updateOutboundPolicy,
} from '@/api/security';
import {
  COLOR,
  OUTBOUND_SCOPE_MAP,
  OUTBOUND_SCOPE_OPTIONS,
} from '../constants';
import type { OutboundPolicyDTO, OutboundPolicyFormValues } from '../types';

const { Text, Title } = Typography;

/** 策略类型常量 */
const POLICY_TYPE = {
  WHITELIST: 'WHITELIST_DOMAIN',
  BLACKLIST: 'BLACKLIST_IP',
} as const;

/** 过期选项 */
const EXPIRE_OPTIONS = [
  { value: 0, label: '长期有效' },
  { value: 24, label: '24 小时' },
  { value: 72, label: '3 天' },
  { value: 168, label: '7 天' },
  { value: 720, label: '30 天' },
  { value: 2160, label: '90 天' },
];

const OutboundPolicyTab: React.FC = () => {
  const { message } = App.useApp();

  // ===== 状态 =====
  const [policies, setPolicies] = useState<OutboundPolicyDTO[]>([]);
  const [loading, setLoading] = useState(false);

  // 列表过滤
  const [activeTab, setActiveTab] = useState<'whitelist' | 'blacklist'>('whitelist');
  const [keyword, setKeyword] = useState('');
  const [filterScope, setFilterScope] = useState<string>('all');
  const [filterStatus, setFilterStatus] = useState<string>('all');

  // 弹窗
  const [modalVisible, setModalVisible] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [editing, setEditing] = useState<OutboundPolicyDTO | null>(null);
  const [form] = Form.useForm<OutboundPolicyFormValues & { expireHours?: number }>();
  const policyTypeWatch = Form.useWatch('policyType', form);

  // 全局出站配置（暂存前端，后续对接后端）
  const [defaultAction, setDefaultAction] = useState<'allow' | 'reject' | 'approve'>('approve');
  const [logRetention, setLogRetention] = useState<number>(30);
  const [alertOnViolation, setAlertOnViolation] = useState<boolean>(true);
  const [showExpire, setShowExpire] = useState<boolean>(true);

  /** 加载数据 */
  const loadData = useCallback(async () => {
    setLoading(true);
    try {
      const res = await getOutboundPolicies({ page: 1, size: 500 });
      setPolicies((res.records || []) as OutboundPolicyDTO[]);
    } catch {
      /* 拦截器已提示 */
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  // ===== 统计数据 =====
  const stats = useMemo(() => {
    const total = policies.filter((p) => !p.deleted);
    const whitelist = total.filter((p) => p.policyType !== POLICY_TYPE.BLACKLIST);
    const blacklist = total.filter((p) => p.policyType === POLICY_TYPE.BLACKLIST);
    const activeWhitelist = whitelist.filter((p) => p.enabled !== false).length;
    const activeBlacklist = blacklist.filter((p) => p.enabled !== false).length;
    return {
      whitelist: whitelist.length,
      blacklist: blacklist.length,
      activeWhitelist,
      activeBlacklist,
      pendingApproval: 0, // TODO: 对接 HITL 审批
    };
  }, [policies]);

  // ===== 过滤后列表 =====
  const filteredList = useMemo(() => {
    const typeFilter = activeTab === 'whitelist'
      ? POLICY_TYPE.WHITELIST
      : POLICY_TYPE.BLACKLIST;
    return policies
      .filter((p) => p.policyType === typeFilter && !p.deleted)
      .filter((p) => {
        if (keyword) {
          const target = activeTab === 'whitelist' ? p.domain : p.ipCidr;
          return (target ?? '').toLowerCase().includes(keyword.toLowerCase());
        }
        return true;
      })
      .filter((p) => filterScope === 'all' || p.applicableScope === filterScope)
      .filter((p) => {
        if (filterStatus === 'all') return true;
        if (filterStatus === 'enabled') return p.enabled !== false;
        if (filterStatus === 'disabled') return p.enabled === false;
        return true;
      });
  }, [policies, activeTab, keyword, filterScope, filterStatus]);

  // ===== 操作 =====
  const openModal = (record?: OutboundPolicyDTO) => {
    setEditing(record ?? null);
    if (record) {
      form.setFieldsValue({
        policyType: record.policyType,
        domain: record.domain ?? '',
        ipCidr: record.ipCidr ?? '',
        applicableScope: record.applicableScope ?? 'ALL',
        enabled: record.enabled ?? true,
        expireHours: record.expireTime ? calcExpireHours(record.expireTime) : 0,
      });
    } else {
      form.resetFields();
      form.setFieldsValue({
        policyType: activeTab === 'whitelist' ? POLICY_TYPE.WHITELIST : POLICY_TYPE.BLACKLIST,
        applicableScope: 'ALL',
        enabled: true,
        expireHours: 0,
      });
    }
    setModalVisible(true);
  };

  const submitForm = async () => {
    try {
      const values = await form.validateFields();
      setSubmitLoading(true);
      // 计算过期时间
      let expireTime: string | undefined;
      if (values.expireHours && values.expireHours > 0) {
        expireTime = dayjs().add(values.expireHours, 'hour').format('YYYY-MM-DD HH:mm:ss');
      }
      const payload: Partial<OutboundPolicyDTO> = {
        policyType: values.policyType,
        domain: values.domain,
        ipCidr: values.ipCidr,
        applicableScope: values.applicableScope,
        enabled: values.enabled,
        expireTime,
      };
      if (editing?.id) {
        await updateOutboundPolicy(editing.id, payload);
        message.success('出站策略已更新');
      } else {
        await createOutboundPolicy(payload);
        message.success('出站策略已创建');
      }
      setModalVisible(false);
      await loadData();
    } catch (err) {
      if ((err as { errorFields?: unknown })?.errorFields) return;
      message.error('操作失败，请稍后重试');
    } finally {
      setSubmitLoading(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteOutboundPolicy(id);
      message.success('已删除');
      await loadData();
    } catch {
      /* 拦截器已提示 */
    }
  };

  // ===== 列定义 =====
  const baseColumns: ColumnsType<OutboundPolicyDTO> = [
    {
      title: activeTab === 'whitelist' ? '域名 / 主机' : 'IP / CIDR',
      dataIndex: activeTab === 'whitelist' ? 'domain' : 'ipCidr',
      width: 260,
      render: (v?: string) => (
        <Space>
          <CloudServerOutlined style={{ color: COLOR.primary }} />
          <Text code style={{ fontSize: 13 }}>
            {v || '-'}
          </Text>
        </Space>
      ),
    },
    {
      title: '适用范围',
      dataIndex: 'applicableScope',
      width: 110,
      render: (s?: string) => {
        const item = OUTBOUND_SCOPE_MAP[s ?? 'ALL'];
        return <Tag color={item?.color ?? '#6b7280'}>{item?.text ?? s}</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 90,
      render: (enabled?: boolean) => {
        const on = enabled !== false;
        return (
          <Tag color={on ? COLOR.success : '#9ca3af'}>
            {on ? '启用' : '停用'}
          </Tag>
        );
      },
    },
    {
      title: '过期时间',
      dataIndex: 'expireTime',
      width: 180,
      render: (v?: string, _record?: OutboundPolicyDTO) => {
        if (!v) return <Text type="secondary">长期有效</Text>;
        const isExpired = dayjs(v).isBefore(dayjs());
        return isExpired ? (
          <Tooltip title="已过期">
            <Tag color={COLOR.danger} icon={<WarningOutlined />}>
              已过期 {v}
            </Tag>
          </Tooltip>
        ) : (
          <Text type="secondary">{v}</Text>
        );
      },
    },
    {
      title: '操作',
      width: 150,
      fixed: 'right',
      render: (_v: unknown, record) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title={`确认删除该${activeTab === 'whitelist' ? '白名单域名' : '黑名单 IP'}？`}
            description="删除后不可恢复"
            onConfirm={() => record.id && handleDelete(record.id)}
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
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* ===== 顶部概览 ===== */}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" bordered={false} style={{ background: 'linear-gradient(135deg, #eef2ff 0%, #e0e7ff 100%)' }}>
            <Space direction="vertical" size={2}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                <CloudUploadOutlined /> 出站白名单
              </Text>
              <Title level={3} style={{ margin: 0, color: COLOR.primary }}>
                {stats.whitelist}
              </Title>
              <Text type="secondary" style={{ fontSize: 12 }}>
                其中启用 {stats.activeWhitelist} 条
              </Text>
            </Space>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" bordered={false} style={{ background: 'linear-gradient(135deg, #fef2f2 0%, #fee2e2 100%)' }}>
            <Space direction="vertical" size={2}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                <SafetyCertificateOutlined /> IP 黑名单
              </Text>
              <Title level={3} style={{ margin: 0, color: COLOR.danger }}>
                {stats.blacklist}
              </Title>
              <Text type="secondary" style={{ fontSize: 12 }}>
                其中启用 {stats.activeBlacklist} 条
              </Text>
            </Space>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" bordered={false} style={{ background: 'linear-gradient(135deg, #fefce8 0%, #fef9c3 100%)' }}>
            <Space direction="vertical" size={2}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                <ThunderboltOutlined /> 待审批出站
              </Text>
              <Title level={3} style={{ margin: 0, color: COLOR.warning }}>
                {stats.pendingApproval}
              </Title>
              <Text type="secondary" style={{ fontSize: 12 }}>
                需要人工审批放行
              </Text>
            </Space>
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card size="small" bordered={false} style={{ background: 'linear-gradient(135deg, #f0fdf4 0%, #dcfce7 100%)' }}>
            <Space direction="vertical" size={2}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                默认出站策略
              </Text>
              <Title level={3} style={{ margin: 0, color: COLOR.success, textTransform: 'capitalize' }}>
                {defaultAction === 'allow' ? '允许' : defaultAction === 'reject' ? '拒绝' : '审批'}
              </Title>
              <Text type="secondary" style={{ fontSize: 12 }}>
                所有未匹配的出站请求
              </Text>
            </Space>
          </Card>
        </Col>
      </Row>

      {/* ===== 策略列表主卡片 ===== */}
      <Card
        title={
          <Space>
            <CloudServerOutlined style={{ color: COLOR.primary }} />
            <strong>出站策略列表</strong>
          </Space>
        }
        extra={
          <Space>
            <Segmented
              value={activeTab}
              onChange={(v) => setActiveTab(v as 'whitelist' | 'blacklist')}
              options={[
                { label: `白名单 (${stats.whitelist})`, value: 'whitelist' },
                { label: `黑名单 (${stats.blacklist})`, value: 'blacklist' },
              ]}
            />
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => openModal()}
            >
              新增{activeTab === 'whitelist' ? '白名单域名' : '黑名单 IP'}
            </Button>
          </Space>
        }
      >
        {/* 过滤条 */}
        <div style={{ display: 'flex', gap: 8, marginBottom: 16, flexWrap: 'wrap' }}>
          <Input.Search
            placeholder={activeTab === 'whitelist' ? '搜索域名，如 api.example.com' : '搜索 IP / CIDR，如 192.168'}
            allowClear
            style={{ width: 260 }}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onSearch={setKeyword}
          />
          <Select
            style={{ width: 130 }}
            value={filterScope}
            onChange={setFilterScope}
            options={[
              { value: 'all', label: '全部范围' },
              ...OUTBOUND_SCOPE_OPTIONS,
            ]}
          />
          <Select
            style={{ width: 130 }}
            value={filterStatus}
            onChange={setFilterStatus}
            options={[
              { value: 'all', label: '全部状态' },
              { value: 'enabled', label: '已启用' },
              { value: 'disabled', label: '已停用' },
            ]}
          />
          <Text type="secondary" style={{ marginLeft: 'auto', fontSize: 12 }}>
            共 {filteredList.length} 条
          </Text>
        </div>

        <Table<OutboundPolicyDTO>
          rowKey="id"
          columns={baseColumns}
          dataSource={filteredList}
          loading={loading}
          pagination={{ pageSize: 10, showSizeChanger: true, showTotal: (t) => `共 ${t} 条` }}
          size="middle"
          rowClassName={(r) => (r.enabled === false ? 'ant-table-row-disabled' : '')}
          locale={{ emptyText: activeTab === 'whitelist' ? '暂无白名单域名，点击右上角「新增」添加' : '暂无黑名单 IP，点击右上角「新增」添加' }}
        />
      </Card>

      {/* ===== 全局出站配置 ===== */}
      <Card
        title={
          <Space>
            <SafetyCertificateOutlined style={{ color: COLOR.warning }} />
            <strong>全局出站策略配置</strong>
          </Space>
        }
      >
        <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 16 }}>
          以下配置定义所有出站请求的默认处理行为与运维参数。白名单/黑名单匹配不上的请求，按默认策略处理。
        </Text>
        <Row gutter={[24, 24]}>
          <Col xs={24} sm={12} md={8}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <Text strong>默认出站动作</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                未命中白/黑名单的出站请求如何处理
              </Text>
              <Segmented
                value={defaultAction}
                onChange={(v) => setDefaultAction(v as 'allow' | 'reject' | 'approve')}
                options={[
                  { label: '放行', value: 'allow' },
                  { label: '审批', value: 'approve' },
                  { label: '拒绝', value: 'reject' },
                ]}
              />
            </div>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <Text strong>出站日志保留</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                外部域名调用审计日志保留时长
              </Text>
              <Select
                style={{ width: '100%' }}
                value={logRetention}
                onChange={setLogRetention}
                options={[
                  { value: 7, label: '7 天' },
                  { value: 30, label: '30 天（默认）' },
                  { value: 90, label: '90 天' },
                  { value: 180, label: '180 天' },
                  { value: 365, label: '1 年' },
                ]}
              />
            </div>
          </Col>
          <Col xs={24} sm={12} md={8}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <Text strong>异常出站告警</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                检测到出站违规请求时发送告警通知
              </Text>
              <Switch
                checked={alertOnViolation}
                onChange={setAlertOnViolation}
                checkedChildren="已开启"
                unCheckedChildren="已关闭"
              />
            </div>
          </Col>
        </Row>
        <Row gutter={[24, 24]} style={{ marginTop: 16 }}>
          <Col xs={24} sm={12} md={8}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <Text strong>过期策略</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                新建白名单/黑名单默认是否带过期时间
              </Text>
              <Switch
                checked={showExpire}
                onChange={setShowExpire}
                checkedChildren="启用过期"
                unCheckedChildren="长期有效"
              />
            </div>
          </Col>
        </Row>
      </Card>

      {/* ===== 策略弹窗 ===== */}
      <Modal
        title={editing ? '编辑出站策略' : '新增出站策略'}
        open={modalVisible}
        onCancel={() => setModalVisible(false)}
        onOk={submitForm}
        confirmLoading={submitLoading}
        width={560}
        okText={editing ? '保存' : '创建'}
        destroyOnClose
      >
        <Form<OutboundPolicyFormValues & { expireHours?: number }>
          form={form}
          layout="vertical"
          initialValues={{ enabled: true, expireHours: 0 }}
        >
          <Form.Item
            name="policyType"
            label="策略类型"
            rules={[{ required: true, message: '请选择策略类型' }]}
          >
            <Select
              options={[
                { value: POLICY_TYPE.WHITELIST, label: '白名单 - 允许访问的域名' },
                { value: POLICY_TYPE.BLACKLIST, label: '黑名单 - 禁止访问的 IP' },
              ]}
            />
          </Form.Item>

          {policyTypeWatch === POLICY_TYPE.BLACKLIST ? (
            <Form.Item
              name="ipCidr"
              label="IP / CIDR 地址"
              rules={[{ required: true, message: '请输入 IP 或 CIDR' }]}
              extra="支持单个 IP（如 192.168.1.100）或网段（如 192.168.1.0/24）"
            >
              <Input placeholder="如 192.168.1.0/24" style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          ) : (
            <Form.Item
              name="domain"
              label="域名"
              rules={[
                { required: true, message: '请输入域名' },
                {
                  pattern: /^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/,
                  message: '域名格式不正确，如 api.example.com',
                },
              ]}
              extra="填写需要放行的目标域名，支持二级域名"
            >
              <Input placeholder="如 api.example.com" style={{ fontFamily: 'monospace' }} />
            </Form.Item>
          )}

          <Form.Item
            name="applicableScope"
            label="适用范围"
            rules={[{ required: true, message: '请选择适用范围' }]}
            extra="该策略对哪些主体生效：全部智能体 / 指定智能体 / 指定部门"
          >
            <Select options={OUTBOUND_SCOPE_OPTIONS} />
          </Form.Item>

          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                name="expireHours"
                label="过期时间"
                extra="到期后自动失效"
              >
                <Select options={EXPIRE_OPTIONS} />
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
    </div>
  );
};

/** 根据 expireTime 字符串计算离现在的小时数（用于编辑回填） */
function calcExpireHours(expireTimeStr: string): number {
  const target = dayjs(expireTimeStr);
  const hours = target.diff(dayjs(), 'hour');
  if (hours <= 0) return 0;
  // 匹配预设选项
  const matched = EXPIRE_OPTIONS.find((o) => o.value === hours);
  return matched ? hours : 0;
}

export default OutboundPolicyTab;
