/**
 * @file 审计日志
 * @description 审计类型统计 + 日志筛选查询（后端分页 + keyword）+ 导出
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState, useCallback } from 'react';
import { App, Button, Card, Col, DatePicker, Input, Row, Select, Space, Table, Tag, Typography } from 'antd';
import { ExportOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType, TablePaginationConfig } from 'antd/es/table';
import type { Dayjs } from 'dayjs';
import { PageHeader } from '@/components/common/PageHeader';
import { getAuditLogs, getAuditStats, exportAuditLogs } from '@/api/security';
import type { AuditLog, AuditLogQuery } from '@/api/security';

const { Text } = Typography;
const { RangePicker } = DatePicker;

/** 审计类型展示元数据 */
const AUDIT_TYPE_META: Record<string, { text: string; color: string }> = {
  session: { text: '会话审计', color: 'green' },
  security: { text: '安全审计', color: 'red' },
  policy_decision: { text: '策略决策', color: 'purple' },
};

/** 审计结果展示映射（对齐后端 AuditResult 枚举） */
const AUDIT_RESULT_META: Record<string, { text: string; color: string }> = {
  SUCCESS: { text: '成功', color: 'green' },
  BLOCKED: { text: '拦截', color: 'red' },
  ALERT: { text: '告警', color: 'orange' },
  RECORDED: { text: '已记录', color: 'blue' },
};

/** 安全获取审计类型元数据 */
const getAuditTypeMeta = (t: string): { text: string; color: string } => {
  const key = (t || '').toLowerCase();
  return AUDIT_TYPE_META[key] ?? { text: t || '未知', color: 'default' };
};

/** 安全获取审计结果元数据 */
const getAuditResultMeta = (r: string): { text: string; color: string } =>
  AUDIT_RESULT_META[r] ?? { text: r || '未知', color: 'default' };

/** 审计类型统计项 */
interface AuditTypeStat {
  key: string;
  statKey: string;
  label: string;
  bg: string;
  color: string;
}

/** 审计类型统计卡片配置（statKey 对齐后端 stats 返回的 key） */
const AUDIT_TYPE_STATS: AuditTypeStat[] = [
  { key: 'session', statKey: 'SESSION', label: '会话审计', bg: '#f6ffed', color: '#389e0d' },
  { key: 'security', statKey: 'SECURITY', label: '安全审计', bg: '#fff1f0', color: '#cf1322' },
  { key: 'policy_decision', statKey: 'POLICY_DECISION', label: '策略决策', bg: '#f9f0ff', color: '#722ed1' },
];

/** 类型筛选选项 */
const TYPE_OPTIONS = [
  { label: '全部类型', value: '' },
  ...AUDIT_TYPE_STATS.map((s) => ({ label: s.label, value: s.statKey })),
];

/** 审计结果筛选选项 */
const RESULT_OPTIONS = [
  { label: '全部结果', value: '' },
  { label: '成功', value: 'SUCCESS' },
  { label: '拦截', value: 'BLOCKED' },
  { label: '告警', value: 'ALERT' },
  { label: '已记录', value: 'RECORDED' },
];

/** 审计日志表格行 */
interface AuditLogRow {
  id: string;
  time: string;
  type: string;
  user: string;
  action: string;
  resource: string;
  detail: string;
  result: string;
  ip: string;
}

