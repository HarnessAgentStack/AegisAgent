/**
 * @file 租户管理
 * @description 租户列表（关键词 + 状态筛选）、新增/编辑、配额配置、冻结/解冻、用量查看
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useCallback, useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Table,
  Tag,
} from 'antd';
import {
  EditOutlined,
  LockOutlined,
  PieChartOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  UnlockOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import type { PageResult } from '@/api/types';
import { PageHeader } from '@/components/common/PageHeader';
import type { Tenant, TenantQuota, TenantUsage } from '@/types/tenant';
import {
  getTenantPage,
  createTenant,
  updateTenant,
  updateTenantQuota,
  freezeTenant,
  unfreezeTenant,
  getTenantUsage,
} from '@/api/tenant';

/** 租户类型 → Tag 配置 */
const TENANT_TYPE_TAG: Record<string, { color: string; text: string }> = {
  HQ: { color: 'blue', text: '总部' },
  SUBSIDIARY: { color: 'cyan', text: '分公司' },
  DIVISION: { color: 'purple', text: '事业部' },
};

/** 租户状态 → Tag 配置 */
const TENANT_STATUS_TAG: Record<string, { color: string; text: string }> = {
  NORMAL: { color: 'success', text: '正常' },
  FROZEN: { color: 'error', text: '冻结' },
};

/** 租户表单值 */
interface TenantFormValues {
  tenantCode: string;
  tenantName: string;
  tenantType: string;
  contactName?: string;
  contactPhone?: string;
  expireTime?: Dayjs;
  remark?: string;
}

/** 配额表单值 */
interface QuotaFormValues {
  maxAgents: number;
  maxResources: number;
  maxConcurrentSessions: number;
  maxTokenPerDay: number;
  maxTokenPerMonth: number;
  maxSandboxes: number;
  maxStorageGb: number;
}

/** 用量展示项 */
interface UsageItem {
  label: string;
  value: number;
  unit: string;
}

