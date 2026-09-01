/**
 * @file MCP 管理
 * @description MCP 中心 - 单 Tab 架构（MCP 市场 + 我的订阅 + 使用说明）：
 *   - MCP市场：管理员发布的 MCP 服务，支持筛选/搜索/订阅/取消订阅
 *   - 我的订阅：已订阅的 MCP 服务列表
 *   - 使用说明：平台使用指南
 * @author aegis
 * @since 2.0.0
 * @changed 2.2: 移除安全等级显示（后台管理配置），优化 UX
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  App,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  Button,
  Spin,
} from 'antd';
import {
  ApiOutlined,
  LinkOutlined,
  DisconnectOutlined,
  SearchOutlined,
  ReloadOutlined,
  InfoCircleOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import type { McpProtocol, McpServer, ToolVO } from '@/types/resource';
import { mcpApi, extractList } from '@/api/resource';

const { Text } = Typography;

/** 协议筛选选项 */
const PROTOCOL_FILTER_OPTIONS = [
  { value: '', label: '全部协议' },
  { value: 'SSE', label: 'SSE' },
  { value: 'STREAMABLE_HTTP', label: 'Streamable HTTP' },
  { value: 'STDIO', label: 'Stdio' },
];

/** 协议 → Tag 颜色 */
const PROTOCOL_TAG: Record<McpProtocol, string> = {
  SSE: 'blue',
  STREAMABLE_HTTP: 'cyan',
  STDIO: 'default',
};

/** 工具类型 → Tag 颜色 */
const TOOL_TYPE_TAG: Record<string, string> = {
  READONLY: 'green',
  READ_WRITE: 'orange',
  WRITE: 'red',
};

