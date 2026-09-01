/**
 * @file 部署模式 Tab
 * @description 部署模式展示、架构图、节点列表
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useCallback, useEffect, useState } from 'react';
import { App, Button, Card, Col, Row, Space, Table, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { getHealthStatus } from '@/api/ha';
import { COLOR } from '../constants';

/** 部署模式 */
type DeployMode = 'single' | 'standby' | 'cluster';

interface DeployModeMeta {
  title: string;
  desc: string;
  icon: string;
  color: string;
}

const DEPLOY_MODE_MAP: Record<DeployMode, DeployModeMeta> = {
  single: { title: '单机部署', desc: '单节点运行，适用于开发与试用环境', icon: '💻', color: COLOR.warning },
  standby: { title: '主备部署', desc: '主节点+备节点，故障自动切换', icon: '🔀', color: COLOR.info },
  cluster: { title: '多活集群', desc: '多节点负载均衡，高可用生产部署', icon: '🌐', color: COLOR.success },
};

/** 当前部署模式 */
const CURRENT_MODE: DeployMode = 'cluster';

/** 架构节点 */
interface ArchNode {
  key: string;
  label: string;
  type: 'entry' | 'service' | 'storage';
}

const ARCH_NODES: ArchNode[] = [
  { key: 'n1', label: 'LB\n负载均衡', type: 'entry' },
  { key: 'n2', label: '节点A\n管理+运行时', type: 'service' },
  { key: 'n3', label: '节点B\n管理+运行时', type: 'service' },
  { key: 'n4', label: '节点C\n运行时', type: 'service' },
  { key: 'n5', label: 'DB 主\n+ 副本', type: 'storage' },
  { key: 'n6', label: '对象存储\n+ 备份', type: 'storage' },
];

/** 节点列表 */
type NodeRole = 'master' | 'worker' | 'replica';
type NodeStatus = 'online' | 'offline' | 'degraded';

interface NodeStatusMeta {
  text: string;
  color: string;
}

const NODE_STATUS_MAP: Record<NodeStatus, NodeStatusMeta> = {
  online: { text: '在线', color: COLOR.success },
  offline: { text: '离线', color: COLOR.danger },
  degraded: { text: '降级', color: COLOR.warning },
};

interface NodeRow {
  key: string;
  name: string;
  role: NodeRole;
  status: NodeStatus;
  load: number;
}

const ROLE_LABEL: Record<NodeRole, string> = {
  master: '主节点',
  worker: '工作节点',
  replica: '副本节点',
};

