/**
 * @file 沙箱实例 Tab - 统计卡片 + 实例列表
 * @description 实例列表展示、按池/状态/实例ID筛选、回收（工作区重初始化）、销毁、查看 Pod 状态
 *   两参数驱动模型：回收统一为工作区重初始化（清理用户数据 + 重建标准目录），无需 strategy 参数
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Col, Input, Popconfirm, Row, Select, Space, Statistic, Table, Tag } from 'antd';
import { ReloadOutlined, CloudOutlined, DeleteOutlined, SyncOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SandboxInstance, SandboxPool } from '@/api/sandbox';
import { instanceApi, poolApi } from '@/api/sandbox';
import {
  INSTANCE_STATUS_MAP,
  INSTANCE_STATUS_OPTIONS,
  normalizePage,
} from './constants';
import { formatDateTime } from '@/utils/format';

interface InstanceTabProps {
  onShowPodStatus: (record: SandboxInstance) => void;
  onTotalChange?: (total: number) => void;
  refreshSignal?: number;
  /** 固定池 ID：传入后隐藏池选择器，始终按该 poolId 查询（池详情内嵌场景） */
  fixedPoolId?: number;
  /** 内嵌模式：隐藏统计卡片和池选择器（池详情页场景） */
  embedded?: boolean;
}

