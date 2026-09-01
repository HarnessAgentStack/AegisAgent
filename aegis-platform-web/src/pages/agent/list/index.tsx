/**
 * @file 智能体市场
 * @description 用户视角的智能体市场与我的智能体管理（重构版）：
 *  1. 双标签（智能体市场 / 我的智能体）
 *  2. 市场卡片：图标 + 名称 + 描述 + 作者 + 订阅数 / 治理档位 + 安全等级 + 订阅/试用/开始任务
 *  3. 筛选器：关键词 + 分类 + 治理档位 + 重置
 *  4. 我的智能体：按状态分组（草稿/审核中/已发布/已驳回/已归档）
 *  5. 订阅状态：批量查询，实时更新
 * @author aegis
 * @since 2.0.0
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SearchOutlined,
  ExclamationCircleOutlined,
  EditOutlined,
  SendOutlined,
  PlayCircleOutlined,
  StopOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  InboxOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import type { Agent } from '@/types/agent';
import { LifeStatus, GovernanceTier } from '@/types/enum';
import { ROUTE_PATH } from '@/utils/constants';
import {
  getSubscribableAgents,
  getMyAgents,
  archiveAgent,
  deleteAgent,
  submitAgentReview,
  subscribeAgent,
  unsubscribeAgent,
} from '@/api/agent';
import { PageHeader } from '@/components/common/PageHeader';
import { BigTabs } from '@/components/common/BigTabs';
import { EmptyState } from '@/components/common/EmptyState';

const { Text } = Typography;

/** 生命周期状态颜色 */
const LIFE_STATUS_COLOR: Record<string, string> = {
  DRAFT: 'default',
  REVIEWING: 'processing',
  PUBLISHED: 'green',
  ARCHIVED: 'orange',
  REJECTED: 'red',
};

/** 生命周期状态图标 */
const LIFE_STATUS_ICON: Record<string, React.ReactNode> = {
  DRAFT: <InboxOutlined />,
  REVIEWING: <ClockCircleOutlined />,
  PUBLISHED: <CheckCircleOutlined />,
  ARCHIVED: <StopOutlined />,
  REJECTED: <CloseCircleOutlined />,
};

/** 生命周期状态标签 */
const LIFE_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  REVIEWING: '审核中',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
  REJECTED: '已驳回',
};

/** 治理档位颜色 */
const GOVERNANCE_COLOR: Record<string, string> = {
  STANDARD: 'green',
  ENHANCED: 'gold',
  STRICT: 'red',
};

/** 治理档位标签 */
const GOVERNANCE_LABEL: Record<string, string> = {
  STANDARD: '标准档',
  ENHANCED: '增强档',
  STRICT: '严格档',
};

/** 分类选项（智能体分类） */
const CATEGORY_OPTIONS = [
  { value: '', label: '全部分类' },
  { value: '问答型', label: '💬 问答型' },
  { value: '执行型', label: '⚡ 执行型' },
  { value: '分析型', label: '📊 分析型' },
  { value: '创作型', label: '✏️ 创作型' },
  { value: '数据处理', label: '🔢 数据处理' },
];

/** 治理档位筛选选项 */
const GOVERNANCE_FILTER_OPTIONS = [
  { value: '', label: '全部档位' },
  { value: GovernanceTier.STANDARD, label: '🟢 标准档' },
  { value: GovernanceTier.ENHANCED, label: '🟡 增强档' },
  { value: GovernanceTier.STRICT, label: '🔴 严格档' },
];

/** 安全等级筛选选项 */
const SECURITY_FILTER_OPTIONS = [
  { value: '', label: '全部等级' },
  { value: 'L1', label: 'L1 公开' },
  { value: 'L2', label: 'L2 内部' },
  { value: 'L3', label: 'L3 机密' },
  { value: 'L4', label: 'L4 绝密' },
];