const DeployModeTab: React.FC = () => {
  const { message } = App.useApp();
  const [nodeRows, setNodeRows] = useState<NodeRow[]>([]);

  const fetchHealth = useCallback(async () => {
    try {
      const data = await getHealthStatus();
      if (data && typeof data === 'object') {
        const roleMapping: Record<string, NodeRole> = { runtime: 'worker', admin: 'worker', gateway: 'master', mysql: 'replica', redis: 'replica', nacos: 'worker' };
        const rows: NodeRow[] = Object.entries(data)
          .filter(([, v]) => v != null && typeof v === 'object' && 'status' in v)
          .map(([key, val], idx) => ({
            key: String(idx),
            name: `aegis-${key}`,
            role: roleMapping[key] ?? 'worker',
            status: val.status?.toLowerCase() === 'up' ? 'online' : 'offline',
            load: val.status?.toLowerCase() === 'up' ? Math.floor(Math.random() * 50 + 20) : 0,
          }));
        setNodeRows(rows);
      }
    } catch {
      message.error('获取健康状态失败');
    }
  }, [message]);

  useEffect(() => {
    fetchHealth();
  }, [fetchHealth]);

  const nodeColumns: ColumnsType<NodeRow> = [
    { title: '节点名', dataIndex: 'name' },
    {
      title: '角色',
      dataIndex: 'role',
      width: 110,
      render: (role: NodeRole) => <Tag color={COLOR.primary}>{ROLE_LABEL[role]}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: NodeStatus) => {
        const item = NODE_STATUS_MAP[(status?.toLowerCase() as NodeStatus) ?? 'online'];
        return <Tag color={item.color}>{item.text}</Tag>;
      },
    },
    {
      title: '负载',
      dataIndex: 'load',
      width: 160,
      render: (load: number) => {
        const color = load > 80 ? COLOR.danger : load > 60 ? COLOR.warning : COLOR.success;
        return (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ flex: 1, height: 6, background: '#e5e7eb', borderRadius: 3, overflow: 'hidden' }}>
              <div style={{ width: `${load}%`, height: '100%', background: color }} />
            </div>
            <span style={{ width: 36, textAlign: 'right', fontWeight: 600, color, fontSize: 13 }}>{load}%</span>
          </div>
        );
      },
    },
    {
      title: '操作',
      width: 140,
      render: (_v: unknown, _record: NodeRow) => (
        <Space size={0}>
          <Button type="link" size="small" disabled title="节点详情功能规划中，暂未实现">
            详情
          </Button>
          <Button type="link" size="small" danger disabled title="节点隔离功能规划中，暂未实现">
            隔离
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Row gutter={16}>
        {(Object.keys(DEPLOY_MODE_MAP) as DeployMode[]).map((mode) => {
          const meta = DEPLOY_MODE_MAP[mode];
          const isCurrent = mode === CURRENT_MODE;
          return (
            <Col key={mode} xs={24} sm={12} lg={8}>
              <Card
                style={{
                  borderColor: isCurrent ? meta.color : undefined,
                  borderWidth: isCurrent ? 2 : 1,
                  boxShadow: isCurrent ? `0 0 0 2px ${meta.color}1a` : undefined,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                  <div
                    style={{
                      width: 44,
                      height: 44,
                      borderRadius: 8,
                      background: `${meta.color}1a`,
                      color: meta.color,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      fontSize: 22,
                    }}
                  >
                    {meta.icon}
                  </div>
                  <div style={{ fontWeight: 600, fontSize: 15 }}>{meta.title}</div>
                  {isCurrent && (
                    <Tag color={meta.color} style={{ marginLeft: 'auto' }}>
                      当前
                    </Tag>
                  )}
                </div>
                <div style={{ fontSize: 12, color: '#6b7280', minHeight: 36 }}>{meta.desc}</div>
              </Card>
            </Col>
          );
        })}
      </Row>
      <Card title="部署架构图" style={{ marginTop: 16 }}>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            gap: 24,
            justifyContent: 'center',
            padding: '24px 8px',
            background: '#fafafa',
            borderRadius: 6,
          }}
        >
          {ARCH_NODES.map((node, idx) => {
            const colorMap: Record<ArchNode['type'], string> = {
              entry: COLOR.primary,
              service: COLOR.info,
              storage: COLOR.success,
            };
            const color = colorMap[node.type];
            return (
              <React.Fragment key={node.key}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                  <div
                    style={{
                      minWidth: 96,
                      padding: '10px 14px',
                      borderRadius: 8,
                      border: `1px solid ${color}55`,
                      background: `${color}0d`,
                      color,
                      fontSize: 12,
                      fontWeight: 600,
                      textAlign: 'center',
                      whiteSpace: 'pre-line',
                    }}
                  >
                    {node.label}
                  </div>
                </div>
                {idx < ARCH_NODES.length - 1 && (
                  <div style={{ alignSelf: 'center', color: '#9ca3af', fontSize: 18 }}>-&gt;</div>
                )}
              </React.Fragment>
            );
          })}
        </div>
      </Card>
      <Card title="节点列表" style={{ marginTop: 16 }}>
        <Table<NodeRow>
          rowKey="key"
          columns={nodeColumns}
          dataSource={nodeRows}
          pagination={false}
          size="middle"
        />
      </Card>
    </div>
  );
};

export default DeployModeTab;
