/**
 * @file 沙箱池详情页
 * @description 池基本信息 + K8s 状态 + 该池下的实例列表（内嵌 InstanceTab）
 *              两参数驱动模型：池参数仅保留 minInstances/maxInstances/idleTimeoutMin，
 *              预热与回收由后端 Reconcile 自动执行，策略体系已移除。
 *              从沙箱池列表点击进入，提供返回按钮
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Descriptions, Space, Spin, Tag, Typography } from 'antd';
import {
  ArrowLeftOutlined,
  CloudOutlined,
  EditOutlined,
  ReloadOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import type { SandboxInstance, SandboxPool } from '@/api/sandbox';
import { poolApi } from '@/api/sandbox';
import {
  NETWORK_POLICY_MAP,
  POOL_STATUS_MAP,
  POOL_TYPE_MAP,
  COLOR,
} from './constants';
import InstanceTab from './InstanceTab';

const { Text } = Typography;

interface PoolDetailProps {
  /** 池 ID，进入详情时可能只有 ID，内部自行加载完整信息 */
  poolId: number;
  /** 已有的池记录（可选，避免重复请求） */
  record?: SandboxPool | null;
  onBack: () => void;
  onEdit: (record: SandboxPool) => void;
  onShowK8sStatus: (record: SandboxPool) => void;
  onShowPodStatus: (record: SandboxInstance) => void;
}

const PoolDetail: React.FC<PoolDetailProps> = ({
  poolId,
  record: initialRecord,
  onBack,
  onEdit,
  onShowK8sStatus,
  onShowPodStatus,
}) => {
  const { message } = App.useApp();
  const [pool, setPool] = useState<SandboxPool | null>(initialRecord ?? null);
  const [loading, setLoading] = useState(!initialRecord);
  const [instanceTotal, setInstanceTotal] = useState(0);

  const loadPool = async () => {
    setLoading(true);
    try {
      const res = await poolApi.getById(poolId);
      setPool(res);
    } catch {
      /* 弹错已处理 */
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (!initialRecord) {
      loadPool();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [poolId]);

  const repairK8s = async () => {
    if (!pool?.id) return;
    try {
      await poolApi.repairK8s(pool.id);
      message.success('K8s 资源已修复');
      loadPool();
    } catch {
      /* 弹错已处理 */
    }
  };

  if (loading || !pool) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  const typeCfg = POOL_TYPE_MAP[pool.poolType] ?? { text: pool.poolType, color: COLOR.gray };
  const statusCfg = POOL_STATUS_MAP[pool.status ?? 'ENABLED'] ?? { text: pool.status, color: COLOR.gray };
  const netCfg = NETWORK_POLICY_MAP[pool.networkPolicy ?? 'RESTRICTED'] ?? { text: pool.networkPolicy, color: COLOR.gray };
  const minInstances = pool.minInstances ?? 0;
  const maxInstances = pool.maxInstances ?? 0;
  const idleTimeoutMin = pool.idleTimeoutMin ?? 0;

  return (
    <div style={{ paddingRight: 4 }}>
      {/* 返回栏 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={onBack} type="text">
          返回沙箱池列表
        </Button>
        <div style={{ height: 20, borderLeft: '1px solid #e5e7eb' }} />
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ width: 10, height: 10, borderRadius: '50%', background: typeCfg.color }} />
          <span style={{ fontSize: 18, fontWeight: 600 }}>{pool.poolName}</span>
          <Tag color={typeCfg.color}>{typeCfg.text}</Tag>
          <Tag color={statusCfg.color}>{statusCfg.text}</Tag>
        </div>
        <div style={{ flex: 1 }} />
        <Space>
          <Button icon={<ReloadOutlined />} onClick={loadPool}>
            刷新
          </Button>
          <Button icon={<CloudOutlined />} onClick={() => onShowK8sStatus(pool)}>
            K8s 状态
          </Button>
          <Button icon={<ToolOutlined />} onClick={repairK8s} style={{ color: COLOR.warning }}>
            修复 K8s
          </Button>
          <Button icon={<EditOutlined />} onClick={() => onEdit(pool)}>
            编辑
          </Button>
        </Space>
      </div>

      {/* 池信息 + 实例配置 */}
      <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        <Card size="small" style={{ flex: '1 1 480px', minWidth: 400 }} title="池配置信息">
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="池编码">
              <Text code>{pool.poolCode ?? '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="Namespace">
              <Text code>{pool.namespace ?? '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label="适用场景">{pool.applicableScene ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="网络策略">
              <Tag color={netCfg.color}>{netCfg.text}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="CPU 限制">{pool.cpuLimit ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="内存限制">{pool.memLimitMb ? `${pool.memLimitMb} MB` : '-'}</Descriptions.Item>
            <Descriptions.Item label="磁盘限制">{pool.diskLimitGb ? `${pool.diskLimitGb} GB` : '-'}</Descriptions.Item>
            <Descriptions.Item label="最小实例数">{minInstances}</Descriptions.Item>
            <Descriptions.Item label="最大实例数">{maxInstances}</Descriptions.Item>
            <Descriptions.Item label="空闲超时(分)">{idleTimeoutMin}</Descriptions.Item>
          </Descriptions>
        </Card>

        <Card size="small" style={{ flex: '1 1 280px', minWidth: 260 }} title="实例配置">
          <div style={{ textAlign: 'center', padding: '12px 0' }}>
            <div style={{ fontSize: 36, fontWeight: 700, color: typeCfg.color }}>
              {minInstances} ~ {maxInstances}
            </div>
            <div style={{ fontSize: 12, color: '#6b7280', marginTop: 4 }}>实例数范围</div>
            <div style={{ display: 'flex', justifyContent: 'space-around', marginTop: 20 }}>
              <div>
                <div style={{ fontSize: 22, fontWeight: 600, color: COLOR.info }}>{minInstances}</div>
                <div style={{ fontSize: 12, color: '#6b7280' }}>最小实例</div>
              </div>
              <div>
                <div style={{ fontSize: 22, fontWeight: 600, color: COLOR.primary }}>{maxInstances}</div>
                <div style={{ fontSize: 12, color: '#6b7280' }}>最大实例</div>
              </div>
              <div>
                <div style={{ fontSize: 22, fontWeight: 600, color: COLOR.gray }}>{idleTimeoutMin}</div>
                <div style={{ fontSize: 12, color: '#6b7280' }}>空闲超时(分)</div>
              </div>
            </div>
          </div>
        </Card>
      </div>

      {/* 该池下的实例列表 */}
      <Card
        size="small"
        title={
          <span style={{ fontSize: 15, fontWeight: 600 }}>
            实例列表 <Tag style={{ marginLeft: 8 }}>{instanceTotal} 个实例</Tag>
          </span>
        }
      >
        <InstanceTab
          fixedPoolId={poolId}
          embedded
          onShowPodStatus={onShowPodStatus}
          onTotalChange={setInstanceTotal}
        />
      </Card>
    </div>
  );
};

export default PoolDetail;
