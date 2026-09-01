/**
 * @file 版本历史Tab
 * @description 版本迭代记录展示与版本对比。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import { App, Button, Card, Empty, Modal, Select, Space, Spin, Tag, Timeline, Typography } from 'antd';
import { DiffOutlined } from '@ant-design/icons';
import { getAgentVersions, getAgentVersionDiff } from '@/api/agent';
import type { AgentVersionInfo, VersionDiff } from '@/api/agent';

const { Text } = Typography;

interface VersionTabProps {
  agentId: string;
}

const VersionTab: React.FC<VersionTabProps> = ({ agentId }) => {
  const { message } = App.useApp();

  const [versions, setVersions] = useState<AgentVersionInfo[]>([]);
  const [versionLoading, setVersionLoading] = useState(false);
  const [diffModalOpen, setDiffModalOpen] = useState(false);
  const [versionDiffs, setVersionDiffs] = useState<VersionDiff[]>([]);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffV1, setDiffV1] = useState<string>('');
  const [diffV2, setDiffV2] = useState<string>('');

  const fetchVersions = async () => {
    if (!agentId) return;
    setVersionLoading(true);
    try {
      const data = await getAgentVersions(agentId);
      setVersions(data);
    } catch (err) {
      console.error(err);
    } finally {
      setVersionLoading(false);
    }
  };

  useEffect(() => {
    void fetchVersions();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agentId]);

  const openDiffModal = () => {
    setDiffV1('');
    setDiffV2('');
    setVersionDiffs([]);
    setDiffModalOpen(true);
  };

  const onFetchDiff = async () => {
    if (!diffV1 || !diffV2) {
      message.warning('请选择两个版本进行对比');
      return;
    }
    if (diffV1 === diffV2) {
      message.warning('请选择不同的版本');
      return;
    }
    setDiffLoading(true);
    try {
      const diffs = await getAgentVersionDiff(agentId, diffV1, diffV2);
      setVersionDiffs(diffs);
    } catch (err) {
      console.error(err);
    } finally {
      setDiffLoading(false);
    }
  };

  return (
    <Card>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
        <Text type="secondary" style={{ fontSize: 13 }}>
          版本迭代记录，点击版本可查看变更详情
        </Text>
        <Button icon={<DiffOutlined />} onClick={openDiffModal}>
          版本对比
        </Button>
      </div>
      <Spin spinning={versionLoading}>
        {versions.length === 0 ? (
          <Empty description="暂无版本记录" />
        ) : (
          <Timeline
            items={versions.map((v, idx) => ({
              color: idx === 0 ? 'green' : 'gray',
              children: (
                <div>
                  <div style={{ marginBottom: 4 }}>
                    <Tag color={idx === 0 ? 'green' : 'default'}>{v.version}</Tag>
                    {idx === 0 && <Tag color="blue">当前</Tag>}
                    <span style={{ fontSize: 11, color: '#9ca3af', marginLeft: 8 }}>
                      {v.createdAt}
                    </span>
                  </div>
                  <div style={{ fontSize: 12, color: '#6b7280' }}>
                    {v.systemPrompt ? `System Prompt: ${v.systemPrompt.slice(0, 60)}...` : '无变更描述'}
                    {v.modelTier && ` · 模型: ${v.modelTier}`}
                  </div>
                </div>
              ),
            }))}
          />
        )}
      </Spin>
      <div style={{ marginTop: 16 }}>
        <Button onClick={() => message.success('已订阅版本更新通知')}>
          🔔 订阅版本更新
        </Button>
      </div>

      {/* 版本对比 Modal */}
      <Modal
        title="版本对比"
        open={diffModalOpen}
        onCancel={() => setDiffModalOpen(false)}
        footer={null}
        width={720}
        destroyOnClose
      >
        <div style={{ marginBottom: 16 }}>
          <Space>
            <Select
              style={{ width: 180 }}
              placeholder="选择版本 A"
              value={diffV1 || undefined}
              onChange={setDiffV1}
              options={versions.map((v) => ({ value: v.version, label: v.version }))}
            />
            <span style={{ color: '#9ca3af' }}>vs</span>
            <Select
              style={{ width: 180 }}
              placeholder="选择版本 B"
              value={diffV2 || undefined}
              onChange={setDiffV2}
              options={versions.map((v) => ({ value: v.version, label: v.version }))}
            />
            <Button type="primary" loading={diffLoading} onClick={onFetchDiff}>
              对比
            </Button>
          </Space>
        </div>
        {versionDiffs.length > 0 && (
          <div>
            <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ background: '#f9fafb', textAlign: 'left' }}>
                  <th style={{ padding: '8px 10px', borderBottom: '1px solid #e5e7eb', width: 120 }}>字段</th>
                  <th style={{ padding: '8px 10px', borderBottom: '1px solid #e5e7eb' }}>旧值（{diffV1}）</th>
                  <th style={{ padding: '8px 10px', borderBottom: '1px solid #e5e7eb' }}>新值（{diffV2}）</th>
                </tr>
              </thead>
              <tbody>
                {versionDiffs.map((d, i) => (
                  <tr key={i}>
                    <td style={{ padding: '8px 10px', borderBottom: '1px solid #f0f0f0', fontWeight: 600 }}>
                      {d.field}
                    </td>
                    <td style={{ padding: '8px 10px', borderBottom: '1px solid #f0f0f0', color: '#ef4444', background: '#fef2f2' }}>
                      <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 11 }}>
                        {d.oldValue ?? '-'}
                      </pre>
                    </td>
                    <td style={{ padding: '8px 10px', borderBottom: '1px solid #f0f0f0', color: '#16a34a', background: '#f0fdf4' }}>
                      <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 11 }}>
                        {d.newValue ?? '-'}
                      </pre>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {versionDiffs.length === 0 && diffV1 && diffV2 && !diffLoading && (
          <Empty description="两版本无差异" />
        )}
      </Modal>
    </Card>
  );
};

export default VersionTab;
