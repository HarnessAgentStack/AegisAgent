/**
 * @file 沙箱实例 - Pod 状态查看弹窗
 * @description 查询实例对应的 K8s Pod 详情
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { Descriptions, Modal, Spin, Tag, Typography } from 'antd';
import type { SandboxInstance } from '@/api/sandbox';
import { instanceApi } from '@/api/sandbox';

const { Text } = Typography;

interface InstancePodStatusModalProps {
  visible: boolean;
  record: SandboxInstance | null;
  onCancel: () => void;
}

const InstancePodStatusModal: React.FC<InstancePodStatusModalProps> = ({
  visible,
  record,
  onCancel,
}) => {
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    if (!visible || !record?.instanceId) return;
    setLoading(true);
    setData(null);
    instanceApi
      .getPodStatus(record.instanceId)
      .then((res) => setData(res))
      .catch(() => {
        /* 弹错已处理 */
      })
      .finally(() => setLoading(false));
  }, [visible, record]);

  const get = (key: string) => (data ? (data[key] as string | undefined) : undefined);

  return (
    <Modal
      title={`Pod 状态 - ${record?.instanceId ?? ''}`}
      open={visible}
      onCancel={onCancel}
      footer={null}
      width={720}
      destroyOnClose
    >
      <Spin spinning={loading}>
        <Descriptions column={2} bordered size="small">
          <Descriptions.Item label="Pod 名称">
            <Text code>{get('podName') ?? record?.podName ?? '-'}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="Namespace">
            <Text code>{get('namespace') ?? record?.namespace ?? '-'}</Text>
          </Descriptions.Item>
          <Descriptions.Item label="阶段" span={2}>
            {(() => {
              const phase = get('phase') as string | undefined;
              const color =
                phase === 'Running' ? '#10b981' : phase === 'Pending' || phase === 'Succeeded' ? '#f59e0b' : '#ef4444';
              return phase ? <Tag color={color}>{phase}</Tag> : <Text type="secondary">-</Text>;
            })()}
          </Descriptions.Item>
          <Descriptions.Item label="Pod IP">{get('podIP') ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="节点 IP">{get('hostIP') ?? '-'}</Descriptions.Item>
          <Descriptions.Item label="启动时间" span={2}>
            {get('startTime') ?? '-'}
          </Descriptions.Item>
          <Descriptions.Item label="原始信息" span={2}>
            {data ? (
              <pre style={{ margin: 0, fontSize: 11, maxHeight: 200, overflow: 'auto' }}>
                {JSON.stringify(data, null, 2)}
              </pre>
            ) : (
              <Text type="secondary">无</Text>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Spin>
    </Modal>
  );
};

export default InstancePodStatusModal;