const InstanceTab: React.FC<InstanceTabProps> = ({
  onShowPodStatus,
  onTotalChange,
  refreshSignal,
  fixedPoolId,
  embedded = false,
}) => {
  const { message } = App.useApp();
  const [keyword, setKeyword] = useState('');
  const [input, setInput] = useState('');
  const [poolId, setPoolId] = useState<number | 'all'>(fixedPoolId ?? 'all');
  const [status, setStatus] = useState<string>('all');
  const [list, setList] = useState<SandboxInstance[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [poolList, setPoolList] = useState<SandboxPool[]>([]);
  const [stats, setStats] = useState<Record<string, number>>({});

  const loadPools = async () => {
    if (fixedPoolId != null) return; // 固定池模式下无需加载池列表
    try {
      const res = await poolApi.list();
      setPoolList(Array.isArray(res) ? res : []);
    } catch {
      /* 弹错已处理 */
    }
  };

  const loadStats = async () => {
    if (embedded) return; // 内嵌模式下不加载全局统计
    try {
      const res = await instanceApi.countByStatus();
      setStats(res ?? {});
    } catch {
      /* 弹错已处理 */
    }
  };

  const loadData = async () => {
    setLoading(true);
    try {
      const effectivePoolId = fixedPoolId ?? (poolId !== 'all' ? poolId : undefined);
      const res = await instanceApi.page({
        page,
        size,
        poolId: effectivePoolId,
        status: status !== 'all' ? status : undefined,
        instanceId: keyword || undefined,
      });
      const { list: records, total: t } = normalizePage<SandboxInstance>(res);
      setList(records);
      setTotal(t);
      onTotalChange?.(t);
    } catch {
      /* 弹错已处理 */
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadPools();
    loadStats();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshSignal]);

  useEffect(() => {
    loadData();
    loadStats();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, poolId, status, page, size, refreshSignal]);

  const poolNameMap = new Map<number, string>();
  poolList.forEach((p) => {
    if (p.id != null) poolNameMap.set(p.id, p.poolName ?? p.poolCode ?? String(p.id));
  });

  // 回收实例：两参数驱动模型下统一为工作区重初始化（无需 strategy 参数）
  const recycle = async (record: SandboxInstance) => {
    try {
      await instanceApi.recycle(record.instanceId);
      message.success('实例已回收（工作区重初始化）');
      loadData();
      loadStats();
    } catch {
      /* 弹错已处理 */
    }
  };

  const destroy = async (record: SandboxInstance) => {
    try {
      await instanceApi.destroy(record.instanceId);
      message.success('实例已销毁');
      loadData();
      loadStats();
    } catch {
      /* 弹错已处理 */
    }
  };

  const columns: ColumnsType<SandboxInstance> = [
    {
      title: '实例ID',
      dataIndex: 'instanceId',
      width: 180,
      render: (id: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{id}</span>,
    },
    ...(fixedPoolId == null
      ? [
          {
            title: '所属池',
            dataIndex: 'poolId' as keyof SandboxInstance,
            width: 130,
            render: (pid?: number) => (pid != null ? poolNameMap.get(pid) ?? String(pid) : '-'),
          },
        ]
      : []),
    {
      title: '状态',
      dataIndex: 'status',
      width: 130,
      render: (s?: string, r?: SandboxInstance) => {
        const cfg = INSTANCE_STATUS_MAP[s ?? 'IDLE'];
        const tag = cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : s ?? '-';
        // IDLE 状态时附加 initialized 子标签（三态语义见 SandboxResourceLoader）：
        //   0 = 脏（用户残留数据，待 admin 回收重初始化）
        //   1 = 干净（工作区已初始化，可直接分配）
        //   2 = 已装载（kb/skill/mcp 资源已注入，有产物残留）
        if ((s === 'IDLE' || s === 'RESIDENT') && r) {
          const initTag =
            r.initialized === 1 ? (
              <Tag color="green" style={{ fontSize: 10 }}>干净</Tag>
            ) : r.initialized === 2 ? (
              <Tag color="blue" style={{ fontSize: 10 }}>已装载</Tag>
            ) : (
              <Tag color="orange" style={{ fontSize: 10 }}>脏</Tag>
            );
          return (
            <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
              {tag}
              {initTag}
            </div>
          );
        }
        return tag;
      },
    },
    { title: '用户ID', dataIndex: 'userId', width: 90, render: (v?: number) => v ?? '-' },
    { title: 'Agent', dataIndex: 'agentId', width: 90, render: (v?: number) => v ?? '-' },
    { title: '会话', dataIndex: 'sessionId', width: 140, ellipsis: true, render: (v?: string) => v ?? '-' },
    {
      title: 'Pod',
      width: 200,
      render: (_: unknown, r: SandboxInstance) => (
        <div style={{ fontSize: 11 }}>
          <div style={{ fontFamily: 'monospace' }}>{r.podName ?? '-'}</div>
          <div style={{ color: '#9ca3af', fontFamily: 'monospace' }}>{r.namespace ?? '-'}</div>
        </div>
      ),
    },
    {
      title: '资源',
      width: 120,
      render: (_: unknown, r: SandboxInstance) => {
        const cpu = r.cpuUsage != null ? `${Math.round((Number(r.cpuUsage) || 0) * 100)}%` : '-';
        const mem = r.memUsage != null ? `${Math.round((Number(r.memUsage) || 0) * 100)}%` : '-';
        return <span style={{ fontSize: 12, color: '#6b7280' }}>CPU {cpu} · MEM {mem}</span>;
      },
    },
    { title: '复用', dataIndex: 'reuseCount', width: 70, render: (v?: number) => v ?? 0 },
    {
      title: '分配时间',
      dataIndex: 'allocatedTime',
      width: 170,
      render: (v?: string) => formatDateTime(v),
    },
    {
      title: '操作',
      width: 200,
      fixed: 'right',
      render: (_: unknown, record: SandboxInstance) => (
        <div style={{ display: 'flex', gap: 8, fontSize: 13, whiteSpace: 'nowrap', flexWrap: 'wrap' }}>
          <a onClick={() => onShowPodStatus(record)}>
            <CloudOutlined /> Pod
          </a>
          {record.status !== 'DESTROYED' && (
            <Popconfirm
              title="回收实例"
              description="将执行工作区重初始化（清理用户数据+重建标准目录）"
              onConfirm={() => recycle(record)}
              okText="确认回收"
              cancelText="取消"
            >
              <a style={{ color: '#f59e0b' }}>
                <SyncOutlined /> 回收
              </a>
            </Popconfirm>
          )}
          {record.status !== 'DESTROYED' && (
            <Popconfirm
              title={`确认销毁实例「${record.instanceId}」？`}
              description="销毁后容器彻底删除，不可恢复"
              onConfirm={() => destroy(record)}
            >
              <a style={{ color: '#ff4d4f' }}>
                <DeleteOutlined /> 销毁
              </a>
            </Popconfirm>
          )}
        </div>
      ),
    },
  ];

  const statCards = INSTANCE_STATUS_OPTIONS.map((opt) => {
    const cfg = INSTANCE_STATUS_MAP[opt.value];
    const count = stats[opt.value] ?? 0;
    return (
      <Col key={opt.value} xs={12} sm={6}>
        <Card size="small">
          <Statistic
            title={<span style={{ fontSize: 12, color: '#6b7280' }}>{cfg.text}</span>}
            value={count}
            valueStyle={{ color: cfg.color, fontSize: 24, fontWeight: 600 }}
          />
        </Card>
      </Col>
    );
  });

  return (
    <>
      {!embedded && (
        <Row gutter={12} style={{ marginBottom: 16 }}>
          {statCards}
          <Col xs={12} sm={6}>
            <Card size="small">
              <Statistic
                title={<span style={{ fontSize: 12, color: '#6b7280' }}>总计</span>}
                value={Object.values(stats).reduce((a, b) => a + b, 0)}
                valueStyle={{ color: '#4f46e5', fontSize: 24, fontWeight: 600 }}
              />
            </Card>
          </Col>
        </Row>
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, gap: 8, flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <Input.Search
            placeholder="搜索实例ID"
            value={input}
            onChange={(e) => {
              setInput(e.target.value);
              if (e.target.value === '') {
                setKeyword('');
                setPage(1);
              }
            }}
            onSearch={(v) => {
              setKeyword(v);
              setPage(1);
            }}
            allowClear
            style={{ width: 260 }}
            enterButton
          />
          {fixedPoolId == null && (
            <Select
              value={poolId}
              onChange={(v) => {
                setPoolId(v);
                setPage(1);
              }}
              options={[
                { value: 'all', label: '全部池' },
                ...poolList.map((p) => ({ value: p.id!, label: p.poolName ?? p.poolCode })),
              ]}
              style={{ width: 160 }}
            />
          )}
          <Select
            value={status}
            onChange={(v) => {
              setStatus(v);
              setPage(1);
            }}
            options={[{ value: 'all', label: '全部状态' }, ...INSTANCE_STATUS_OPTIONS]}
            style={{ width: 130 }}
          />
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => { loadData(); loadStats(); }}>
            刷新
          </Button>
        </Space>
      </div>

      <Table<SandboxInstance>
        rowKey="instanceId"
        columns={columns}
        dataSource={list}
        loading={loading}
        scroll={{ x: 1620 }}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, sz) => {
            setPage(p);
            setSize(sz);
          },
        }}
      />
    </>
  );
};

export default InstanceTab;
