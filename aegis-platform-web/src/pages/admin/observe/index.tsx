/**
 * @file 可观测监控主页
 * @description 会话级视图：会话汇总列表 + 懒加载 Trace 子表 + 统计卡片
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Col,
  Input,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  SearchOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons';
import { PageHeader } from '@/components/common/PageHeader';
import {
  getSessions,
  getSessionTraces,
  getStats,
  type SessionSummary,
  type TraceRecord,
  type ObserveStats,
  type PageResult,
} from '@/api/observe';
import TraceDetailDrawer from './TraceDetailDrawer';
import SessionDetailPanel from './components/SessionDetailPanel';
import { formatDuration, formatPercent } from '@/utils/format';

const { Text } = Typography;

type DimensionKey = 'sessionId' | 'userId' | 'agentId';

const DIMENSION_TABS: { key: DimensionKey; label: string; placeholder: string }[] = [
  { key: 'sessionId', label: '会话', placeholder: '请输入会话 ID' },
  { key: 'userId', label: '用户', placeholder: '请输入用户 ID' },
  { key: 'agentId', label: '智能体', placeholder: '请输入智能体 ID' },
];

const STATUS_MAP: Record<string, { color: string; text: string }> = {
  RUNNING: { color: 'processing', text: '运行中' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  TIMEOUT: { color: 'warning', text: '超时' },
};

interface SessionRow extends SessionSummary {
  key: string;
}

function formatDateTime(iso?: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return `${d.getFullYear()}-${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}`;
}

const AdminObservePage: React.FC = () => {
  const [dimension, setDimension] = useState<DimensionKey>('sessionId');
  const [dimensionValue, setDimensionValue] = useState('');

  const [sessions, setSessions] = useState<SessionSummary[]>([]);
  const [sessionTotal, setSessionTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [loading, setLoading] = useState(false);

  const [expandedSessionId, setExpandedSessionId] = useState<string | null>(null);
  const [sessionTraces, setSessionTraces] = useState<Map<string, TraceRecord[]>>(new Map());

  const [stats, setStats] = useState<ObserveStats | null>(null);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [selectedTraceId, setSelectedTraceId] = useState<string | null>(null);

  const [sessionPanelOpen, setSessionPanelOpen] = useState(false);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);

  const fetchSessions = useCallback(() => {
    setLoading(true);
    const params: { page?: number; size?: number; sessionId?: string; userId?: number; agentId?: number } = {
      page,
      size: pageSize,
    };
    if (dimensionValue.trim()) {
      if (dimension === 'userId') {
        const num = Number(dimensionValue);
        if (!isNaN(num)) params.userId = num;
      } else if (dimension === 'agentId') {
        const num = Number(dimensionValue);
        if (!isNaN(num)) params.agentId = num;
      } else {
        params.sessionId = dimensionValue.trim();
      }
    }
    getSessions(params)
      .then((res: PageResult<SessionSummary>) => {
        setSessionTotal(res.total || 0);
        setSessions(res.list || []);
        // reset expanded state when data refreshes
        setExpandedSessionId(null);
        setSessionTraces(new Map());
      })
      .catch(() => {
        setSessions([]);
        setSessionTotal(0);
      })
      .finally(() => setLoading(false));
  }, [dimension, dimensionValue, page, pageSize]);

  const fetchStats = useCallback(() => {
    getStats()
      .then((res) => setStats(res))
      .catch(() => setStats(null));
  }, []);

  const handleSearch = () => {
    setPage(1);
    fetchSessions();
    fetchStats();
  };

  const handleReset = () => {
    setDimensionValue('');
    setPage(1);
  };

  const handleExpand = async (expanded: boolean, record: SessionSummary) => {
    if (expanded && !sessionTraces.has(record.sessionId)) {
      try {
        const res = await getSessionTraces(record.sessionId, { page: 1, size: 100 });
        setSessionTraces((prev) => new Map(prev).set(record.sessionId, res.list || []));
      } catch {
        setSessionTraces((prev) => new Map(prev).set(record.sessionId, []));
      }
    }
    setExpandedSessionId(expanded ? record.sessionId : null);
  };

  const handleTraceRowClick = (traceId: string) => {
    setSelectedTraceId(traceId);
    setDrawerOpen(true);
  };

  useEffect(() => {
    fetchSessions();
    fetchStats();
  }, [fetchSessions, fetchStats]);

  const traceColumns: ColumnsType<TraceRecord> = [
    {
      title: 'Trace ID',
      dataIndex: 'traceId',
      width: 200,
      render: (v: string) => (
        <Text copyable style={{ fontSize: 12, fontFamily: 'monospace', cursor: 'pointer' }}>
          {v}
        </Text>
      ),
    },
    {
      title: '智能体',
      dataIndex: 'agentName',
      width: 130,
      render: (v: unknown, record: TraceRecord) =>
        (v as string) || (record.agentId != null ? `Agent#${record.agentId}` : '-'),
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      width: 100,
      render: (v?: number) => (
        <Text style={{ color: v && v > 5000 ? '#ff4d4f' : undefined }}>
          {formatDuration(v)}
        </Text>
      ),
    },
    {
      title: 'Token',
      width: 100,
      render: (_v: unknown, record: TraceRecord) =>
        ((record.tokenInput || 0) + (record.tokenOutput || 0)).toLocaleString(),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s: string) => {
        const info = STATUS_MAP[s] || { color: 'default', text: s };
        return <Tag color={info.color}>{info.text}</Tag>;
      },
    },
    {
      title: '开始时间',
      dataIndex: 'startTime',
      width: 170,
      render: (v: string) => formatDateTime(v),
    },
    {
      title: '操作',
      width: 80,
      render: (_v: unknown, record: TraceRecord) => (
        <Button
          type="link"
          size="small"
          onClick={(e) => {
            e.stopPropagation();
            handleTraceRowClick(record.traceId);
          }}
        >
          详情
        </Button>
      ),
    },
  ];

  const renderTraceTable = (sessionId: string) => {
    const traces = sessionTraces.get(sessionId) || [];
    return (
      <Table
        columns={traceColumns}
        dataSource={traces}
        rowKey="traceId"
        pagination={false}
        size="small"
        locale={{ emptyText: '暂无对话记录' }}
        onRow={(record) => ({
          style: { cursor: 'pointer' },
          onClick: () => {
            setSelectedTraceId(record.traceId);
            setDrawerOpen(true);
          },
        })}
      />
    );
  };

  const sessionColumns: ColumnsType<SessionRow> = [
    {
      title: '会话 ID',
      dataIndex: 'sessionId',
      width: 240,
      fixed: 'left',
      render: (v: string) => (
        <Text copyable style={{ fontSize: 12, fontFamily: 'monospace' }}>
          {v}
        </Text>
      ),
    },
    {
      title: '智能体',
      dataIndex: 'agentName',
      width: 140,
      render: (v: unknown, record: SessionRow) =>
        (v as string) || (record.agentId != null ? `Agent#${record.agentId}` : '-'),
    },
    {
      title: '用户',
      dataIndex: 'userName',
      width: 120,
      render: (v: unknown, record: SessionRow) =>
        (v as string) || (record.userId != null ? `User#${record.userId}` : '-'),
    },
    {
      title: '轮次数',
      dataIndex: 'traceCount',
      width: 90,
      sorter: (a, b) => a.traceCount - b.traceCount,
    },
    {
      title: '成功率',
      width: 110,
      sorter: (a, b) => {
        const ra = a.traceCount ? a.successCount / a.traceCount : 0;
        const rb = b.traceCount ? b.successCount / b.traceCount : 0;
        return ra - rb;
      },
      render: (_v: unknown, record: SessionRow) => {
        if (!record.traceCount) return '-';
        const ratio = record.successCount / record.traceCount;
        const rate = ratio * 100;
        const color = rate >= 90 ? '#52c41a' : rate >= 70 ? '#fa8c16' : '#ff4d4f';
        return <span style={{ color, fontWeight: 500 }}>{formatPercent(ratio, 1)}</span>;
      },
    },
    {
      title: '总耗时',
      dataIndex: 'totalDurationMs',
      width: 110,
      sorter: (a, b) => (a.totalDurationMs || 0) - (b.totalDurationMs || 0),
      render: (v?: number) => formatDuration(v),
    },
    {
      title: '总 Token',
      dataIndex: 'totalTokens',
      width: 120,
      sorter: (a, b) => (a.totalTokens || 0) - (b.totalTokens || 0),
      render: (v?: number) => (v != null ? v.toLocaleString() : '-'),
    },
    {
      title: '最后活动时间',
      dataIndex: 'lastActiveTime',
      width: 170,
      fixed: 'right',
      render: (v?: string) => formatDateTime(v),
    },
    {
      title: '操作',
      width: 140,
      fixed: 'right',
      render: (_v: unknown, record: SessionRow) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            onClick={(e) => {
              e.stopPropagation();
              setSelectedSessionId(record.sessionId);
              setSessionPanelOpen(true);
            }}
          >
            会话详情
          </Button>
          <Button
            type="link"
            size="small"
            onClick={(e) => {
              e.stopPropagation();
              if (expandedSessionId === record.sessionId) {
                setExpandedSessionId(null);
              } else {
                handleExpand(true, record);
              }
            }}
          >
            展开
          </Button>
        </Space>
      ),
    },
  ];

  const sessionRows: SessionRow[] = useMemo(
    () => sessions.map((s) => ({ ...s, key: s.sessionId })),
    [sessions],
  );

  const successRate = stats ? formatPercent(stats.successRate, 1) : '-';
  const avgDuration = stats ? formatDuration(stats.avgDurationMs) : '-';
  const p95Duration = stats ? formatDuration(stats.p95DurationMs) : '-';

  return (
    <div>
      <PageHeader title="观测中心" desc="会话级执行链路追踪、性能分析一站式入口" />

      {/* 摘要统计条 */}
      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={8} sm={8}>
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <CheckCircleOutlined style={{ fontSize: 28, color: '#52c41a' }} />
              <div>
                <div style={{ fontSize: 12, color: '#8c8c8c' }}>成功率</div>
                <div style={{ fontSize: 22, fontWeight: 700, color: '#52c41a' }}>
                  {successRate}
                  <span style={{ fontSize: 14, fontWeight: 400, marginLeft: 2 }}>%</span>
                </div>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={8} sm={8}>
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <ClockCircleOutlined style={{ fontSize: 28, color: '#1677ff' }} />
              <div>
                <div style={{ fontSize: 12, color: '#8c8c8c' }}>平均耗时</div>
                <div style={{ fontSize: 22, fontWeight: 700, color: '#1677ff' }}>{avgDuration}</div>
              </div>
            </div>
          </Card>
        </Col>
        <Col xs={8} sm={8}>
          <Card>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <ThunderboltOutlined style={{ fontSize: 28, color: '#722ed1' }} />
              <div>
                <div style={{ fontSize: 12, color: '#8c8c8c' }}>P95 耗时</div>
                <div style={{ fontSize: 22, fontWeight: 700, color: '#722ed1' }}>{p95Duration}</div>
              </div>
            </div>
          </Card>
        </Col>
      </Row>

      {/* 检索 + 结果 */}
      <Card>
        {/* 检索区 */}
        <div style={{ marginBottom: 16 }}>
          <Space size={8} style={{ flexWrap: 'wrap' }}>
            <span style={{ fontSize: 13, color: '#8c8c8c' }}>维度：</span>
            <Select
              value={dimension}
              onChange={(v) => {
                setDimension(v);
                setDimensionValue('');
              }}
              style={{ width: 120 }}
              options={DIMENSION_TABS.map((t) => ({ label: t.label, value: t.key }))}
            />
            <Input
              key={dimension}
              placeholder={DIMENSION_TABS.find((t) => t.key === dimension)?.placeholder}
              value={dimensionValue}
              onChange={(e) => setDimensionValue(e.target.value)}
              onPressEnter={handleSearch}
              allowClear
              style={{ width: 240 }}
            />
            <Button type="primary" icon={<SearchOutlined />} onClick={handleSearch}>
              查询
            </Button>
            <Button icon={<ReloadOutlined />} onClick={handleReset}>
              重置
            </Button>
          </Space>
        </div>

        {/* 会话列表表格 */}
        <Table<SessionRow>
          rowKey="key"
          columns={sessionColumns}
          dataSource={sessionRows}
          loading={loading}
          scroll={{ x: 1500 }}
          expandable={{
            expandedRowKeys: expandedSessionId ? [expandedSessionId] : [],
            onExpand: (expanded, record) => handleExpand(expanded, record),
            expandedRowRender: (record) => renderTraceTable(record.sessionId),
            expandRowByClick: true,
          }}
          pagination={{
            current: page,
            pageSize,
            total: sessionTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            showTotal: (t) => `共 ${t} 条`,
            onChange: (p, s) => {
              setPage(p);
              setPageSize(s);
            },
          }}
          size="middle"
          locale={{ emptyText: '暂无会话数据' }}
        />
      </Card>

      <TraceDetailDrawer
        open={drawerOpen}
        traceId={selectedTraceId}
        onClose={() => setDrawerOpen(false)}
      />

      <SessionDetailPanel
        open={sessionPanelOpen}
        sessionId={selectedSessionId}
        onClose={() => setSessionPanelOpen(false)}
      />
    </div>
  );
};

export default AdminObservePage;
