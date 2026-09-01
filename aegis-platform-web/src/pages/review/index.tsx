/**
 * @file 统一审核中心（管理控制台）
 * @description 管理员视角的统一审核中心：审核所有资源类型（智能体/SKILL/知识库/MCP/工具），
 *              支持详情页只读查看所有资源类型的完整信息，复用各资源详情展示逻辑。
 *              所有审核操作（通过/驳回）统一在此页面完成。
 * @author aegis
 * @since 2.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  Descriptions,
  Avatar,
} from 'antd';
import {
  CheckOutlined,
  CloseOutlined,
  EyeOutlined,
  ReloadOutlined,
  RobotOutlined,
  ThunderboltOutlined,
  DatabaseOutlined,
  ApiOutlined,
  ToolOutlined,
  UserOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { PageHeader } from '@/components/common/PageHeader';
import ResourceReadOnlyDetail from '@/components/common/ResourceReadOnlyDetail';
import {
  approveReview,
  getPendingReviews,
  rejectReview,
  type ResourceReview,
} from '@/api/review';
import { http } from '@/api/request';

/** 工具列表项 */
interface ToolVO {
  id?: string;
  toolCode?: string;
  toolName?: string;
  toolType?: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  securityLevel?: number;
  status?: string;
}

const { Text } = Typography;

/** 资源类型 → 图标（支持 MCP_SERVICE 向后兼容） */
const RESOURCE_TYPE_ICON: Record<string, React.ReactNode> = {
  AGENT: <RobotOutlined />,
  SKILL: <ThunderboltOutlined />,
  KNOWLEDGE_BASE: <DatabaseOutlined />,
  MCP: <ApiOutlined />,
  MCP_SERVICE: <ApiOutlined />,
  TOOL: <ToolOutlined />,
};

/** 资源类型 → 文案（支持 MCP_SERVICE 向后兼容） */
const RESOURCE_TYPE_TEXT: Record<string, string> = {
  AGENT: '智能体',
  SKILL: '技能',
  KNOWLEDGE_BASE: '知识库',
  MCP: 'MCP 服务',
  MCP_SERVICE: 'MCP 服务',
  TOOL: '工具',
};

/** 资源类型 → Tag 颜色（支持 MCP_SERVICE 向后兼容） */
const RESOURCE_TYPE_COLOR: Record<string, string> = {
  AGENT: 'purple',
  SKILL: 'blue',
  KNOWLEDGE_BASE: 'cyan',
  MCP: 'geekblue',
  MCP_SERVICE: 'geekblue',
  TOOL: 'green',
};

/** 审核页支持的资源类型（与 ResourceReadOnlyDetail 对齐） */
type ReviewResourceType = 'AGENT' | 'SKILL' | 'KNOWLEDGE_BASE' | 'MCP' | 'TOOL';

/** 规范化资源类型（统一映射到前端使用的键） */
const normalizeResourceType = (t?: string): ReviewResourceType | '' => {
  if (!t) return '';
  if (t === 'MCP_SERVICE') return 'MCP';
  return t as ReviewResourceType;
};

/** 审核状态 → Tag 颜色 / 文案 */
const REVIEW_STATUS_CFG: Record<string, { color: string; text: string }> = {
  PENDING: { color: 'processing', text: '待审核' },
  APPROVED: { color: 'success', text: '已通过' },
  REJECTED: { color: 'error', text: '已驳回' },
};

/** 单页加载条数（P2-ITEM-6：真实分页，默认 20 条/页） */
const DEFAULT_PAGE_SIZE = 20;

/** 驳回理由表单 */
interface RejectForm {
  reason: string;
}

