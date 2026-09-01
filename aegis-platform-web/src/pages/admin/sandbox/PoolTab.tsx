/**
 * @file 沙箱池 Tab - 卡片网格 + 表格双视图
 * @description 池列表展示、创建/编辑/启停/删除、K8s 状态查看、K8s 资源修复
 *              两参数驱动模型：池参数仅 min_instances/max_instances/idle_timeout_min；
 *              预热和回收由后端 Reconcile 循环自动执行，前端无需 warmup/sync 按钮；
 *              策略体系已完全移除。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Col, Input, Popconfirm, Row, Segmented, Select, Space, Table, Tag } from 'antd';
import {
  CloudOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  RightOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { SandboxPool } from '@/api/sandbox';
import { poolApi } from '@/api/sandbox';
import {
  NETWORK_POLICY_MAP,
  POOL_STATUS_OPTIONS,
  POOL_STATUS_MAP,
  POOL_TYPE_MAP,
  normalizePage,
} from './constants';

interface PoolTabProps {
  onCreate: () => void;
  onEdit: (record: SandboxPool) => void;
  onShowK8sStatus: (record: SandboxPool) => void;
  onViewDetail: (record: SandboxPool) => void;
  onTotalChange?: (total: number) => void;
  refreshSignal?: number;
}

type ViewMode = 'card' | 'table';

const PoolTab: React.FC<PoolTabProps> = ({
  onCreate,
  onEdit,
  onShowK8sStatus,
  onViewDetail,
  onTotalChange,
  refreshSignal,
}) => {
  const { message } = App.useApp();
  const [view, setView] = useState<ViewMode>('card');
  const [keyword, setKeyword] = useState('');
  const [input, setInput] = useState('');
  const [poolType, setPoolType] = useState<string>('all');
  const [status, setStatus] = useState<string>('all');
  const [list, setList] = useState<SandboxPool[]>([]);
  const [loading, setLoading] = useState(false);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);

  const loadData = async () => {
    setLoading(true);
    try {
      const res = await poolApi.page({
        page,
        size,
        poolName: keyword || undefined,
        poolType: poolType !== 'all' ? poolType : undefined,
        status: status !== 'all' ? status : undefined,
      });
      const { list: records, total: t } = normalizePage<SandboxPool>(res);
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
    loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, poolType, status, page, size, refreshSignal]);

  const toggleStatus = async (record: SandboxPool) => {
    try {
      const next = record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED';
      await poolApi.updateStatus(record.id!, next);
      message.success(next === 'ENABLED' ? '池已启用' : '池已禁用');
      loadData();
    } catch {
      /* 弹错已处理 */
    }
  };

  // 手动修复 K8s 资源（重建 Namespace + ResourceQuota + NetworkPolicy）
  const repairK8s = async (record: SandboxPool) => {
    try {
      await poolApi.repairK8s(record.id!);
      message.success(`池「${record.poolName}」K8s 资源已修复`);
      loadData();
    } catch {
      /* 弹错已处理 */
    }
  };

  const remove = async (record: SandboxPool) => {
    try {
      await poolApi.delete(record.id!);
      message.success('池已删除（K8s Namespace 已清理）');
      loadData();
    } catch {
      /* 弹错已处理 */
    }
  };

  const columns: ColumnsType<SandboxPool> = [
    { title: '池编码', dataIndex: 'poolCode', width: 160 },
    { title: '池名称', dataIndex: 'poolName', width: 140 },
    {
      title: '类型',
      dataIndex: 'poolType',
      width: 90,
      render: (t: string) => {
        const cfg = POOL_TYPE_MAP[t];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : t ?? '-';
      },
    },
    {
      title: 'Namespace',
      dataIndex: 'namespace',
      width: 200,
      render: (v?: string) => (v ? <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> : '-'),
    },
    {
      title: '实例范围',
      width: 150,
      render: (_: unknown, r: SandboxPool) => (
        <Space direction="vertical" size={0}>
          <span style={{ fontWeight: 600 }}>
            {r.minInstances ?? 0} ~ {r.maxInstances ?? 0}
          </span>
          <span style={{ fontSize: 11, color: '#9ca3af' }}>空闲超时 {r.idleTimeoutMin ?? 0} 分钟</span>
        </Space>
      ),
    },
    {
      title: '资源',
      width: 160,
      render: (_: unknown, r: SandboxPool) => (
        <span style={{ fontSize: 12, color: '#6b7280' }}>
          CPU {r.cpuLimit} · MEM {r.memLimitMb}MB
        </span>
      ),
    },
    {
      title: '网络',
      dataIndex: 'networkPolicy',
      width: 110,
      render: (np?: string) => {
        const cfg = NETWORK_POLICY_MAP[np ?? 'RESTRICTED'];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : np ?? '-';
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (s?: string) => {
        const cfg = POOL_STATUS_MAP[s ?? 'ENABLED'];
        return cfg ? <Tag color={cfg.color}>{cfg.text}</Tag> : s ?? '-';
      },
    },
    {
      title: '操作',
      width: 320,
      fixed: 'right',
      render: (_: unknown, record: SandboxPool) => (
        <div style={{ display: 'flex', gap: 8, fontSize: 13, whiteSpace: 'nowrap', flexWrap: 'wrap' }}>
          <a onClick={() => onViewDetail(record)} style={{ color: '#4f46e5', fontWeight: 600 }}>
            <RightOutlined /> 详情
          </a>
          <a onClick={() => onShowK8sStatus(record)}>
            <CloudOutlined /> K8s
          </a>
          <a onClick={() => repairK8s(record)} title="手动修复该池的 Namespace/Quota/NetworkPolicy">
            <ToolOutlined /> 修复
          </a>
          <a onClick={() => onEdit(record)}>
            <EditOutlined /> 编辑
          </a>
          <a onClick={() => toggleStatus(record)} style={{ color: record.status === 'ENABLED' ? '#ef4444' : '#10b981' }}>
            {record.status === 'ENABLED' ? '禁用' : '启用'}
          </a>
          <Popconfirm
            title={`确认删除池「${record.poolName}」？`}
            description="将校验无活跃实例后删除 K8s Namespace"
            onConfirm={() => remove(record)}
          >
            <a style={{ color: '#ff4d4f' }}>
              <DeleteOutlined /> 删除
            </a>
          </Popconfirm>
        </div>
      ),
    },
  ];

  const toolbar = (
    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16, gap: 8, flexWrap: 'wrap' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <Input.Search
          placeholder="搜索池名称"
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
          style={{ width: 220 }}
          enterButton
        />
        <Select
          value={poolType}
          onChange={(v) => {
            setPoolType(v);
            setPage(1);
          }}
          options={[
            { value: 'all', label: '全部类型' },
            ...Object.keys(POOL_TYPE_MAP).map((k) => ({
              value: k,
              label: POOL_TYPE_MAP[k].text,
            })),
          ]}
          style={{ width: 130 }}
        />
        <Select
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(1);
          }}
          options={[{ value: 'all', label: '全部状态' }, ...POOL_STATUS_OPTIONS]}
          style={{ width: 130 }}
        />
        <Segmented
          value={view}
          onChange={(v) => setView(v as ViewMode)}
          options={[
            { value: 'card', label: '卡片' },
            { value: 'table', label: '表格' },
          ]}
        />
      </div>
      <Space>
        <Button icon={<ReloadOutlined />} onClick={loadData}>
          刷新
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={onCreate}>
          新建沙箱池
        </Button>
      </Space>
    </div>
  );

  if (view === 'card') {
    return (
      <>
        {toolbar}
        <Row gutter={[16, 16]}>
          {list.map((pool) => {
            const typeCfg = POOL_TYPE_MAP[pool.poolType] ?? { text: pool.poolType, color: '#6b7280' };
            const statusCfg = POOL_STATUS_MAP[pool.status ?? 'ENABLED'] ?? { text: pool.status, color: '#6b7280' };
            const netCfg = NETWORK_POLICY_MAP[pool.networkPolicy ?? 'RESTRICTED'] ?? { text: pool.networkPolicy, color: '#6b7280' };
            return (
              <Col key={pool.id} xs={24} sm={12} md={8} lg={6}>
                <Card
                  size="small"
                  style={{ height: '100%', borderColor: '#e5e7eb', cursor: 'pointer' }}
                  styles={{ body: { padding: 16 } }}
                  loading={loading}
                  hoverable
                  onClick={() => onViewDetail(pool)}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                      <span style={{ width: 8, height: 8, borderRadius: '50%', background: typeCfg.color, flexShrink: 0 }} />
                      <span style={{ fontWeight: 600, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {pool.poolName}
                      </span>
                      <RightOutlined style={{ fontSize: 10, color: '#9ca3af' }} />
                    </div>
                    <Tag color={statusCfg.color}>{statusCfg.text}</Tag>
                  </div>
                  <div style={{ fontSize: 12, color: '#6b7280', marginBottom: 8 }}>
                    <Tag color={typeCfg.color} style={{ marginRight: 4 }}>{typeCfg.text}</Tag>
                    <Tag color={netCfg.color}>{netCfg.text}</Tag>
                  </div>
                  <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 10, fontFamily: 'monospace' }}>
                    {pool.namespace ?? '-'}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 6 }}>
                    <span style={{ fontSize: 11, color: '#9ca3af' }}>实例范围</span>
                    <span style={{ fontSize: 18, fontWeight: 700, color: typeCfg.color }}>
                      {pool.minInstances ?? 0}
                      <span style={{ fontSize: 12, color: '#9ca3af', fontWeight: 400, margin: '0 4px' }}>~</span>
                      {pool.maxInstances ?? 0}
                      <span style={{ fontSize: 12, color: '#6b7280', fontWeight: 400, marginLeft: 4 }}>实例</span>
                    </span>
                  </div>
                  <div style={{ fontSize: 11, color: '#9ca3af' }}>
                    空闲超时 {pool.idleTimeoutMin ?? 0} 分钟 · CPU {pool.cpuLimit} · MEM {pool.memLimitMb}MB
                  </div>
                  <div
                    style={{ display: 'flex', justifyContent: 'space-between', marginTop: 12, borderTop: '1px dashed #e5e7eb', paddingTop: 8 }}
                    onClick={(e) => e.stopPropagation()}
                  >
                    <Space size={4}>
                      <Button type="text" size="small" icon={<CloudOutlined />} onClick={() => onShowK8sStatus(pool)} title="K8s 状态" />
                      <Button type="text" size="small" icon={<ToolOutlined />} onClick={() => repairK8s(pool)} title="修复 K8s 资源" />
                    </Space>
                    <Space size={4}>
                      <Button type="text" size="small" icon={<EditOutlined />} onClick={() => onEdit(pool)} title="编辑" />
                      <Popconfirm
                        title={`确认删除池「${pool.poolName}」？`}
                        onConfirm={() => remove(pool)}
                      >
                        <Button type="text" size="small" danger icon={<DeleteOutlined />} title="删除" />
                      </Popconfirm>
                    </Space>
                  </div>
                </Card>
              </Col>
            );
          })}
          {list.length === 0 && !loading && (
            <Col span={24}>
              <Card>
                <div style={{ textAlign: 'center', color: '#9ca3af', padding: 32 }}>暂无沙箱池，点击右上角"新建沙箱池"创建</div>
              </Card>
            </Col>
          )}
        </Row>
      </>
    );
  }

  return (
    <>
      {toolbar}
      <Table<SandboxPool>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        scroll={{ x: 1500 }}
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

export default PoolTab;