const TenantPage: React.FC = () => {
  const { message } = App.useApp();
  const [tenantForm] = Form.useForm<TenantFormValues>();
  const [quotaForm] = Form.useForm<QuotaFormValues>();

  // 列表与查询
  const [tenants, setTenants] = useState<Tenant[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  // 新增/编辑弹窗
  const [tenantModalOpen, setTenantModalOpen] = useState(false);
  const [tenantModalLoading, setTenantModalLoading] = useState(false);
  const [editingTenantId, setEditingTenantId] = useState<string | null>(null);

  // 配额弹窗
  const [quotaModalOpen, setQuotaModalOpen] = useState(false);
  const [quotaModalLoading, setQuotaModalLoading] = useState(false);
  const [quotaTenantId, setQuotaTenantId] = useState<string | null>(null);

  // 用量弹窗
  const [usageModalOpen, setUsageModalOpen] = useState(false);
  const [usageLoading, setUsageLoading] = useState(false);
  const [usage, setUsage] = useState<TenantUsage | null>(null);
  const [usageTenantName, setUsageTenantName] = useState('');

  /** 拉取租户分页列表 */
  const fetchTenants = useCallback(() => {
    setLoading(true);
    getTenantPage({ keyword: keyword || undefined, status: statusFilter, page, size: pageSize })
      .then((res) => {
        const data = res as PageResult<Tenant> & { records?: Tenant[] };
        const list = data.list ?? data.records ?? [];
        setTenants(list);
        setTotal(data.total ?? list.length);
      })
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setLoading(false));
  }, [keyword, statusFilter, page, pageSize]);

  useEffect(() => {
    fetchTenants();
  }, [fetchTenants]);

  // ===== 新增 / 编辑租户 =====
  const openTenantModal = (record?: Tenant) => {
    tenantForm.resetFields();
    if (record) {
      setEditingTenantId(record.id);
      tenantForm.setFieldsValue({
        tenantCode: record.tenantCode,
        tenantName: record.tenantName,
        tenantType: record.tenantType,
        contactName: record.contactName,
        contactPhone: record.contactPhone,
        expireTime: record.expireTime ? dayjs(record.expireTime) : undefined,
        remark: record.remark,
      });
    } else {
      setEditingTenantId(null);
      tenantForm.setFieldsValue({ tenantType: 'HQ' });
    }
    setTenantModalOpen(true);
  };

  const submitTenant = async () => {
    try {
      const values = await tenantForm.validateFields();
      setTenantModalLoading(true);
      const payload: Partial<Tenant> = {
        tenantCode: values.tenantCode,
        tenantName: values.tenantName,
        tenantType: values.tenantType,
        contactName: values.contactName,
        contactPhone: values.contactPhone,
        expireTime: values.expireTime ? values.expireTime.format('YYYY-MM-DD') : undefined,
        remark: values.remark,
      };
      if (editingTenantId !== null) {
        await updateTenant(editingTenantId, payload);
        message.success('租户更新成功');
      } else {
        await createTenant(payload);
        message.success('租户新增成功');
      }
      setTenantModalOpen(false);
      fetchTenants();
    } catch (err) {
      console.error(err);
    } finally {
      setTenantModalLoading(false);
    }
  };

  // ===== 配额 =====
  const openQuotaModal = (record: Tenant) => {
    quotaForm.resetFields();
    setQuotaTenantId(record.id);
    quotaForm.setFieldsValue({
      maxAgents: 50,
      maxResources: 200,
      maxConcurrentSessions: 100,
      maxTokenPerDay: 1_000_000,
      maxTokenPerMonth: 20_000_000,
      maxSandboxes: 20,
      maxStorageGb: 100,
    });
    setQuotaModalOpen(true);
  };

  const submitQuota = async () => {
    if (quotaTenantId === null) return;
    try {
      const values = await quotaForm.validateFields();
      setQuotaModalLoading(true);
      const quota: Partial<TenantQuota> = { ...values };
      await updateTenantQuota(quotaTenantId, quota);
      message.success('配额更新成功');
      setQuotaModalOpen(false);
    } catch (err) {
      console.error(err);
    } finally {
      setQuotaModalLoading(false);
    }
  };

  // ===== 冻结 / 解冻 =====
  const handleFreeze = async (record: Tenant) => {
    try {
      await freezeTenant(record.id);
      message.success(`已冻结「${record.tenantName}」`);
      fetchTenants();
    } catch {
      /* 弹错已处理 */
    }
  };

  const handleUnfreeze = async (record: Tenant) => {
    try {
      await unfreezeTenant(record.id);
      message.success(`已解冻「${record.tenantName}」`);
      fetchTenants();
    } catch {
      /* 弹错已处理 */
    }
  };

  // ===== 用量 =====
  const openUsageModal = (record: Tenant) => {
    setUsageTenantName(record.tenantName);
    setUsage(null);
    setUsageModalOpen(true);
    setUsageLoading(true);
    getTenantUsage(record.id)
      .then((data) => setUsage(data))
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setUsageLoading(false));
  };

  /** 用量展示项 */
  const usageItems: UsageItem[] = usage
    ? [
        { label: '智能体数', value: usage.agentCount, unit: '个' },
        { label: '资源数', value: usage.resourceCount, unit: '个' },
        { label: '并发会话', value: usage.concurrentSessionCount, unit: '路' },
        { label: '今日 Token', value: usage.tokenUsedToday, unit: '' },
        { label: '本月 Token', value: usage.tokenUsedThisMonth, unit: '' },
        { label: '沙箱占用', value: usage.sandboxUsed, unit: '个' },
        { label: '存储占用', value: usage.storageUsedGb, unit: 'GB' },
      ]
    : [];

  // ===== 列定义 =====
  const columns: ColumnsType<Tenant> = [
    { title: '租户编码', dataIndex: 'tenantCode', width: 140 },
    { title: '租户名称', dataIndex: 'tenantName', width: 180 },
    {
      title: '类型',
      dataIndex: 'tenantType',
      width: 100,
      render: (t: string) => {
        const cfg = TENANT_TYPE_TAG[t] ?? { color: 'default', text: t };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: string) => {
        const cfg = TENANT_STATUS_TAG[s] ?? { color: 'default', text: s };
        return <Tag color={cfg.color}>{cfg.text}</Tag>;
      },
    },
    { title: '联系人', dataIndex: 'contactName', width: 110 },
    { title: '联系电话', dataIndex: 'contactPhone', width: 130 },
    {
      title: '到期时间',
      dataIndex: 'expireTime',
      width: 130,
      render: (v?: string) => (v ? dayjs(v).format('YYYY-MM-DD') : '—'),
    },
    {
      title: '操作',
      width: 280,
      fixed: 'right',
      render: (_: unknown, record: Tenant) => (
        <Space size="small">
          <a onClick={() => openTenantModal(record)}>
            <EditOutlined /> 编辑
          </a>
          <a onClick={() => openQuotaModal(record)}>
            <PieChartOutlined /> 配额
          </a>
          {record.status === 'FROZEN' ? (
            <a onClick={() => handleUnfreeze(record)} style={{ color: '#10b981' }}>
              <UnlockOutlined /> 解冻
            </a>
          ) : (
            <Popconfirm title="确认冻结该租户？" onConfirm={() => handleFreeze(record)}>
              <a style={{ color: '#ef4444' }}>
                <LockOutlined /> 冻结
              </a>
            </Popconfirm>
          )}
          <a onClick={() => openUsageModal(record)}>
            <PieChartOutlined /> 用量
          </a>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <PageHeader title="租户管理" desc="租户列表 · 新增编辑 · 配额配置 · 冻结/解冻 · 用量查看" />
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 8 }}>
          <Space wrap>
            <Input
              placeholder="租户编码 / 名称"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onPressEnter={() => {
                setPage(1);
                fetchTenants();
              }}
              style={{ width: 220 }}
              allowClear
            />
            <Select
              placeholder="状态筛选"
              value={statusFilter}
              onChange={(v) => setStatusFilter(v)}
              allowClear
              style={{ width: 140 }}
              options={[
                { value: 'NORMAL', label: '正常' },
                { value: 'FROZEN', label: '冻结' },
              ]}
            />
            <Button icon={<SearchOutlined />} type="primary" onClick={() => { setPage(1); fetchTenants(); }}>
              查询
            </Button>
            <Button icon={<ReloadOutlined />} onClick={() => { setKeyword(''); setStatusFilter(undefined); setPage(1); fetchTenants(); }}>
              重置
            </Button>
          </Space>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openTenantModal()}>
            新增租户
          </Button>
        </div>
        <Table<Tenant>
          rowKey="id"
          columns={columns}
          dataSource={tenants}
          loading={loading}
          scroll={{ x: 1300 }}
          pagination={{
            current: page,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p);
              setPageSize(s);
            },
          }}
        />
      </Card>

      {/* 新增 / 编辑租户弹窗 */}
      <Modal
        title={editingTenantId !== null ? '编辑租户' : '新增租户'}
        open={tenantModalOpen}
        onCancel={() => setTenantModalOpen(false)}
        onOk={submitTenant}
        confirmLoading={tenantModalLoading}
        width={720}
        destroyOnClose
      >
        <Form<TenantFormValues> form={tenantForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="tenantCode" label="租户编码" rules={[{ required: true, message: '请输入租户编码' }]}>
                <Input placeholder="如 HQ-001" disabled={editingTenantId !== null} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="tenantName" label="租户名称" rules={[{ required: true, message: '请输入租户名称' }]}>
                <Input placeholder="如 总部" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="tenantType" label="租户类型" rules={[{ required: true, message: '请选择租户类型' }]}>
                <Select
                  options={[
                    { value: 'HQ', label: '总部' },
                    { value: 'SUBSIDIARY', label: '分公司' },
                    { value: 'DIVISION', label: '事业部' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="expireTime" label="到期时间">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="contactName" label="联系人">
                <Input placeholder="联系人姓名" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="contactPhone" label="联系电话">
                <Input placeholder="联系电话" />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="remark" label="备注">
                <Input.TextArea rows={3} placeholder="备注信息" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      {/* 配额弹窗 */}
      <Modal
        title="配额配置"
        open={quotaModalOpen}
        onCancel={() => setQuotaModalOpen(false)}
        onOk={submitQuota}
        confirmLoading={quotaModalLoading}
        width={720}
        destroyOnClose
      >
        <Form<QuotaFormValues> form={quotaForm} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item name="maxAgents" label="智能体数量上限" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="maxResources" label="资源数量上限" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="maxConcurrentSessions" label="并发会话上限" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="maxSandboxes" label="沙箱数量上限" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="maxTokenPerDay" label="每日 Token 上限" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="maxTokenPerMonth" label="每月 Token 上限" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item name="maxStorageGb" label="存储上限（GB）" rules={[{ required: true }]}>
                <InputNumber min={0} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      {/* 用量弹窗 */}
      <Modal
        title={`租户用量 - ${usageTenantName}`}
        open={usageModalOpen}
        onCancel={() => setUsageModalOpen(false)}
        footer={null}
        width={640}
        destroyOnClose
      >
        {usageLoading ? (
          <div style={{ textAlign: 'center', padding: 40, color: '#9ca3af' }}>加载中...</div>
        ) : usage ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {usageItems.map((item) => {
              // 计数类指标按量级填充（参考量 100），Token 类封顶 100%
              const percent = item.unit === '' ? 100 : Math.min(100, item.value);
              return (
                <div key={item.label}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                    <span style={{ color: '#374151' }}>{item.label}</span>
                    <span style={{ fontWeight: 600 }}>
                      {item.value.toLocaleString()} {item.unit}
                    </span>
                  </div>
                  <Progress percent={percent} showInfo={false} size="small" />
                </div>
              );
            })}
            {usage.statDate && (
              <div style={{ textAlign: 'right', color: '#9ca3af', fontSize: 12 }}>
                统计日期：{usage.statDate}
              </div>
            )}
          </div>
        ) : (
          <div style={{ textAlign: 'center', padding: 40, color: '#9ca3af' }}>暂无用量数据</div>
        )}
      </Modal>
    </div>
  );
};

export default TenantPage;
