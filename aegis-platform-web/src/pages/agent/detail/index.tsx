/**
 * @file 智能体详情
 * @description 5-tab 结构（概览/资源绑定/配置参数/版本历史/API管理）。
 *              操作按钮按角色动态生成（创建者/已订阅/未订阅/通用智能体）。
 *              对接后端 {@code GET /api/admin/agent/{id}}。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Avatar,
  Button,
  Card,
  Popconfirm,
  Space,
  Spin,
  Tag,
} from 'antd';
import { EditOutlined, RocketOutlined, StopOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { getAgentDetail, archiveAgent, submitAgentReview } from '@/api/agent';
import type { Agent } from '@/types/agent';
import { AgentType, LifeStatus } from '@/types/enum';
import { useAuthStore } from '@/stores/authStore';
import { ROUTE_PATH } from '@/utils/constants';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import {
  AGENT_TYPE_LABEL,
  AGENT_TYPE_SHORT,
  LIFE_STATUS_COLOR,
  GOVERNANCE_COLOR,
  GOVERNANCE_LABEL,
  TABS,
} from './constants';
import OverviewTab from './tabs/OverviewTab';
import BindingTab from './tabs/BindingTab';
import ConfigTab from './tabs/ConfigTab';
import VersionTab from './tabs/VersionTab';
import ApiDetailTab from './tabs/ApiDetailTab';

const AgentDetail: React.FC = () => {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const id = params.get('id') ?? '';

  const [loading, setLoading] = useState(false);
  const [agent, setAgent] = useState<Agent | null>(null);
  const [tab, setTab] = useState<string>('overview');
  const user = useAuthStore((s) => s.user);

  const fetchDetail = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const data = await getAgentDetail(id);
      setAgent(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void fetchDetail();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const onSubmitReview = async () => {
    try {
      await submitAgentReview(id);
      message.success('已提交审核，审核通过后将进入智能体市场');
      await fetchDetail();
    } catch {
      // 弹错已处理
    }
  };

  const onArchive = async () => {
    try {
      await archiveAgent(id);
      message.success('已归档下线');
      await fetchDetail();
    } catch {
      // 弹错已处理
    }
  };

  /** 操作按钮：按状态 + 当前用户角色动态生成。
   * 编辑/归档/提交审核 仅对作者（authorUserId === 当前 user.id）可见。
   * 进入对话 对所有人可见。返回列表 对所有人可见。 */
  const footerBtns = useMemo(() => {
    if (!agent) return null;
    const isPublished = agent.lifeStatus === LifeStatus.PUBLISHED;
    const isDraft = agent.lifeStatus === LifeStatus.DRAFT;
    const isArchived = agent.lifeStatus === LifeStatus.ARCHIVED;
    // authorUserId / user.id 都是 string（AgentVO 用 @JsonSerialize(ToStringSerializer)）
    const isAuthor = !!user && !!agent.authorUserId && String(agent.authorUserId) === String(user.id);
    return (
      <Space>
        <Button onClick={() => navigate(ROUTE_PATH.AGENT_LIST)}>返回列表</Button>
        {!isArchived && isAuthor && (
          <Button
            icon={<EditOutlined />}
            onClick={() => navigate(`${ROUTE_PATH.AGENT_EDIT}?id=${agent.id}`)}
          >
            编辑
          </Button>
        )}
        {isDraft && isAuthor && (
          <Button type="primary" onClick={onSubmitReview}>
            提交审核
          </Button>
        )}
        {isPublished && (
          <Button
            type="primary"
            icon={<RocketOutlined />}
            onClick={() => navigate(ROUTE_PATH.WORKBENCH)}
          >
            进入对话
          </Button>
        )}
        {isPublished && isAuthor && (
          <Popconfirm title="确认归档下线该智能体？" onConfirm={onArchive}>
            <Button danger icon={<StopOutlined />}>
              归档下线
            </Button>
          </Popconfirm>
        )}
      </Space>
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent, user]);

  if (loading) {
    return <Spin style={{ display: 'block', marginTop: 120, textAlign: 'center' }} />;
  }

  if (!agent) {
    return (
      <div>
        <PageHeader title="智能体详情" desc="未找到智能体" />
        <Card>未找到智能体（ID: {id || '-'}）</Card>
      </div>
    );
  }

  const isUniversal = agent.agentType === AgentType.UNIVERSAL;
  const isSystem = agent.agentType === AgentType.SYSTEM;
  const memLabel = isUniversal
    ? '🧠 用户归档记忆 · 跨会话保留'
    : '🤖 智能体自我记忆 · 共享上下文';

  return (
    <div>
      <PageHeader
        title={agent.agentName}
        desc={`${AGENT_TYPE_LABEL[agent.agentType] ?? '应用智能体'} · ${agent.version || 'v0.0.1'} · ${memLabel}`}
        extra={footerBtns}
      />

      {/* 基本信息卡片 */}
      <Card style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <Avatar
            size={56}
            style={{
              background: agent.color || '#4f46e5',
              fontSize: 24,
              flexShrink: 0,
            }}
          >
            {agent.icon || '🤖'}
          </Avatar>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 16, fontWeight: 600, marginBottom: 6 }}>
              {agent.agentName}
              <Tag
                color={LIFE_STATUS_COLOR[agent.lifeStatus] ?? 'default'}
                style={{ marginLeft: 8 }}
              >
                {agent.lifeStatus}
              </Tag>
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
              <Tag color={isSystem ? 'geekblue' : 'blue'}>
                {AGENT_TYPE_SHORT[agent.agentType] ?? '应用'}
              </Tag>
              <Tag>{agent.version || 'v0.0.1'}</Tag>
              <Tag color={GOVERNANCE_COLOR[agent.governanceTier ?? 'STANDARD'] ?? 'default'}>
                {GOVERNANCE_LABEL[agent.governanceTier ?? 'STANDARD'] ?? '标准档'}
              </Tag>
              <Tag>本租户可见</Tag>
            </div>
          </div>
        </div>
      </Card>

      <BigTabs tabs={TABS} active={tab} onChange={setTab} />

      {/* Tab 1: 概览 */}
      {tab === 'overview' && <OverviewTab agent={agent} />}

      {/* Tab 2: 资源绑定 */}
      {tab === 'resources' && <BindingTab agent={agent} />}

      {/* Tab 3: 配置参数 */}
      {tab === 'config' && <ConfigTab agent={agent} />}

      {/* Tab 4: 版本历史 */}
      {tab === 'version' && <VersionTab agentId={id} />}

      {/* Tab 5: API 管理 */}
      {tab === 'api' && (
        isSystem ? (
          <ApiDetailTab agent={agent} />
        ) : (
          <Card>
            <Alert
              type="warning"
              showIcon
              message="仅限系统智能体"
              description="API 管理功能仅适用于系统智能体（SYSTEM），其他类型智能体不支持 API 发布。"
            />
          </Card>
        )
      )}
    </div>
  );
};

export default AgentDetail;