const ReviewPage: React.FC = () => {
  const { message, modal } = App.useApp();

  // ===== 待审核列表（P2-ITEM-6：真实服务端分页 + 后端筛选） =====
  const [pendingData, setPendingData] = useState<ResourceReview[]>([]);
  const [pendingTotal, setPendingTotal] = useState(0);
  const [pendingKw, setPendingKw] = useState('');
  const [pendingKwInput, setPendingKwInput] = useState('');
  const [pendingType, setPendingType] = useState<string>('all');
  const [pendingPage, setPendingPage] = useState(1);
  const [pendingSize, setPendingSize] = useState(DEFAULT_PAGE_SIZE);
  const [pendingLoading, setPendingLoading] = useState(false);

  // ===== 详情 Modal =====
  const [detailVisible, setDetailVisible] = useState(false);
  const [detailRecord, setDetailRecord] = useState<ResourceReview | null>(null);
  const [detailTab, setDetailTab] = useState<string>('info');
  const [mcpTools, setMcpTools] = useState<ToolVO[]>([]);
  const [mcpToolsLoading, setMcpToolsLoading] = useState(false);

  // ===== 驳回 Modal =====
  const [rejectVisible, setRejectVisible] = useState(false);
  const [rejectLoading, setRejectLoading] = useState(false);
  const [rejectTarget, setRejectTarget] = useState<ResourceReview | null>(null);
  const [rejectForm] = Form.useForm<RejectForm>();

  /** 加载待审核列表（P2-ITEM-6：真实分页 + 后端 keyword/type 筛选） */
  const fetchPending = async () => {
    setPendingLoading(true);
    try {
      const res = await getPendingReviews({
        keyword: pendingKw || undefined,
        resourceType: pendingType !== 'all' ? pendingType : undefined,
        page: pendingPage,
        size: pendingSize,
      });
      setPendingData(res?.records ?? []);
      setPendingTotal(res?.total ?? 0);
    } catch {
      // 错误已由 http 拦截器统一提示
    } finally {
      setPendingLoading(false);
    }
  };

  // 筛选/分页变化时重新加载
  useEffect(() => {
    fetchPending();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pendingKw, pendingType, pendingPage, pendingSize]);

  /** 搜索提交（回车/搜索按钮） */
  const handleSearch = () => {
    setPendingKw(pendingKwInput.trim());
    setPendingPage(1);
  };

  /** 资源类型切换 */
  const handleTypeChange = (type: string) => {
    setPendingType(type);
    setPendingPage(1);
  };

  /** 加载 MCP 服务工具列表 */
  const loadMcpTools = async (resourceId?: string, resourceType?: string) => {
    const normalizedType = normalizeResourceType(resourceType);
    if (!resourceId || (normalizedType !== 'MCP' && resourceType !== 'MCP_SERVICE')) {
      setMcpTools([]);
      return;
    }
    setMcpToolsLoading(true);
    try {
      const res = await http.get<{ data: ToolVO[] }>(
        `/admin/resource/mcp/services/${resourceId}/tools`
      );
      setMcpTools(res?.data ?? []);
    } catch {
      setMcpTools([]);
    } finally {
      setMcpToolsLoading(false);
    }
  };

  /** 打开详情 */
  const openDetail = async (record: ResourceReview) => {
    setDetailRecord(record);
    setDetailTab('info');
    setDetailVisible(true);
    // 如果是 MCP 服务，加载工具列表
    await loadMcpTools(record.resourceId, record.resourceType);
  };

  /** 通过审核 */
  const handleApprove = (record: ResourceReview) => {
    const id = record.id;
    if (id == null) return;
    // P2-2：SYSTEM 智能体审核提示区分——常驻沙箱+API 启用，而非发布到市场
    const isSystemAgent = record.resourceType === 'AGENT'
      && record.resourceSubType === 'SYSTEM';
    const approveTip = isSystemAgent
      ? '审核通过后将启用 API 接口并绑定常驻沙箱池，供业务系统调用。'
      : '审核通过后将正式发布，用户即可在市场中看到该资源。';
    modal.confirm({
      title: '确认通过审核',
      icon: <CheckOutlined style={{ color: '#52c41a' }} />,
      content: (
        <div>
          <Text>将通过「<Text strong>{record.resourceName ?? '-'}</Text>」</Text>
          <div style={{ marginTop: 4 }}>
            <Tag color={RESOURCE_TYPE_COLOR[record.resourceType ?? ''] ?? 'default'}>
              {RESOURCE_TYPE_TEXT[record.resourceType ?? ''] ?? '-'}
            </Tag>
            {record.version && <Text type="secondary" style={{ marginLeft: 8 }}>v{record.version}</Text>}
          </div>
          <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
            {approveTip}
          </Text>
          {isSystemAgent && (
            <Alert
              type="info"
              showIcon
              style={{ marginTop: 8, fontSize: 12 }}
              message="系统智能体审核后将自动：1) 匹配并绑定常驻沙箱池 2) 启用 API 接口状态 3) 生成 API Key"
            />
          )}
        </div>
      ),
      okText: '确认通过',
      cancelText: '取消',
      okButtonProps: { type: 'primary' },
      onOk: async () => {
        try {
          await approveReview(id);
          setPendingData((prev) => prev.filter((r) => r.id !== id));
          message.success(`已通过「${record.resourceName ?? ''}」的审核`);
        } catch {
          // 错误已由 http 拦截器统一提示
        }
      },
    });
  };

  /** 打开驳回 Modal */
  const openRejectModal = (record: ResourceReview) => {
    setRejectTarget(record);
    rejectForm.resetFields();
    setRejectVisible(true);
  };

  /** 提交驳回 */
  const submitReject = async () => {
    try {
      const values = await rejectForm.validateFields();
      if (!rejectTarget?.id) return;
      const id = rejectTarget.id;
      setRejectLoading(true);
      await rejectReview(id, values.reason);
      setPendingData((prev) => prev.filter((r) => r.id !== id));
      message.success(
        `已驳回「${rejectTarget.resourceName ?? ''}」的审核`
      );
      setRejectVisible(false);
    } catch (err) {
      console.error(err);
    } finally {
      setRejectLoading(false);
    }
  };

  /** 资源类型渲染（自动规范化 MCP_SERVICE → MCP） */
  const renderResourceType = (t?: string) => {
    if (!t) return <Text type="secondary">-</Text>;
    const normalized = normalizeResourceType(t);
    return (
      <Tag color={RESOURCE_TYPE_COLOR[normalized] ?? 'default'} icon={RESOURCE_TYPE_ICON[normalized]}>
        {RESOURCE_TYPE_TEXT[normalized] ?? t}
      </Tag>
    );
  };

  /** 审核状态渲染 */
  const renderReviewStatus = (status?: string) => {
    if (!status) return <Text type="secondary">-</Text>;
    const cfg = REVIEW_STATUS_CFG[status] ?? { color: 'default', text: status };
    return <Tag color={cfg.color}>{cfg.text}</Tag>;
  };

  /** 表格列 */
  const columns: ColumnsType<ResourceReview> = [
    {
      title: '待审核对象',
      dataIndex: 'resourceName',
      width: 220,
      render: (text: string, record) => (
        <div>
          <a onClick={() => openDetail(record)} style={{ fontSize: 14, fontWeight: 500 }}>{text || '-'}</a>
          {record.resourceType && (
            <div style={{ marginTop: 2 }}>
              {renderResourceType(record.resourceType)}
              {record.version && <Text type="secondary" style={{ fontSize: 11 }}> v{record.version}</Text>}
            </div>
          )}
        </div>
      ),
    },
    {
      title: '申请人',
      dataIndex: 'applicantName',
      width: 120,
      render: (text: string, record) => (
        <div>
          <div>{text || <Text type="secondary">-</Text>}</div>
          {record.applicantUserId && (
            <Text type="secondary" style={{ fontSize: 11 }}>ID: {record.applicantUserId}</Text>
          )}
        </div>
      ),
    },
    {
      title: '安全等级',
      dataIndex: 'securityLevel',
      width: 90,
      render: (level?: number) => {
        if (level == null) return <Text type="secondary">-</Text>;
        const colors = ['green', 'blue', 'orange', 'red'];
        const labels = ['L1', 'L2', 'L3', 'L4'];
        const idx = Math.min(Math.max(level - 1, 0), 3);
        return <Tag color={colors[idx]}>{labels[idx]}</Tag>;
      },
    },
    {
      title: '提交时间',
      dataIndex: 'submitTime',
      width: 170,
      render: (v?: string) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'reviewStatus',
      width: 100,
      render: (status?: string) => renderReviewStatus(status),
    },
    {
      title: '操作',
      width: 240,
      fixed: 'right',
      render: (_, record) => (
        <Space size="small">
          <Button
            size="small"
            type="link"
            icon={<EyeOutlined />}
            onClick={() => openDetail(record)}
          >
            详情
          </Button>
          <Button
            size="small"
            type="primary"
            icon={<CheckOutlined />}
            onClick={() => handleApprove(record)}
          >
            通过
          </Button>
          <Button
            size="small"
            danger
            icon={<CloseOutlined />}
            onClick={() => openRejectModal(record)}
          >
            驳回
          </Button>
        </Space>
      ),
    },
  ];

  /** 详情 Modal 渲染 */
  const renderDetailModal = () => {
    if (!detailRecord) return null;
    const r = detailRecord;
    const status = r.reviewStatus ?? 'PENDING';
    const statusCfg = REVIEW_STATUS_CFG[status] ?? { color: 'default', text: status };
    const normalizedType = normalizeResourceType(r.resourceType);
    const isMcpResource = normalizedType === 'MCP' || r.resourceType === 'MCP_SERVICE';

    // 构建 Tabs items
    const tabsItems: Array<{ key: string; label: string; children: React.ReactNode }> = [
      {
        key: 'info',
        label: '📋 审核信息',
        children: (
          <div style={{ padding: '4px 0' }}>
            <Descriptions column={2} size="small" bordered style={{ marginBottom: 12 }}>
              <Descriptions.Item label="资源名称" span={2}>
                <Text strong style={{ fontSize: 15 }}>{r.resourceName || '-'}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="资源类型">
                {renderResourceType(r.resourceType)}
              </Descriptions.Item>
              <Descriptions.Item label="版本">
                {r.version || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="安全等级">
                {r.securityLevel ? `L${r.securityLevel}` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="审核状态">
                <Tag color={statusCfg.color}>{statusCfg.text}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="提交人">
                <Space>
                  <Avatar size="small" icon={<UserOutlined />} />
                  {r.applicantName || '-'}
                  {r.applicantUserId && <Text type="secondary" style={{ fontSize: 11 }}>ID: {r.applicantUserId}</Text>}
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="提交时间">
                {r.submitTime || '-'}
              </Descriptions.Item>
              {r.reviewTime && (
                <Descriptions.Item label="审核时间">
                  {String(r.reviewTime)}
                </Descriptions.Item>
              )}
              {r.rejectReason && (
                <Descriptions.Item label="驳回理由" span={2}>
                  <Text type="danger">{r.rejectReason}</Text>
                </Descriptions.Item>
              )}
            </Descriptions>
          </div>
        ),
      },
      {
        key: 'detail',
        label: '📦 资源详情',
        children: (
          <div style={{ padding: '4px 0' }}>
            {r.resourceId ? (
              <ResourceReadOnlyDetail
                resourceType={normalizedType as ReviewResourceType}
                resourceId={r.resourceId}
                basicInfo={{
                  resourceName: r.resourceName,
                  version: r.version,
                  securityLevel: r.securityLevel,
                }}
              />
            ) : (
              <Empty description="资源ID缺失，无法加载详情" />
            )}
          </div>
        ),
      },
    ];

    // MCP 服务添加工具列表 Tab
    if (isMcpResource) {
      tabsItems.push({
        key: 'tools',
        label: '🔧 工具列表',
        children: (
          <div style={{ padding: '4px 0' }}>
            {mcpToolsLoading ? (
              <Empty description="加载中..." />
            ) : mcpTools.length === 0 ? (
              <Empty description="该 MCP 服务暂未注册工具" />
            ) : (
              <Table<ToolVO>
                rowKey={(t) => String(t.id ?? t.toolCode ?? '')}
                size="small"
                pagination={false}
                bordered
                dataSource={mcpTools}
                columns={[
                  {
                    title: '工具编码',
                    dataIndex: 'toolCode',
                    width: 150,
                    render: (v: string) => <Text code>{v || '-'}</Text>,
                  },
                  {
                    title: '工具名称',
                    dataIndex: 'toolName',
                    width: 130,
                    render: (v: string) => v || '-',
                  },
                  {
                    title: '类型',
                    dataIndex: 'toolType',
                    width: 90,
                    render: (v: string) => v ? <Tag>{v}</Tag> : '-',
                  },
                  {
                    title: '描述',
                    dataIndex: 'description',
                    render: (v: string) => (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {v || '-'}
                      </Text>
                    ),
                  },
                  {
                    title: '安全等级',
                    dataIndex: 'securityLevel',
                    width: 90,
                    render: (level?: number) => {
                      if (level == null) return <Text type="secondary">-</Text>;
                      const colors = ['green', 'blue', 'orange', 'red'];
                      const labels = ['L1', 'L2', 'L3', 'L4'];
                      const idx = Math.min(Math.max(level - 1, 0), 3);
                      return <Tag color={colors[idx]}>{labels[idx]}</Tag>;
                    },
                  },
                  {
                    title: '状态',
                    dataIndex: 'status',
                    width: 80,
                    render: (v: string) => v ? <Tag color={v === 'ACTIVE' ? 'success' : 'default'}>{v}</Tag> : '-',
                  },
                ]}
              />
            )}
          </div>
        ),
      });
    }

    // 变更说明 Tab
    tabsItems.push({
      key: 'diff',
      label: '📝 变更说明',
      children: (
        <div style={{ padding: '4px 0' }}>
          <Alert
            type="info"
            showIcon
            message="变更说明"
            description={r.changeSummary || '申请人未提供变更说明'}
            style={{ marginBottom: 12 }}
          />
          {r.rejectReason && (
            <Alert
              type="error"
              showIcon
              message="最近一次驳回理由"
              description={r.rejectReason}
            />
          )}
        </div>
      ),
    });

    return (
      <Modal
        title={
          <Space>
            <span>审核详情</span>
            <Tag color={RESOURCE_TYPE_COLOR[normalizedType] ?? 'default'} icon={RESOURCE_TYPE_ICON[normalizedType]}>
              {RESOURCE_TYPE_TEXT[normalizedType] ?? '-'}
            </Tag>
            <Tag color={statusCfg.color}>{statusCfg.text}</Tag>
          </Space>
        }
        open={detailVisible}
        onCancel={() => setDetailVisible(false)}
        footer={[
          <Button key="close" onClick={() => setDetailVisible(false)}>
            关闭
          </Button>,
          <Button
            key="approve"
            type="primary"
            icon={<CheckOutlined />}
            onClick={() => {
              setDetailVisible(false);
              handleApprove(r);
            }}
          >
            通过审核
          </Button>,
          <Button
            key="reject"
            danger
            icon={<CloseOutlined />}
            onClick={() => {
              setDetailVisible(false);
              openRejectModal(r);
            }}
          >
            驳回
          </Button>,
        ]}
        width={960}
        bodyStyle={{ maxHeight: 'calc(100vh - 200px)', overflowY: 'auto' }}
      >
        <Tabs
          activeKey={detailTab}
          onChange={setDetailTab}
          size="small"
          style={{ marginBottom: 16 }}
          items={tabsItems}
        />
      </Modal>
    );
  };

  /** 驳回 Modal 渲染 */
  const renderRejectModal = () => (
    <Modal
      title="驳回审核"
      open={rejectVisible}
      onCancel={() => setRejectVisible(false)}
      onOk={submitReject}
      confirmLoading={rejectLoading}
      okText="提交驳回"
      cancelText="取消"
      okButtonProps={{ danger: true }}
      width={560}
    >
      {rejectTarget && (
        <div style={{ marginBottom: 16 }}>
          <Text>
            将驳回「<Text strong>{rejectTarget.resourceName || '-'}</Text>」
            {rejectTarget.resourceType && (
              <Text type="secondary">
                {' '}
                ({RESOURCE_TYPE_TEXT[rejectTarget.resourceType] ?? '-'})
              </Text>
            )}
            {rejectTarget.version ? ` v${rejectTarget.version}` : ''}
            的审核申请。
          </Text>
        </div>
      )}
      <Form<RejectForm> form={rejectForm} layout="vertical">
        <Form.Item
          name="reason"
          label="驳回理由"
          rules={[
            { required: true, message: '请填写驳回理由' },
            { min: 5, message: '驳回理由不少于 5 字' },
            { max: 500, message: '驳回理由不超过 500 字' },
          ]}
          tooltip="驳回理由将通知申请人，请明确指出需修改的内容"
        >
          <Input.TextArea
            rows={4}
            placeholder="请说明驳回原因，例如：智能体描述不够清晰，请补充具体使用场景后重新提交"
            showCount
            maxLength={500}
          />
        </Form.Item>
      </Form>
    </Modal>
  );

  /** 类型筛选选项 */
  const typeOptions = [
    { value: 'all', label: '全部类型' },
    ...Object.entries(RESOURCE_TYPE_TEXT).map(([value, label]) => ({ value, label })),
  ];

  return (
    <div>
      <PageHeader
        title="统一审核中心"
        desc="管理员审核入口 · 集中审核智能体、SKILL、知识库、MCP、工具等所有资源的发布申请"
      />

      {/* 统计卡片 */}
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={`共 ${pendingTotal} 项待审核`}
        description={
          <Text style={{ fontSize: 13 }}>
            {pendingTotal === 0 ? '当前暂无待审核项' : '点击列表查看详情，支持通过或驳回操作'}
          </Text>
        }
      />

      <Card>
        <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between' }}>
          <Space wrap>
            <Input.Search
              placeholder="搜索对象名称/提交人"
              value={pendingKwInput}
              onChange={(e) => setPendingKwInput(e.target.value)}
              onSearch={handleSearch}
              allowClear
              enterButton
              style={{ width: 280 }}
            />
            <Select
              value={pendingType}
              onChange={handleTypeChange}
              style={{ width: 140 }}
              options={typeOptions}
            />
          </Space>
          <Button icon={<ReloadOutlined />} onClick={fetchPending} loading={pendingLoading}>
            刷新
          </Button>
        </Space>

        <Table<ResourceReview>
          rowKey={(r) => String(r.id ?? '')}
          columns={columns}
          dataSource={pendingData}
          loading={pendingLoading}
          locale={{ emptyText: <Empty description="暂无待审核记录" /> }}
          pagination={{
            current: pendingPage,
            pageSize: pendingSize,
            total: pendingTotal,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条待审核`,
            onChange: (p, sz) => {
              setPendingPage(p);
              setPendingSize(sz);
            },
          }}
          scroll={{ x: 1200 }}
          size="middle"
        />
      </Card>

      {renderDetailModal()}
      {renderRejectModal()}
    </div>
  );
};

export default ReviewPage;
