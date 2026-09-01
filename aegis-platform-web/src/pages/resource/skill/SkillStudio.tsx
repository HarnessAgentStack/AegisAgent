/**
 * @file 个人 SKILL 中心 - 卡片网格
 * @description 我的技能（所有状态混排 + 搜索 + 状态标签）
 * @author aegis
 * @since 1.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  App,
  Button,
  Empty,
  Input,
  Pagination,
  Space,
  Spin,
  Tag,
} from 'antd';
import {
  EditOutlined,
  DeleteOutlined,
  RollbackOutlined,
  SearchOutlined,
  ExclamationCircleOutlined,
  RocketOutlined,
  StopOutlined,
  PlusOutlined,
  EyeOutlined,
} from '@ant-design/icons';
import { LifeStatusTag } from '@/components/common/LifeStatusTag';
import { LifeStatus } from '@/types/enum';
import type { Skill } from '@/types/resource';
import { skillApi, extractList, extractTotal } from '@/api/resource';
import { ICON, ICON_BG, SKILL_TYPE_TAG } from './constants';

interface SkillStudioProps {
  onCreate: (initialPrompt?: string) => void;
  onEdit: (record: Skill) => void;
  onOpenDetail: (record: Skill) => void;
  onTotalChange?: (total: number) => void;
  refreshSignal?: number;
}

const SkillStudio: React.FC<SkillStudioProps> = ({
  onCreate,
  onEdit,
  onOpenDetail,
  onTotalChange,
  refreshSignal,
}) => {
  const { message, modal } = App.useApp();

  // ===== State =====
  const [keyword, setKeyword] = useState('');
  const [searchInput, setSearchInput] = useState('');

  // ===== Mine List =====
  const [mineList, setMineList] = useState<Skill[]>([]);
  const [mineLoading, setMineLoading] = useState(false);
  const [minePage, setMinePage] = useState(1);
  const [mineSize, setMineSize] = useState(10);
  const [mineTotal, setMineTotal] = useState(0);

  // ===== Load Mine（P1-ITEM-17：仅加载当前用户创建的技能，所有状态混排） =====
  const loadMine = async () => {
    setMineLoading(true);
    try {
      const res = await skillApi.mine({
        keyword: keyword || undefined,
        page: minePage,
        size: mineSize,
      });
      setMineList(extractList(res));
      setMineTotal(extractTotal(res));
    } catch {
      /* handled */
    } finally {
      setMineLoading(false);
    }
  };

  useEffect(() => {
    loadMine();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, minePage, mineSize, refreshSignal]);

  useEffect(() => {
    if (onTotalChange) {
      onTotalChange(mineTotal);
    }
  }, [mineTotal, onTotalChange]);

  // ===== Actions =====
  const handleSubmitForReview = async (record: Skill) => {
    try {
      await skillApi.submitReview(record.id!);
      message.success(`「${record.skillName}」已提交审核`);
      loadMine();
    } catch {
      /* handled */
    }
  };

  const handleDelete = (record: Skill) => {
    modal.confirm({
      title: `确认删除「${record.skillName}」？`,
      icon: <ExclamationCircleOutlined />,
      content: '删除后不可恢复，关联的订阅数据也会清除',
      okType: 'danger',
      okText: '删除',
      onOk: async () => {
        try {
          await skillApi.remove(record.id!);
          message.success('删除成功');
          loadMine();
        } catch {
          /* handled */
        }
      },
    });
  };

  const handleRollback = async (record: Skill) => {
    try {
      await skillApi.revertDraft(record.id!);
      message.success(`「${record.skillName}」已回退到草稿`);
      loadMine();
    } catch {
      /* handled */
    }
  };

  const handleArchive = async (record: Skill) => {
    modal.confirm({
      title: `确认归档「${record.skillName}」？`,
      content: '归档后技能将不再出现在市场中，可随时恢复',
      okText: '归档',
      onOk: async () => {
        try {
          await skillApi.archive(record.id!);
          message.success('归档成功');
          loadMine();
        } catch {
          /* handled */
        }
      },
    });
  };

  // ===== Render Skill Card =====
  const renderSkillCard = (record: Skill) => {
    const status = record.lifeStatus ?? LifeStatus.DRAFT;
    const isDraft = status === LifeStatus.DRAFT;
    const isRejected = status === LifeStatus.REJECTED;
    const isReviewing = status === LifeStatus.REVIEWING;
    const isPublished = status === LifeStatus.PUBLISHED;
    const isArchived = status === LifeStatus.ARCHIVED;

    const renderActions = () => {
      const actions: React.ReactNode[] = [];

      if (isDraft || isRejected) {
        actions.push(
          <Button key="edit" size="small" type="primary" icon={<EditOutlined />} onClick={() => onEdit(record)}>
            编辑
          </Button>,
        );
        actions.push(
          <Button key="submit" size="small" icon={<RocketOutlined />} onClick={() => handleSubmitForReview(record)}>
            提交审核
          </Button>,
        );
      }

      if (isPublished) {
        actions.push(
          <Button key="edit" size="small" icon={<EditOutlined />} onClick={() => onEdit(record)}>
            编辑
          </Button>,
        );
        actions.push(
          <Button key="rollback" size="small" icon={<RollbackOutlined />} onClick={() => handleRollback(record)}>
            回退
          </Button>,
        );
        actions.push(
          <Button key="archive" size="small" icon={<StopOutlined />} onClick={() => handleArchive(record)}>
            归档
          </Button>,
        );
      }

      if (isArchived) {
        actions.push(
          <Button key="rollback" size="small" icon={<RollbackOutlined />} onClick={() => handleRollback(record)}>
            恢复
          </Button>,
        );
      }

      if (isReviewing) {
        actions.push(
          <Button key="view" size="small" icon={<EyeOutlined />} onClick={() => onOpenDetail(record)}>
            查看
          </Button>,
        );
      }

      actions.push(
        <Button key="detail" size="small" icon={<EyeOutlined />} onClick={() => onOpenDetail(record)}>
          详情
        </Button>,
      );

      if (isDraft || isRejected) {
        actions.push(
          <Button key="delete" size="small" danger icon={<DeleteOutlined />} onClick={() => handleDelete(record)}>
            删除
          </Button>,
        );
      }

      return <Space size={4}>{actions}</Space>;
    };

    return (
      <div
        key={record.id}
        onClick={() => onOpenDetail(record)}
        style={{
          border: '1px solid #e5e7eb',
          borderRadius: 8,
          padding: 16,
          background: '#fff',
          cursor: 'pointer',
          transition: 'all 0.2s',
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
        }}
        onMouseEnter={(e) => {
          (e.currentTarget as HTMLDivElement).style.boxShadow = '0 4px 12px rgba(0,0,0,0.08)';
          (e.currentTarget as HTMLDivElement).style.transform = 'translateY(-2px)';
        }}
        onMouseLeave={(e) => {
          (e.currentTarget as HTMLDivElement).style.boxShadow = 'none';
          (e.currentTarget as HTMLDivElement).style.transform = 'translateY(0)';
        }}
      >
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 12 }}>
          <div
            style={{
              width: 44,
              height: 44,
              borderRadius: 8,
              background: ICON_BG[record.skillType === 'COMPOSITE' ? 'COMPOSITE' : 'ATOMIC'] || '#e6f4ff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 22,
              flexShrink: 0,
            }}
          >
            {ICON[record.skillType === 'COMPOSITE' ? 'COMPOSITE' : 'ATOMIC'] || '🛠️'}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div
              style={{
                fontWeight: 600,
                fontSize: 15,
                color: '#111827',
                marginBottom: 4,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {record.skillName}
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <LifeStatusTag status={status} />
              {record.securityLevel && (
                <Tag color={SKILL_TYPE_TAG[record.skillType]?.color || 'blue'} style={{ margin: 0 }}>
                  {record.securityLevel}
                </Tag>
              )}
              {record.version && (
                <Tag style={{ margin: 0 }}>v{record.version}</Tag>
              )}
            </div>
          </div>
        </div>

        <div style={{ fontSize: 13, color: '#4b5563', marginBottom: 10, flex: 1, overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
          {record.description || '暂无描述'}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: 12, color: '#6b7280' }}>
            <span>📥 {record.subsCount ?? 0}</span>
          </div>
          {record.updatedAt && (
            <span style={{ fontSize: 11, color: '#9ca3af' }}>
              {new Date(record.updatedAt).toLocaleDateString()}
            </span>
          )}
        </div>

        {/* Actions */}
        <div
          style={{
            borderTop: '1px solid #f3f4f6',
            paddingTop: 10,
            display: 'flex',
            justifyContent: 'flex-end',
          }}
          onClick={(e) => e.stopPropagation()}
        >
          {renderActions()}
        </div>
      </div>
    );
  };

  return (
    <div>
      {/* Filter Bar */}
      <div style={{ display: 'flex', gap: 12, marginBottom: 20, flexWrap: 'wrap', alignItems: 'center' }}>
        <Input.Search
          placeholder="搜索技能名称/描述/标签"
          value={searchInput}
          onChange={(e) => {
            setSearchInput(e.target.value);
            if (e.target.value === '') {
              setKeyword('');
              setMinePage(1);
            }
          }}
          onSearch={(v) => {
            setKeyword(v);
            setMinePage(1);
          }}
          allowClear
          style={{ width: 280 }}
          enterButton={<SearchOutlined />}
        />

        <div style={{ flex: 1 }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => onCreate()} size="large">
          ➕ 对话创建技能（推荐）
        </Button>
      </div>

      {/* Mine View */}
      <Spin spinning={mineLoading}>
        {mineList.length === 0 ? (
          <Empty
            description={
              keyword
                ? `未找到包含「${keyword}」的技能`
                : '还没有创建任何技能，点击右上角创建第一个吧'
            }
          />
        ) : (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
              gap: 16,
            }}
          >
            {mineList.map((s) => renderSkillCard(s))}
          </div>
        )}

        {/* Pagination */}
        {mineTotal > mineSize && (
          <div style={{ textAlign: 'center', marginTop: 24 }}>
            <Pagination
              current={minePage}
              pageSize={mineSize}
              total={mineTotal}
              showSizeChanger
              onChange={(p, sz) => {
                setMinePage(p);
                setMineSize(sz);
              }}
            />
          </div>
        )}
      </Spin>
    </div>
  );
};

export default SkillStudio;