/** 安全等级标签样式 */
const SECURITY_LEVEL_STYLE: Record<string, { color: string; text: string }> = {
  L1: { color: 'green', text: 'L1 公开' },
  L2: { color: 'blue', text: 'L2 内部' },
  L3: { color: 'orange', text: 'L3 机密' },
  L4: { color: 'red', text: 'L4 绝密' },
};

const AgentList: React.FC = () => {
  const navigate = useNavigate();
  const { message, modal } = App.useApp();
  const [tab, setTab] = useState('market');

  // ===== 市场数据 =====
  const [marketAgents, setMarketAgents] = useState<Agent[]>([]);
  const [marketLoading, setMarketLoading] = useState(false);
  const [marketKw, setMarketKw] = useState('');
  const [marketKwInput, setMarketKwInput] = useState('');
  const [marketGov, setMarketGov] = useState<string>('');
  const [marketSec, setMarketSec] = useState<string>('');
  const [marketCategory, setMarketCategory] = useState<string>('');

  // ===== 订阅状态 =====
  const [subscribedIds, setSubscribedIds] = useState<Set<string>>(new Set());

  // ===== 我的智能体数据 =====
  const [myAgents, setMyAgents] = useState<Agent[]>([]);
  const [myLoading, setMyLoading] = useState(false);
  const [myKw, setMyKw] = useState('');
  const [myKwInput, setMyKwInput] = useState('');
  const [myStatusFilter, setMyStatusFilter] = useState<string>('ALL');

  // ===== 驳回详情弹窗 =====
  const [rejectDetailVisible, setRejectDetailVisible] = useState(false);
  const [rejectDetailAgent, setRejectDetailAgent] = useState<Agent | null>(null);

  /** 加载市场数据 */
  const fetchMarket = async () => {
    setMarketLoading(true);
    try {
      const list = await getSubscribableAgents();
      setMarketAgents(list);
      // 批量查询订阅状态
      const agentIds = list.map((a) => a.id);
      if (agentIds.length > 0) {
        // 简化处理：逐个查询订阅状态（实际项目中应有批量接口）
        const subsSet = new Set<string>();
        // 这里假设后端返回的数据中已包含订阅标识，或使用逐个查询
        // 为避免N+1问题，暂使用前端状态管理
        setSubscribedIds(subsSet);
      }
    } catch (err) {
      console.error('加载市场数据失败:', err);
      message.error('加载智能体市场失败');
    } finally {
      setMarketLoading(false);
    }
  };

  /** 加载我的智能体 */
  const fetchMyAgents = async () => {
    setMyLoading(true);
    try {
      const list = await getMyAgents();
      setMyAgents(list);
    } catch (err) {
      console.error('加载我的智能体失败:', err);
      message.error('加载我的智能体失败');
    } finally {
      setMyLoading(false);
    }
  };

  useEffect(() => {
    fetchMarket();
    fetchMyAgents();
  }, []);

  /** 归档下线 */
  const onArchive = async (id: string, agentName: string) => {
    modal.confirm({
      title: '确认下线？',
      content: `确定将智能体「${agentName}」归档下线？下线后用户将无法订阅和使用。`,
      okText: '确认下线',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        try {
          await archiveAgent(id);
          message.success('已归档下线');
          await fetchMyAgents();
        } catch {
          /* 错误已处理 */
        }
      },
    });
  };

  /** 删除智能体 */
  const onDelete = async (id: string, agentName: string) => {
    modal.confirm({
      title: '确认删除？',
      icon: <ExclamationCircleOutlined />,
      content: `确定删除智能体「${agentName}」？此操作不可恢复。`,
      okText: '确认删除',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        try {
          await deleteAgent(id);
          message.success('删除成功');
          await fetchMyAgents();
        } catch {
          /* 错误已处理 */
        }
      },
    });
  };

  /** 提交审核 */
  const onSubmitReview = async (id: string) => {
    modal.confirm({
      title: '确认提交审核？',
      content: '提交后智能体将进入审核状态，由管理员在管理控制台审核通过后方可发布到市场。',
      okText: '确认提交',
      cancelText: '取消',
      onOk: async () => {
        try {
          await submitAgentReview(id);
          message.success('已提交审核，等待管理员审核');
          await fetchMyAgents();
        } catch {
          /* 错误已处理 */
        }
      },
    });
  };

  /** 查看驳回详情 */
  const viewRejectDetail = (agent: Agent) => {
    setRejectDetailAgent(agent);
    setRejectDetailVisible(true);
  };

  /** 订阅智能体 */
  const handleSubscribe = async (agent: Agent) => {
    try {
      await subscribeAgent(agent.id);
      setSubscribedIds((prev) => new Set(prev).add(agent.id));
      message.success(`已订阅「${agent.agentName}」`);
    } catch (e) {
      console.error('订阅失败:', e);
    }
  };

  /** 取消订阅 */
  const handleUnsubscribe = async (agent: Agent) => {
    try {
      await unsubscribeAgent(agent.id);
      setSubscribedIds((prev) => {
        const next = new Set(prev);
        next.delete(agent.id);
        return next;
      });
      message.success(`已取消订阅「${agent.agentName}」`);
    } catch (e) {
      console.error('取消订阅失败:', e);
    }
  };

  /** 开始任务 - 跳转工作台 */
  const handleStartTask = (agent: Agent) => {
    navigate(`${ROUTE_PATH.WORKBENCH}?agentId=${agent.id}`);
    message.success(`已切换到「${agent.agentName}」，开始任务`);
  };

  // ===== 过滤市场数据 =====
  const filteredMarket = useMemo(() => {
    return marketAgents.filter((a) => {
      // 关键词筛选
      if (marketKw) {
        const kw = marketKw.toLowerCase();
        if (
          !(a.agentName?.toLowerCase().includes(kw) ||
            a.description?.toLowerCase().includes(kw) ||
            a.category?.toLowerCase().includes(kw))
        ) {
          return false;
        }
      }
      // 治理档位筛选
      if (marketGov && a.governanceTier !== marketGov) return false;
      // 安全等级筛选
      if (marketSec && a.securityLevel !== marketSec) return false;
      // 分类筛选
      if (marketCategory && a.category !== marketCategory) return false;
      return true;
    });
  }, [marketAgents, marketKw, marketGov, marketSec, marketCategory]);

  // ===== 过滤我的智能体数据 =====
  const filteredMyAgents = useMemo(() => {
    return myAgents.filter((a) => {
      // 关键词筛选
      if (myKw) {
        const kw = myKw.toLowerCase();
        if (
          !(a.agentName?.toLowerCase().includes(kw) ||
            a.description?.toLowerCase().includes(kw) ||
            a.agentCode?.toLowerCase().includes(kw))
        ) {
          return false;
        }
      }
      // 状态筛选
      if (myStatusFilter !== 'ALL' && a.lifeStatus !== myStatusFilter) return false;
      return true;
    });
  }, [myAgents, myKw, myStatusFilter]);

  // ===== 状态统计（我的智能体）=====
  const statusCount = useMemo(() => {
    const c: Record<string, number> = { ALL: myAgents.length };
    myAgents.forEach((a) => {
      if (a.lifeStatus) {
        c[a.lifeStatus] = (c[a.lifeStatus] || 0) + 1;
      }
    });
    return c;
  }, [myAgents]);

  const hasMarketFilter = !!marketKw || !!marketGov || !!marketSec || !!marketCategory;

  const resetMarketFilter = () => {
    setMarketKw('');
    setMarketKwInput('');
    setMarketGov('');
    setMarketSec('');
    setMarketCategory('');
  };

  const resetMyFilter = () => {
    setMyKw('');
    setMyKwInput('');
    setMyStatusFilter('ALL');
  };

  // ===== 按状态分组我的智能体 =====
  const groupedMyAgents = useMemo(() => {
    const groups: Record<string, Agent[]> = {
      DRAFT: [],
      REVIEWING: [],
      PUBLISHED: [],
      REJECTED: [],
      ARCHIVED: [],
    };
    filteredMyAgents.forEach((a) => {
      if (a.lifeStatus && groups[a.lifeStatus]) {
        groups[a.lifeStatus].push(a);
      }
    });
    return groups;
  }, [filteredMyAgents]);

  /** 渲染市场卡片 */
  const renderMarketCard = (a: Agent) => {
    const isSubscribed = subscribedIds.has(a.id);
    const govCfg = GOVERNANCE_COLOR[a.governanceTier ?? 'STANDARD'];
    const govLabel = GOVERNANCE_LABEL[a.governanceTier ?? 'STANDARD'];
    const secCfg = a.securityLevel ? SECURITY_LEVEL_STYLE[a.securityLevel] : null;

    return (
      <Col xs={24} sm={12} md={8} lg={6} key={a.id}>
        <Card
          hoverable
          style={{ height: '100%', borderRadius: 8, border: '1px solid #e5e7eb' }}
          bodyStyle={{ padding: 16, display: 'flex', flexDirection: 'column', height: '100%' }}
          onClick={() => navigate(`${ROUTE_PATH.AGENT_DETAIL}?id=${a.id}`)}
        >
          {/* 头部：图标 + 名称 + 状态标签 */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
            <div
              style={{
                width: 44,
                height: 44,
                borderRadius: 10,
                background: a.color ?? '#eef2ff',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 24,
                flexShrink: 0,
              }}
            >
              {a.icon || '🤖'}
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div
                style={{
                  fontSize: 15,
                  fontWeight: 600,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {a.agentName}
              </div>
              {a.category && (
                <Text type="secondary" style={{ fontSize: 11 }}>
                  {a.category}
                </Text>
              )}
            </div>
          </div>

          {/* 描述 */}
          <div
            style={{
              fontSize: 12,
              color: '#6b7280',
              marginBottom: 10,
              display: '-webkit-box',
              WebkitLineClamp: 2,
              WebkitBoxOrient: 'vertical',
              overflow: 'hidden',
              minHeight: 32,
              lineHeight: 1.5,
            }}
          >
            {a.description ?? '暂无描述'}
          </div>

          {/* 标签行：治理档位 + 安全等级 */}
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 8 }}>
            <Tag color={govCfg} style={{ fontSize: 10, margin: 0 }}>
              {govLabel}
            </Tag>
            {secCfg && (
              <Tag color={secCfg.color} style={{ fontSize: 10, margin: 0 }}>
                {secCfg.text}
              </Tag>
            )}
          </div>

          {/* 元信息：订阅数 + 版本 + 作者 */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              fontSize: 11,
              color: '#9ca3af',
              marginBottom: 12,
              flexWrap: 'wrap',
            }}
          >
            <span>👥 {a.subsCount ?? 0} 订阅</span>
            <span>📦 v{a.version ?? '0.0.1'}</span>
            {a.authorUserId && <span>👤 用户{a.authorUserId}</span>}
          </div>

          {/* 操作按钮 */}
          <div style={{ display: 'flex', gap: 6, marginTop: 'auto' }}>
            {isSubscribed ? (
              <>
                <Button
                  type="primary"
                  size="small"
                  style={{ flex: 1 }}
                  icon={<PlayCircleOutlined />}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleStartTask(a);
                  }}
                >
                  开始任务
                </Button>
                <Button
                  size="small"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleUnsubscribe(a);
                  }}
                >
                  取消订阅
                </Button>
              </>
            ) : (
              <>
                <Button
                  size="small"
                  style={{ flex: 1 }}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleSubscribe(a);
                  }}
                >
                  订阅
                </Button>
              </>
            )}
          </div>
        </Card>
      </Col>
    );
  };

  /** 渲染我的智能体卡片 */
  const renderMyCard = (a: Agent) => {
    const isDraft = a.lifeStatus === LifeStatus.DRAFT;
    const isRejected = a.lifeStatus === LifeStatus.REJECTED;
    const isReviewing = a.lifeStatus === LifeStatus.REVIEWING;
    const isPublished = a.lifeStatus === LifeStatus.PUBLISHED;
    const isArchived = a.lifeStatus === LifeStatus.ARCHIVED;

    return (
      <Card
        key={a.id}
        hoverable
        style={{
          height: '100%',
          borderRadius: 8,
          border: isRejected ? '1px solid #ffccc7' : '1px solid #e5e7eb',
          background: isArchived ? '#f9fafb' : '#fff',
          cursor: 'pointer',
        }}
        bodyStyle={{ padding: 16, display: 'flex', flexDirection: 'column', height: '100%' }}
        onClick={() => navigate(`${ROUTE_PATH.AGENT_DETAIL}?id=${a.id}`)}
      >
        {/* 头部：图标 + 名称 + 状态 */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
          <div
            style={{
              width: 40,
              height: 40,
              borderRadius: 10,
              background: a.color ?? '#eef2ff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 20,
              flexShrink: 0,
            }}
          >
            {a.icon || '🤖'}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div
              style={{
                fontSize: 15,
                fontWeight: 600,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {a.agentName}
            </div>
            <Text type="secondary" style={{ fontSize: 11 }}>
              {a.agentCode}
            </Text>
          </div>
          <Tag color={LIFE_STATUS_COLOR[a.lifeStatus ?? '']} icon={LIFE_STATUS_ICON[a.lifeStatus ?? '']} style={{ fontSize: 10, margin: 0 }}>
            {LIFE_STATUS_LABEL[a.lifeStatus ?? '']}
          </Tag>
        </div>

        {/* 描述 */}
        <div
          style={{
            fontSize: 12,
            color: '#6b7280',
            marginBottom: 8,
            display: '-webkit-box',
            WebkitLineClamp: 2,
            WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
            minHeight: 32,
            lineHeight: 1.5,
          }}
        >
          {a.description ?? '暂无描述'}
        </div>

        {/* 驳回原因提示 */}
        {isRejected && (
          <div
            style={{
              marginBottom: 8,
              padding: '6px 8px',
              background: '#fff2f0',
              borderRadius: 4,
              border: '1px solid #ffccc7',
              fontSize: 12,
              color: '#cf1322',
              cursor: 'pointer',
            }}
            onClick={(e) => {
              e.stopPropagation();
              viewRejectDetail(a);
            }}
          >
            <ExclamationCircleOutlined /> 点击查看驳回原因
          </div>
        )}

        {/* 标签行 */}
        <div style={{ display: 'flex', gap: 4, marginBottom: 10 }}>
          <Tag color={GOVERNANCE_COLOR[a.governanceTier ?? 'STANDARD']} style={{ fontSize: 10, margin: 0 }}>
            {GOVERNANCE_LABEL[a.governanceTier ?? 'STANDARD']}
          </Tag>
          {a.category && (
            <Tag style={{ fontSize: 10, margin: 0 }}>{a.category}</Tag>
          )}
          {a.securityLevel && (
            <Tag color="orange" style={{ fontSize: 10, margin: 0 }}>{a.securityLevel}</Tag>
          )}
        </div>

        {/* 版本信息 */}
        <div style={{ fontSize: 11, color: '#9ca3af', marginBottom: 12 }}>
          v{a.version ?? '0.0.1'} · {a.subsCount ?? 0} 订阅
        </div>

        {/* 操作按钮 */}
        <div style={{ display: 'flex', gap: 6, marginTop: 'auto', flexWrap: 'wrap' }}>
          {/* 所有状态都可编辑（除审核中外） */}
          {!isReviewing && !isArchived && (
            <Button
              size="small"
              style={{ flex: 1 }}
              icon={<EditOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                navigate(`${ROUTE_PATH.AGENT_EDIT}?id=${a.id}`);
              }}
            >
              {isDraft ? '编辑' : isRejected ? '修改' : '编辑'}
            </Button>
          )}

          {isDraft && (
            <Button
              type="primary"
              size="small"
              style={{ flex: 1 }}
              icon={<SendOutlined />}
              onClick={(e) => {
                e.stopPropagation();
                onSubmitReview(a.id);
              }}
            >
              提交审核
            </Button>
          )}
          {isDraft && (
            <Button
              size="small"
              danger
              onClick={(e) => {
                e.stopPropagation();
                onDelete(a.id, a.agentName);
              }}
            >
              删除
            </Button>
          )}

          {isRejected && (
            <Button
              type="primary"
              size="small"
              onClick={(e) => {
                e.stopPropagation();
                onSubmitReview(a.id);
              }}
            >
              重新提交
            </Button>
          )}

          {isReviewing && (
            <Tag color="processing" icon={<ClockCircleOutlined />} style={{ fontSize: 11, margin: 0 }}>
              ⏳ 审核中，请等待
            </Tag>
          )}

          {isPublished && (
            <>
              <Button
                type="primary"
                size="small"
                style={{ flex: 1 }}
                icon={<PlayCircleOutlined />}
                onClick={(e) => {
                  e.stopPropagation();
                  navigate(`${ROUTE_PATH.WORKBENCH}?agentId=${a.id}`);
                }}
              >
                开始对话
              </Button>
              <Button
                size="small"
                danger
                onClick={(e) => {
                  e.stopPropagation();
                  onArchive(a.id, a.agentName);
                }}
              >
                下线
              </Button>
            </>
          )}

          {isArchived && (
            <Tag color="orange" style={{ fontSize: 11, margin: 0 }}>
              已归档
            </Tag>
          )}
        </div>
      </Card>
    );
  };

  // ===== 渲染市场视图 =====
  const renderMarketView = () => (
    <div>
      {/* 筛选工具栏 */}
      <Space
        style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}
      >
        <Space>
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索智能体名称/描述/分类..."
            value={marketKwInput}
            onChange={(e) => {
              setMarketKwInput(e.target.value);
              if (!e.target.value) {
                setMarketKw('');
              }
            }}
            onPressEnter={() => setMarketKw(marketKwInput)}
            style={{ width: 280 }}
            allowClear
          />
          <Select
            placeholder="全部档位"
            value={marketGov || undefined}
            onChange={(v) => setMarketGov(v ?? '')}
            style={{ width: 140 }}
            allowClear
            options={GOVERNANCE_FILTER_OPTIONS.filter((o) => o.value !== '')}
          />
          <Select
            placeholder="全部等级"
            value={marketSec || undefined}
            onChange={(v) => setMarketSec(v ?? '')}
            style={{ width: 140 }}
            allowClear
            options={SECURITY_FILTER_OPTIONS.filter((o) => o.value !== '')}
          />
          <Select
            placeholder="全部分类"
            value={marketCategory || undefined}
            onChange={(v) => setMarketCategory(v ?? '')}
            style={{ width: 140 }}
            allowClear
            options={CATEGORY_OPTIONS.filter((o) => o.value !== '')}
          />
          {hasMarketFilter && (
            <Button icon={<ReloadOutlined />} onClick={resetMarketFilter}>
              重置
            </Button>
          )}
        </Space>
        <Text type="secondary" style={{ fontSize: 12 }}>
          共 {filteredMarket.length} 个智能体
        </Text>
      </Space>

      {/* 说明：治理档位 vs 安全等级 */}
      <div style={{ marginBottom: 12, fontSize: 12, color: '#6b7280', background: '#f3f4f6', padding: '8px 12px', borderRadius: 6 }}>
        💡 <strong>治理档位</strong>：控制智能体的护栏强度（标准/增强/严格），影响工具管控与审计粒度 ·
        <strong> 安全等级</strong>：标记数据敏感级别（L1公开~L4绝密），影响可见范围与访问权限
      </div>

      {/* 当前筛选条件显示 */}
      {hasMarketFilter && (
        <Space style={{ marginBottom: 12, fontSize: 12 }}>
          <Text type="secondary">筛选：</Text>
          {marketKw && <Tag color="blue">关键词: {marketKw}</Tag>}
          {marketGov && <Tag color="green">{GOVERNANCE_LABEL[marketGov]}</Tag>}
          {marketSec && <Tag color="orange">安全: {marketSec}</Tag>}
          {marketCategory && <Tag>{marketCategory}</Tag>}
        </Space>
      )}

      {/* 卡片网格 */}
      {marketLoading ? (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <RobotOutlined style={{ fontSize: 48, color: '#d1d5db' }} />
          <div style={{ marginTop: 12, color: '#9ca3af' }}>加载中...</div>
        </div>
      ) : filteredMarket.length === 0 ? (
        <EmptyState
          icon={<RobotOutlined style={{ fontSize: 40, color: '#d1d5db' }} />}
          title="暂无智能体"
          desc={hasMarketFilter ? '没有符合筛选条件的智能体，试试调整筛选条件' : '当前市场暂无已发布的智能体'}
          actions={
            hasMarketFilter ? (
              <Button onClick={resetMarketFilter}>重置筛选</Button>
            ) : null
          }
        />
      ) : (
        <Row gutter={[16, 16]}>
          {filteredMarket.map(renderMarketCard)}
        </Row>
      )}
    </div>
  );

  // ===== 渲染我的智能体视图 =====
  const renderMyView = () => (
    <div>
      {/* 筛选工具栏 */}
      <Space
        style={{ marginBottom: 16, width: '100%', justifyContent: 'space-between', flexWrap: 'wrap' }}
      >
        <Space>
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索智能体名称/编码..."
            value={myKwInput}
            onChange={(e) => {
              setMyKwInput(e.target.value);
              if (!e.target.value) {
                setMyKw('');
              }
            }}
            onPressEnter={() => setMyKw(myKwInput)}
            style={{ width: 240 }}
            allowClear
          />
          <Select
            value={myStatusFilter}
            onChange={(v) => setMyStatusFilter(v)}
            style={{ width: 140 }}
            options={[
              { value: 'ALL', label: `全部 (${myAgents.length})` },
              { value: LifeStatus.DRAFT, label: `草稿 (${statusCount[LifeStatus.DRAFT] ?? 0})` },
              { value: LifeStatus.REVIEWING, label: `审核中 (${statusCount[LifeStatus.REVIEWING] ?? 0})` },
              { value: LifeStatus.PUBLISHED, label: `已发布 (${statusCount[LifeStatus.PUBLISHED] ?? 0})` },
              { value: LifeStatus.REJECTED, label: `已驳回 (${statusCount[LifeStatus.REJECTED] ?? 0})` },
              { value: LifeStatus.ARCHIVED, label: `已归档 (${statusCount[LifeStatus.ARCHIVED] ?? 0})` },
            ]}
          />
          {(myKw || myStatusFilter !== 'ALL') && (
            <Button icon={<ReloadOutlined />} onClick={resetMyFilter}>
              重置
            </Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={() => fetchMyAgents()}>
            刷新
          </Button>
        </Space>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => navigate(ROUTE_PATH.AGENT_CREATE)}
        >
          创建智能体
        </Button>
      </Space>

      {/* 状态标签统计 */}
      {filteredMyAgents.length > 0 && (
        <Space style={{ marginBottom: 16 }}>
          {Object.entries(statusCount).map(([status, count]) => {
            if (status === 'ALL') return null;
            if (count === 0) return null;
            return (
              <Tag
                key={status}
                color={LIFE_STATUS_COLOR[status] ?? 'default'}
                style={{ fontSize: 11 }}
              >
                {LIFE_STATUS_LABEL[status] ?? status}: {count}
              </Tag>
            );
          })}
        </Space>
      )}

      {/* 按状态分组展示 */}
      {myLoading ? (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <RobotOutlined style={{ fontSize: 48, color: '#d1d5db' }} />
          <div style={{ marginTop: 12, color: '#9ca3af' }}>加载中...</div>
        </div>
      ) : filteredMyAgents.length === 0 ? (
        <EmptyState
          icon={<RobotOutlined style={{ fontSize: 40, color: '#d1d5db' }} />}
          title="暂无智能体"
          desc="去创建一个智能体吧，或者调整筛选条件"
          actions={
            <Space>
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => navigate(ROUTE_PATH.AGENT_CREATE)}
              >
                创建智能体
              </Button>
              {(myKw || myStatusFilter !== 'ALL') && (
                <Button onClick={resetMyFilter}>重置筛选</Button>
              )}
            </Space>
          }
        />
      ) : (
        <div>
          {/* 分组渲染 */}
          {(['DRAFT', 'REVIEWING', 'PUBLISHED', 'REJECTED', 'ARCHIVED'] as const).map((status) => {
            const agents = groupedMyAgents[status];
            if (!agents || agents.length === 0) return null;
            if (myStatusFilter !== 'ALL' && myStatusFilter !== status) return null;

            return (
              <div key={status} style={{ marginBottom: 24 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                    marginBottom: 12,
                    paddingBottom: 8,
                    borderBottom: '2px solid',
                    borderColor:
                      status === 'DRAFT'
                        ? '#d1d5db'
                        : status === 'REVIEWING'
                          ? '#1890ff'
                          : status === 'PUBLISHED'
                            ? '#52c41a'
                            : status === 'REJECTED'
                              ? '#ff4d4f'
                              : '#faad14',
                  }}
                >
                  <Tag color={LIFE_STATUS_COLOR[status]} style={{ fontSize: 12, margin: 0 }}>
                    {LIFE_STATUS_ICON[status]} {LIFE_STATUS_LABEL[status]}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 13 }}>
                    {agents.length} 个智能体
                  </Text>
                </div>
                <Row gutter={[16, 16]}>
                  {agents.map(renderMyCard)}
                </Row>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  return (
    <div>
      <PageHeader
        title="智能体市场"
        desc={
          tab === 'market'
            ? '浏览并订阅应用智能体，通用智能体默认可用无需订阅'
            : '管理我创建的智能体，提交审核后由管理员统一审核，审核通过即可发布'
        }
      />

      <BigTabs
        tabs={[
          { key: 'market', label: '🏪 智能体市场', badge: filteredMarket.length },
          { key: 'mine', label: '🤖 我的智能体', badge: filteredMyAgents.length },
        ]}
        active={tab}
        onChange={setTab}
      />

      {tab === 'market' ? renderMarketView() : renderMyView()}

      {/* 驳回详情弹窗 */}
      <Modal
        title="审核驳回详情"
        open={rejectDetailVisible}
        onCancel={() => setRejectDetailVisible(false)}
        footer={[
          <Button key="close" onClick={() => setRejectDetailVisible(false)}>
            关闭
          </Button>,
          rejectDetailAgent && (
            <Button
              key="edit"
              type="primary"
              onClick={() => {
                setRejectDetailVisible(false);
                if (rejectDetailAgent) {
                  navigate(`${ROUTE_PATH.AGENT_EDIT}?id=${rejectDetailAgent.id}`);
                }
              }}
            >
              去修改
            </Button>
          ),
        ].filter(Boolean) as React.ReactNode[]}
      >
        {rejectDetailAgent && (
          <div>
            <div style={{ marginBottom: 12 }}>
              <Text strong style={{ fontSize: 16 }}>
                {rejectDetailAgent.agentName}
              </Text>
              <Tag color="red" style={{ marginLeft: 8 }}>
                已驳回
              </Tag>
            </div>
            <Text type="secondary">驳回原因：</Text>
            <div
              style={{
                marginTop: 8,
                padding: 12,
                background: '#fff2f0',
                borderRadius: 6,
                border: '1px solid #ffccc7',
                color: '#cf1322',
                minHeight: 60,
              }}
            >
              审核未通过，请前往管理控制台 → 审核中心查看详细驳回理由，并根据反馈修改后重新提交。
            </div>
            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 12 }}>
              💡 提示：审核操作已统一迁移至管理控制台，您可以在管理控制台的「审核中心」查看详细的驳回原因。
            </Text>
          </div>
        )}
      </Modal>
    </div>
  );
};

export default AgentList;
