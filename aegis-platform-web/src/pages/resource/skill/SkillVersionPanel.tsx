/**
 * @file 技能版本管理面板
 * @description 版本历史管理面板：版本指针指示、版本列表、回滚、灰度发布、版本差异查看
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  List,
  Modal,
  Slider,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  CheckCircleFilled,
  CloseCircleOutlined,
  HistoryOutlined,
  RollbackOutlined,
  ThunderboltOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import type { SkillVersion, SkillVersionDiff } from '@/types/resource';
import { skillApi } from '@/api/resource';

const { Text, Paragraph } = Typography;

interface SkillVersionPanelProps {
  skillId: string;
  onClose: () => void;
  refreshSignal?: number;
}

const SkillVersionPanel: React.FC<SkillVersionPanelProps> = ({
  skillId,
  onClose,
  refreshSignal,
}) => {
  const { message, modal } = App.useApp();
  const [loading, setLoading] = useState(false);
  const [versions, setVersions] = useState<SkillVersion[]>([]);

  const [rollbackTarget, setRollbackTarget] = useState<SkillVersion | null>(null);
  const [rollbackLoading, setRollbackLoading] = useState(false);

  const [grayTarget, setGrayTarget] = useState<SkillVersion | null>(null);
  const [grayPercent, setGrayPercent] = useState(10);
  const [grayLoading, setGrayLoading] = useState(false);

  const [diffTarget, setDiffTarget] = useState<{
    from: SkillVersion;
    to: SkillVersion;
  } | null>(null);
  const [diffResult, setDiffResult] = useState<SkillVersionDiff | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  const fetchVersions = async () => {
    setLoading(true);
    try {
      const list = await skillApi.getVersionHistory(skillId);
      setVersions(list || []);
    } catch {
      setVersions([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (skillId) {
      fetchVersions();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [skillId, refreshSignal]);

  const currentPointer = versions.find((v) => v.isActive);

  const handleRollback = async () => {
    if (!rollbackTarget) return;
    setRollbackLoading(true);
    try {
      await skillApi.rollbackVersion(skillId, {
        targetVersion: rollbackTarget.version,
      });
      message.success(`已回滚至版本 v${rollbackTarget.version}`);
      setRollbackTarget(null);
      fetchVersions();
    } catch {
      /* 错误已由拦截器处理 */
    } finally {
      setRollbackLoading(false);
    }
  };

  const handleGrayRelease = async () => {
    if (!grayTarget) return;
    setGrayLoading(true);
    try {
      await skillApi.grayReleaseVersion(skillId, {
        version: grayTarget.version,
        percent: grayPercent,
      });
      message.success(`已对 v${grayTarget.version} 开启 ${grayPercent}% 灰度`);
      setGrayTarget(null);
      fetchVersions();
    } catch {
      /* 错误已由拦截器处理 */
    } finally {
      setGrayLoading(false);
    }
  };

  const handleViewDiff = async (from: SkillVersion, to: SkillVersion) => {
    setDiffTarget({ from, to });
    setDiffResult(null);
    setDiffLoading(true);
    try {
      const diff = await skillApi.getVersionDiff(skillId, from.version, to.version);
      setDiffResult(diff);
    } catch {
      setDiffResult(null);
    } finally {
      setDiffLoading(false);
    }
  };

  const confirmRollback = (version: SkillVersion) => {
    modal.confirm({
      title: '确认回滚',
      content: `确定将技能回滚至版本 v${version.version}？回滚后该版本将成为当前指针版本。`,
      okText: '确认回滚',
      cancelText: '取消',
      okButtonProps: { danger: true },
      onOk: () => setRollbackTarget(version),
    });
  };

  const openGrayRelease = (version: SkillVersion) => {
    setGrayTarget(version);
    setGrayPercent(10);
  };

  const renderVersionItem = (version: SkillVersion) => {
    const isCurrent = !!version.isActive;
    const isGray = !!version.isCanary;

    return (
      <List.Item
        key={version.id}
        style={{
          background: isCurrent ? '#f6ffed' : isGray ? '#e6f7ff' : 'transparent',
          border: isCurrent ? '1px solid #b7eb8f' : isGray ? '1px solid #91d5ff' : '1px solid #f0f0f0',
          borderRadius: 8,
          marginBottom: 8,
          padding: 12,
        }}
      >
        <div style={{ width: '100%' }}>
          <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }}>
            <Space direction="vertical" size={4}>
              <Space>
                <Text strong style={{ fontSize: 15 }}>
                  v{version.version}
                </Text>
                {isCurrent && (
                  <Tag color="green" icon={<CheckCircleFilled />}>
                    当前版本
                  </Tag>
                )}
                {isGray && !isCurrent && (
                  <Tag color="blue">灰度中</Tag>
                )}
                {version.isPointerOnly && (
                  <Tag color="default">指针记录</Tag>
                )}
              </Space>
              {version.description && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  {version.description}
                </Text>
              )}
              <Space size={16} style={{ fontSize: 12 }}>
                <Text type="secondary">
                  <HistoryOutlined /> {version.createTime || '-'}
                </Text>
                {version.category && (
                  <Text type="secondary">分类: {version.category}</Text>
                )}
              </Space>
            </Space>

            <Space>
              {!isCurrent && (
                <Button
                  size="small"
                  icon={<RollbackOutlined />}
                  onClick={() => confirmRollback(version)}
                >
                  回滚
                </Button>
              )}
              <Button
                size="small"
                icon={<ThunderboltOutlined />}
                onClick={() => openGrayRelease(version)}
              >
                灰度发布
              </Button>
              {currentPointer && !isCurrent && (
                <Button
                  size="small"
                  onClick={() => handleViewDiff(version, currentPointer)}
                >
                  查看差异
                </Button>
              )}
            </Space>
          </Space>
        </div>
      </List.Item>
    );
  };

  return (
    <>
      <Modal
        title="版本管理"
        open={true}
        onCancel={onClose}
        footer={null}
        width={720}
        destroyOnClose
      >
        <Card size="small" style={{ marginBottom: 16 }}>
          <Descriptions column={2} size="small">
            <Descriptions.Item label="当前版本">
              {currentPointer ? (
                <Space>
                  <Tag color="green">v{currentPointer.version}</Tag>
                  {currentPointer.isCanary && (
                    <Tag color="blue">灰度中</Tag>
                  )}
                </Space>
              ) : (
                <Tag>暂无发布版本</Tag>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="版本总数">
              {versions.length}
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <List
          loading={loading}
          dataSource={versions}
          renderItem={renderVersionItem}
          locale={{ emptyText: '暂无版本记录' }}
        />
      </Modal>

      {/* 回滚确认弹窗 */}
      <Modal
        title="回滚版本"
        open={!!rollbackTarget}
        onCancel={() => setRollbackTarget(null)}
        onOk={handleRollback}
        confirmLoading={rollbackLoading}
        okText="确认回滚"
        cancelText="取消"
        okButtonProps={{ danger: true }}
      >
        {rollbackTarget && (
          <Paragraph>
            确定将技能回滚至版本 <Text strong>v{rollbackTarget.version}</Text>？
            <br />
            回滚后该版本将成为当前生效版本，此操作可再次回滚撤销。
          </Paragraph>
        )}
      </Modal>

      {/* 灰度发布弹窗 */}
      <Modal
        title="灰度发布"
        open={!!grayTarget}
        onCancel={() => setGrayTarget(null)}
        onOk={handleGrayRelease}
        confirmLoading={grayLoading}
        okText="确认发布"
        cancelText="取消"
      >
        {grayTarget && (
          <>
            <Paragraph>
              将版本 <Text strong>v{grayTarget.version}</Text> 设为灰度发布
            </Paragraph>
            <Paragraph type="secondary" style={{ marginBottom: 8 }}>
              灰度比例：{grayPercent}%
            </Paragraph>
            <Slider
              min={1}
              max={100}
              value={grayPercent}
              onChange={setGrayPercent}
              marks={{
                1: '1%',
                10: '10%',
                30: '30%',
                50: '50%',
                100: '100%',
              }}
            />
            <Alert
              type="info"
              showIcon
              message="灰度发布说明"
              description="灰度版本将按比例接收流量，其余流量继续走当前版本。可随时调整比例或全量发布。"
              style={{ marginTop: 12 }}
            />
          </>
        )}
      </Modal>

      {/* 版本差异弹窗 */}
      <Modal
        title={
          diffTarget
            ? `版本差异对比: v${diffTarget.from.version} → v${diffTarget.to.version}`
            : '版本差异'
        }
        open={!!diffTarget}
        onCancel={() => {
          setDiffTarget(null);
          setDiffResult(null);
        }}
        footer={null}
        width={560}
      >
        {diffLoading ? (
          <div style={{ textAlign: 'center', padding: 24 }}>
            <SyncOutlined spin />
            <div style={{ marginTop: 8 }}>正在加载差异...</div>
          </div>
        ) : diffResult ? (
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            {Object.entries(diffResult.fields || {})
              .filter(([, v]) => (v as { changed: boolean }).changed)
              .length === 0 ? (
                <div style={{ textAlign: 'center', padding: 24 }}>
                  <CloseCircleOutlined style={{ fontSize: 40, color: '#bfbfbf' }} />
                  <Paragraph type="secondary" style={{ marginTop: 8 }}>
                    两个版本之间未检测到差异
                  </Paragraph>
                </div>
              ) : (
                Object.entries(diffResult.fields)
                  .filter(([, v]) => (v as { changed: boolean }).changed)
                  .map(([field, value]) => {
                    const v = value as { from: unknown; to: unknown };
                    const fromStr = v.from == null ? '(空)' : String(v.from).slice(0, 120);
                    const toStr = v.to == null ? '(空)' : String(v.to).slice(0, 120);
                    return (
                      <div
                        key={field}
                        style={{
                          padding: '8px 12px',
                          background: '#fffbe6',
                          borderLeft: '3px solid #faad14',
                          borderRadius: 4,
                        }}
                      >
                        <Text strong style={{ fontSize: 13 }}>{field}</Text>
                        <div style={{ marginTop: 6, fontSize: 12 }}>
                          <div style={{ color: '#ff4d4f', marginBottom: 4 }}>
                            - {fromStr}{String(v.from || '').length > 120 ? '...' : ''}
                          </div>
                          <div style={{ color: '#52c41a' }}>
                            + {toStr}{String(v.to || '').length > 120 ? '...' : ''}
                          </div>
                        </div>
                      </div>
                    );
                  })
              )}
          </Space>
        ) : null}
      </Modal>
    </>
  );
};

export default SkillVersionPanel;