const AuditPage: React.FC = () => {
  const { message } = App.useApp();
  const [typeFilter, setTypeFilter] = useState<string>('');
  const [resultFilter, setResultFilter] = useState<string>('');
  const [keyword, setKeyword] = useState('');
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [auditLogs, setAuditLogs] = useState<AuditLogRow[]>([]);
  const [auditStats, setAuditStats] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [pagination, setPagination] = useState<{ current: number; pageSize: number; total: number }>({
    current: 1,
    pageSize: 10,
    total: 0,
  });

  // ----- 加载审计统计（随时间范围联动） -----
  const fetchStats = useCallback(() => {
    const params: Record<string, unknown> = {};
    if (dateRange && dateRange[0]) params.startTime = dateRange[0].format('YYYY-MM-DD HH:mm:ss');
    if (dateRange && dateRange[1]) params.endTime = dateRange[1].format('YYYY-MM-DD HH:mm:ss');
    getAuditStats(params)
      .then((res) => setAuditStats(res || {}))
      .catch(() => {});
  }, [dateRange]);

  useEffect(() => {
    fetchStats();
  }, [fetchStats]);

  // ----- 加载审计日志（后端分页 + keyword） -----
  const fetchLogs = useCallback(
    (page: number, size: number) => {
      setLoading(true);
      const params: AuditLogQuery = {
        page,
        size,
        logType: typeFilter || undefined,
        result: resultFilter || undefined,
        keyword: keyword.trim() || undefined,
      };
      if (dateRange && dateRange[0]) params.startTime = dateRange[0].format('YYYY-MM-DD HH:mm:ss');
      if (dateRange && dateRange[1]) params.endTime = dateRange[1].format('YYYY-MM-DD HH:mm:ss');
      getAuditLogs(params)
        .then((res) => {
          const rows: AuditLogRow[] = (res?.records || []).map((log: AuditLog, i: number) => ({
            id: String(log.id ?? i),
            time: log.occurTime ?? '',
            type: (log.logType ?? 'SESSION').toLowerCase(),
            user: log.username ?? String(log.userId ?? ''),
            action: log.operation ?? '',
            resource: log.resourceName ?? log.resourceType ?? '-',
            detail: log.detail ?? '',
            result: log.result ?? 'SUCCESS',
            ip: log.ip ?? '',
          }));
          setAuditLogs(rows);
          setPagination((prev) => ({
            ...prev,
            current: page,
            pageSize: size,
            total: res?.total ?? 0,
          }));
        })
        .catch(() => {
          setAuditLogs([]);
          setPagination((prev) => ({ ...prev, total: 0 }));
        })
        .finally(() => setLoading(false));
    },
    [typeFilter, resultFilter, keyword, dateRange],
  );

  // 筛选条件变化时回到第一页重新拉取
  useEffect(() => {
    fetchLogs(1, pagination.pageSize);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [typeFilter, resultFilter, dateRange]);

  // keyword 防抖（300ms）
  useEffect(() => {
    const timer = setTimeout(() => {
      fetchLogs(1, pagination.pageSize);
    }, 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword]);

  const handleReset = () => {
    setTypeFilter('');
    setResultFilter('');
    setKeyword('');
    setDateRange(null);
  };

  const handleExport = () => {
    const params: Record<string, unknown> = {};
    if (typeFilter) params.logType = typeFilter;
    if (resultFilter) params.result = resultFilter;
    if (keyword.trim()) params.keyword = keyword.trim();
    if (dateRange && dateRange[0]) params.startTime = dateRange[0].format('YYYY-MM-DD HH:mm:ss');
    if (dateRange && dateRange[1]) params.endTime = dateRange[1].format('YYYY-MM-DD HH:mm:ss');
    setExporting(true);
    exportAuditLogs(params)
      .then((blob) => {
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `audit-logs-${new Date().toISOString().slice(0, 10)}.csv`;
        a.click();
        window.URL.revokeObjectURL(url);
        message.success('审计日志导出成功');
      })
      .catch(() => {
        message.error('审计日志导出失败，请稍后重试');
      })
      .finally(() => setExporting(false));
  };

  const columns: ColumnsType<AuditLogRow> = [
    { title: '时间', dataIndex: 'time', width: 170 },
    {
      title: '类型',
      dataIndex: 'type',
      width: 120,
      render: (t: string) => {
        const meta = getAuditTypeMeta(t);
        return <Tag color={meta.color}>{meta.text}</Tag>;
      },
    },
    { title: '用户', dataIndex: 'user', width: 110 },
    { title: '操作', dataIndex: 'action', width: 160 },
    { title: '资源', dataIndex: 'resource', width: 180 },
    { title: '详情', dataIndex: 'detail', ellipsis: true },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (r: string) => {
        const meta = getAuditResultMeta(r);
        return <Tag color={meta.color}>{meta.text}</Tag>;
      },
    },
    { title: 'IP', dataIndex: 'ip', width: 130 },
  ];

  /** 按统计 key 获取数量 */
  const getCount = (statKey: string) => {
    const stat = auditStats[statKey] ?? auditStats[statKey.toLowerCase()] ?? 0;
    return stat ?? 0;
  };

  const handleTableChange = (pag: TablePaginationConfig) => {
    fetchLogs(pag.current ?? 1, pag.pageSize ?? 10);
  };

  return (
    <div>
      <PageHeader
        title="审计日志"
        desc="会话审计(90天) / 安全审计(365天) / 策略决策(90天) — 记录会话生命周期、安全拦截事件与工具策略决策"
      />

      {/* 审计类型统计卡片 */}
      <Row gutter={[16, 16]}>
        {AUDIT_TYPE_STATS.map((stat) => (
          <Col key={stat.key} xs={24} sm={12} lg={8}>
            <Card style={{ background: stat.bg, border: 'none' }}>
              <Text type="secondary" style={{ fontSize: 13 }}>{stat.label}</Text>
              <div style={{ fontSize: 28, fontWeight: 700, color: stat.color, marginTop: 8 }}>
                {getCount(stat.statKey).toLocaleString()}
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      {/* 筛选区 + 审计日志表格 */}
      <Card style={{ marginTop: 16 }}>
        <Space style={{ marginBottom: 16, width: '100%', flexWrap: 'wrap' }}>
          <Select
            value={typeFilter}
            onChange={setTypeFilter}
            options={TYPE_OPTIONS}
            style={{ width: 160 }}
          />
          <Select
            value={resultFilter}
            onChange={setResultFilter}
            options={RESULT_OPTIONS}
            style={{ width: 140 }}
          />
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索用户 / 操作 / 资源"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            allowClear
            style={{ width: 240 }}
          />
          <RangePicker
            value={dateRange}
            onChange={(dates) => setDateRange(dates as [Dayjs | null, Dayjs | null] | null)}
            style={{ width: 260 }}
          />
          <Button icon={<ReloadOutlined />} onClick={handleReset}>重置</Button>
        </Space>
        <div style={{ marginBottom: 16, textAlign: 'right' }}>
          <Button type="primary" icon={<ExportOutlined />} loading={exporting} onClick={handleExport}>导出审计日志</Button>
        </div>
        <Table<AuditLogRow>
          rowKey="id"
          columns={columns}
          dataSource={auditLogs}
          loading={loading}
          size="middle"
          pagination={{
            current: pagination.current,
            pageSize: pagination.pageSize,
            total: pagination.total,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条`,
          }}
          onChange={handleTableChange}
        />
      </Card>

      {/* 导出说明（实际能力，非画饼） */}
      <Card style={{ marginTop: 16 }}>
        <Text type="secondary" style={{ fontSize: 13 }}>
          导出为 CSV（UTF-8 BOM，Excel 可直接打开），单次上限 10000 条，导出行为本身已记入安全审计。
        </Text>
      </Card>
    </div>
  );
};

export default AuditPage;
