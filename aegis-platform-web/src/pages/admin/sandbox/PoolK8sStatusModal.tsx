/**
 * @file 沙箱池 - K8s 资源状态查看弹窗
 * @description 查询池对应的 K8s Namespace/ResourceQuota/NetworkPolicy/Pod 列表
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { Descriptions, Modal, Spin, Table, Tag, Typography } from 'antd';
import type { SandboxPool } from '@/api/sandbox';
import { poolApi } from '@/api/sandbox';

const { Text } = Typography;

interface PoolK8sStatusModalProps {
  visible: boolean;
  record: SandboxPool | null;
  onCancel: () => void;
}

interface K8sStatus {
  namespace?: string;
  namespaceExists?: boolean;
  resourceQuota?: Record<string, string>;
  networkPolicy?: { name?: string; types?: string[] };
  pods?: Array<{ name?: string; status?: string; ip?: string; age?: string }>;
  [k: string]: unknown;
}

const PoolK8sStatusModal: React.FC<PoolK8sStatusModalProps> = ({
  visible,
  record,
  onCancel,
}) => {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<K8sStatus | null>(null);

  useEffect(() => {
    if (!visible || !record?.id) return;
    setLoading(true);
    setData(null);
    poolApi
      .getK8sStatus(record.id)
      .then((res) => setData(res as K8sStatus))
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setLoading(false));
  }, [visible, record]);

  const pods = data?.pods ?? [];

  return (
    <Modal
      title={`K8s 资源状态 - ${record?.poolName ?? ''}`}
      open={visible}
      onCancel={onCancel}
      footer={null}
      width={820}
      destroyOnClose
    >
      <Spin spinning={loading}>
        <Descriptions column={2} bordered size="small" style={{ marginBottom: 16 }}>
          <Descriptions.Item label="Namespace">
            <Text code>{data?.namespace ?? record?.namespace ?? '-'}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="命名空间是否存在">
            {data?.namespaceExists ? (
              <Tag color="#10b981">已创建</Tag>
            ) : (
              <Tag color="#ef4444">未创建</Tag>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="ResourceQuota" span={2}>
            {data?.resourceQuota && Object.keys(data.resourceQuota).length > 0 ? (
              <pre style={{ margin: 0, fontSize: 12 }}>
                {JSON.stringify(data.resourceQuota, null, 2)}
              </pre>
            ) : (
              <Text type="secondary">无</Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="NetworkPolicy" span={2}>
            {data?.networkPolicy ? (
              <span>
                <Tag color="#4f46e5">{data.networkPolicy.name ?? '-'}</Tag>
                {data.networkPolicy.types?.map((t) => (
                  <Tag key={t} color="#3b82f6">
                    {t}
                  </Tag>
                ))}
              </span>
            ) : (
              <Text type="secondary">无</Text>
            )}
          </Descriptions.Item>
        </Descriptions>

        <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 8 }}>
          Pod 列表（{pods.length}）
        </div>
        <Table
          rowKey="name"
          size="small"
          dataSource={pods}
          pagination={false}
          columns={[
            { title: 'Pod 名称', dataIndex: 'name', render: (v?: string) => <Text code>{v ?? '-'}</Text> },
            {
              title: '状态',
              dataIndex: 'status',
              width: 120,
              render: (s?: string) => {
                const color = s === 'Running' ? '#10b981' : s === 'Pending' ? '#f59e0b' : '#ef4444';
                return <Tag color={color}>{s ?? '-'}</Tag>;
              },
            },
            { title: 'IP', dataIndex: 'ip', width: 140 },
            { title: '存活时长', dataIndex: 'age', width: 120 },
          ]}
        />
      </Spin>
    </Modal>
  );
};

export default PoolK8sStatusModal;