const McpPage: React.FC = () => {
  const { message } = App.useApp();
  const [activeTab, setActiveTab] = useState<'market' | 'mine'>('market');

  // ===== 筛选状态 =====
  const [keyword, setKeyword] = useState('');
  const [keywordInput, setKeywordInput] = useState('');
  const [protocolFilter, setProtocolFilter] = useState('');

  // MCP市场数据
  const [servers, setServers] = useState<McpServer[]>([]);
  // 已订阅的服务 ID 集合
  const [subscribedServerIds, setSubscribedServerIds] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);

  // ===== Tool 详情弹窗状态 =====
  const [toolDetailVisible, setToolDetailVisible] = useState(false);
  const [toolDetailLoading, setToolDetailLoading] = useState(false);
  const [currentTools, setCurrentTools] = useState<ToolVO[]>([]);
  const [currentServiceName, setCurrentServiceName] = useState('');

  /** 打开工具详情 */
  const openToolDetail = async (serviceId: string, serviceName: string) => {
    setCurrentServiceName(serviceName);
    setToolDetailLoading(true);
    setToolDetailVisible(true);
    try {
      const res = await mcpApi.getServiceTools(serviceId);
      setCurrentTools(res || []);
    } catch {
      setCurrentTools([]);
      message.error('获取工具列表失败，请确认 MCP 服务可用');
    } finally {
      setToolDetailLoading(false);
    }
  };

  // ===== 前端过滤后的市场数据 =====
  const filteredServers = useMemo(() => {
    return servers.filter((s) => {
      // 关键词筛选
      if (keyword) {
        const kw = keyword.toLowerCase();
        if (
          !(s.mcpName?.toLowerCase().includes(kw) ||
            s.mcpCode?.toLowerCase().includes(kw) ||
            s.endpoint?.toLowerCase().includes(kw) ||
            s.provider?.toLowerCase().includes(kw))
        ) {
          return false;
        }
      }
      // 协议筛选
      if (protocolFilter && s.protocol !== protocolFilter) return false;
      return true;
    });
  }, [servers, keyword, protocolFilter]);

  const hasFilter = !!keyword || !!protocolFilter;

  const resetFilter = () => {
    setKeyword('');
    setKeywordInput('');
    setProtocolFilter('');
  };

  // 初始化加载
  useEffect(() => {
    const loadAll = async () => {
      setLoading(true);
      try {
        const serverRes = await mcpApi.marketServices({ page: 1, size: 100 });
        const list = extractList(serverRes);
        setServers(list);
        // 构建已订阅 ID 集合
        const ids = new Set<string>();
        list.forEach((s) => {
          if (s.subscribed) ids.add(s.id);
        });
        setSubscribedServerIds(ids);
      } catch {
        message.error('加载 MCP 列表失败');
      } finally {
        setLoading(false);
      }
    };
    loadAll();
  }, [message]);

  /** 切换订阅状态 */
  const toggleSubscribe = async (id: string, name: string) => {
    const alreadySubscribed = subscribedServerIds.has(id);
    try {
      if (!alreadySubscribed) {
        await mcpApi.subscribeService(id);
        setSubscribedServerIds((prev) => new Set(prev).add(id));
        message.success(`已订阅「${name}」，可立即使用`);
      } else {
        await mcpApi.unsubscribeService(id);
        setSubscribedServerIds((prev) => {
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        message.success(`已取消订阅「${name}」`);
      }
    } catch {
      // 错误已由 http 拦截器处理
    }
  };

  // ===== MCP市场 表格列 =====
  const marketColumns: ColumnsType<McpServer> = [
    {
      title: '协议',
      dataIndex: 'protocol',
      width: 120,
      render: (p: McpProtocol) => <Tag color={PROTOCOL_TAG[p]}>{p}</Tag>,
    },
    { title: '编码', dataIndex: 'mcpCode', width: 180 },
    {
      title: '名称',
      dataIndex: 'mcpName',
      width: 180,
      render: (v: string) => <Text strong>{v}</Text>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      width: 240,
      ellipsis: true,
      render: (v?: string) => v || '-',
    },
    {
      title: '端点',
      dataIndex: 'endpoint',
      width: 240,
      render: (v: string) => <Text copyable style={{ fontSize: 12 }}>{v}</Text>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (_: string, row) => {
        const subscribed = subscribedServerIds.has(row.id);
        return subscribed ? (
          <Tag color="success">已订阅</Tag>
        ) : (
          <Tag color="default">未订阅</Tag>
        );
      },
    },
    {
      title: '工具数',
      dataIndex: 'toolCount',
      width: 80,
      render: (v?: number) => v ?? 0,
    },
    {
      title: '提供方',
      dataIndex: 'provider',
      width: 120,
      render: (v?: string) => v || '-',
    },
    {
      title: '操作',
      width: 200,
      fixed: 'right',
      render: (_, row) => {
        const subscribed = subscribedServerIds.has(row.id);
        return (
          <div style={{ display: 'flex', gap: 10, fontSize: 13, whiteSpace: 'nowrap' }}>
            <a style={{ color: '#4f46e5' }} onClick={() => openToolDetail(row.id, row.mcpName)}>
              <InfoCircleOutlined /> 查看工具
            </a>
            {subscribed ? (
              <a style={{ color: '#ff4d4f' }} onClick={() => toggleSubscribe(row.id, row.mcpName)}>
                <DisconnectOutlined /> 取消订阅
              </a>
            ) : (
              <a style={{ color: '#10b981' }} onClick={() => toggleSubscribe(row.id, row.mcpName)}>
                <LinkOutlined /> 订阅
              </a>
            )}
          </div>
        );
      },
    },
  ];

  const subscribedServers = servers.filter((s) => subscribedServerIds.has(s.id));
  const marketCount = servers.length;
  const subscribedCount = subscribedServerIds.size;

  const subscribedColumns: ColumnsType<McpServer> = [
    {
      title: '协议',
      dataIndex: 'protocol',
      width: 120,
      render: (p: McpProtocol) => <Tag color={PROTOCOL_TAG[p]}>{p}</Tag>,
    },
    { title: '编码', dataIndex: 'mcpCode', width: 180 },
    {
      title: '名称',
      dataIndex: 'mcpName',
      width: 180,
      render: (v: string) => <Text strong>{v}</Text>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      width: 240,
      ellipsis: true,
      render: (v?: string) => v || '-',
    },
    {
      title: '端点',
      dataIndex: 'endpoint',
      width: 240,
      render: (v: string) => <Text copyable style={{ fontSize: 12 }}>{v}</Text>,
    },
    {
      title: '工具数',
      dataIndex: 'toolCount',
      width: 80,
      render: (v?: number) => v ?? 0,
    },
    {
      title: '操作',
      width: 120,
      fixed: 'right',
      render: (_, row) => (
        <a style={{ color: '#ff4d4f' }} onClick={() => toggleSubscribe(row.id, row.mcpName)}>
          <DisconnectOutlined /> 取消订阅
        </a>
      ),
    },
  ];

  return (
    <div style={{ height: '100%', overflowY: 'auto', paddingRight: 4 }}>
      <PageHeader
        title="MCP中心"
        desc="MCP 服务统一管理 · 市场浏览订阅"
      />

      <BigTabs
        tabs={[
          { key: 'market', label: '🏪 MCP市场', badge: marketCount },
          { key: 'mine', label: '📦 我的订阅', badge: subscribedCount },
        ]}
        active={activeTab}
        onChange={(key) => setActiveTab(key as 'market' | 'mine')}
      />

      {/* 市场提示 */}
      <div style={{
        marginBottom: 16, padding: '8px 12px', background: '#eff6ff',
        borderLeft: '3px solid #3b82f6', borderRadius: 4, fontSize: 12, color: '#1e40af',
      }}>
        💡 <strong>MCP 使用流程：</strong> 浏览市场 → 订阅服务（即订即用）→ 在智能体中自动加载。
        MCP 服务由管理员审核发布，订阅后即可在通用智能体对话中使用。
      </div>

      {/* MCP市场 Tab */}
      {activeTab === 'market' && (
        <div>
          {/* 统计信息 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 13, color: '#6b7280' }}>
              <Tag color="blue" icon={<ApiOutlined />}>系统发布 {marketCount}</Tag>
              <Tag color="green" icon={<LinkOutlined />}>已订阅 {subscribedCount}</Tag>
              {hasFilter && <Tag color="orange">筛选结果 {filteredServers.length}</Tag>}
            </div>
          </div>

          {/* 筛选工具栏 */}
          <Space style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}>
            <Space>
              <Input
                prefix={<SearchOutlined />}
                placeholder="搜索名称/编码/端点/提供方..."
                value={keywordInput}
                onChange={(e) => {
                  setKeywordInput(e.target.value);
                  if (!e.target.value) {
                    setKeyword('');
                  }
                }}
                onPressEnter={() => setKeyword(keywordInput)}
                style={{ width: 280 }}
                allowClear
              />
              <Select
                placeholder="全部协议"
                value={protocolFilter || undefined}
                onChange={(v) => setProtocolFilter(v ?? '')}
                style={{ width: 140 }}
                allowClear
                options={PROTOCOL_FILTER_OPTIONS.filter((o) => o.value !== '')}
              />
              {hasFilter && (
                <Button icon={<ReloadOutlined />} onClick={resetFilter}>
                  重置
                </Button>
              )}
            </Space>
          </Space>

          {/* 当前筛选条件 */}
          {hasFilter && (
            <Space style={{ marginBottom: 12, fontSize: 12 }}>
              <Text type="secondary">筛选：</Text>
              {keyword && <Tag color="blue">关键词: {keyword}</Tag>}
              {protocolFilter && <Tag color="cyan">协议: {protocolFilter}</Tag>}
            </Space>
          )}

          <Table
            rowKey="id"
            loading={loading}
            columns={marketColumns}
            dataSource={filteredServers}
            pagination={false}
            scroll={{ x: 1200 }}
            size="middle"
          />
        </div>
      )}

      {/* 我的订阅 Tab */}
      {activeTab === 'mine' && (
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Text strong style={{ fontSize: 15 }}>
                <LinkOutlined /> 已订阅的 MCP 服务
              </Text>
              <Tag color="blue">{subscribedCount}</Tag>
            </div>
          </div>
          <Table
            rowKey="id"
            loading={loading}
            columns={subscribedColumns}
            dataSource={subscribedServers}
            pagination={false}
            scroll={{ x: 1000 }}
            size="middle"
          />
        </div>
      )}

      {/* 工具详情Modal */}
      <Modal
        title={
          <Space>
            <ApiOutlined />
            <span>MCP 工具列表 - <Text strong>{currentServiceName}</Text></span>
          </Space>
        }
        open={toolDetailVisible}
        onCancel={() => setToolDetailVisible(false)}
        footer={[
          <Button key="close" onClick={() => setToolDetailVisible(false)}>
            关闭
          </Button>,
        ]}
        width={680}
        destroyOnClose
      >
        {toolDetailLoading ? (
          <div style={{ textAlign: 'center', padding: 40 }}>
            <Spin tip="正在通过 MCP 协议查询工具列表..." />
          </div>
        ) : currentTools.length === 0 ? (
          <div style={{ textAlign: 'center', padding: 40, color: '#9ca3af' }}>
            <ApiOutlined style={{ fontSize: 48, marginBottom: 16 }} />
            <div style={{ fontSize: 14, marginBottom: 8 }}>该 MCP 服务当前未暴露任何工具</div>
            <div style={{ fontSize: 12, color: '#6b7280' }}>
              请确认服务正在运行且协议配置正确
            </div>
          </div>
        ) : (
          <div>
            <div style={{ marginBottom: 12, fontSize: 12, color: '#6b7280' }}>
              共发现 <Text strong style={{ color: '#4f46e5' }}>{currentTools.length}</Text> 个工具，
              通过 MCP 协议实时获取
            </div>
            <Table
              rowKey="toolCode"
              size="small"
              pagination={false}
              dataSource={currentTools}
              columns={[
                {
                  title: '工具名称',
                  dataIndex: 'toolName',
                  width: 160,
                  render: (v: string) => <Text strong>{v}</Text>,
                },
                {
                  title: '描述',
                  dataIndex: 'description',
                  ellipsis: true,
                  render: (v?: string) => v || '-',
                },
                {
                  title: '类型',
                  dataIndex: 'toolType',
                  width: 100,
                  render: (v: string) => (
                    <Tag color={TOOL_TYPE_TAG[v] || 'default'}>
                      {v || 'UNKNOWN'}
                    </Tag>
                  ),
                },
              ]}
            />
          </div>
        )}
      </Modal>
    </div>
  );
};

export default McpPage;
