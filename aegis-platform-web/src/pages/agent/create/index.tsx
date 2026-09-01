/**
 * @file 智能体创建
 * @description 对齐产品方案：以「治理档位（governanceTier）」单一判别器取代原先分散的
 *  安全级别 / 护栏级别 / 规划模式，表单字段做减法。
 *  字段对齐：
 *  1. 智能体名称 *
 *  2. 分类 *
 *  3. 治理档位（STANDARD / ENHANCED / STRICT）
 *  4. 图标选择器（emoji 网格）
 *  5. 一句话描述
 *  6. 详细说明
 *  7. 主题色
 *  系统智能体额外要求绑定「部署目标（沙箱池）」，审核通过后由平台自动开通独立 API。
 *  对接后端 {@code POST /api/admin/agent}，创建后为草稿态。
 * @author aegis
 * @since 2.0.0
 */
import React, { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Empty,
  Form,
  Input,
  InputNumber,
  Radio,
  Row,
  Select,
  Space,
  Spin,
  Steps,
  Tag,
  Typography,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import { ModelTierSelector } from '@/components/business/ModelTierSelector';
import ApiConfigForm from '@/components/api/ApiConfigForm';
import { createAgent } from '@/api/agent';
import { skillApi, knowledgeApi, mcpApi, extractList } from '@/api/resource';
import { ROUTE_PATH } from '@/utils/constants';
import { AgentType, ModelTier, GovernanceTier } from '@/types/enum';
import type { AgentSaveParams, AgentBindingRequest } from '@/types/agent';
import type { AgentApiConfigParams } from '@/types/agentApi';
import type { Skill, KnowledgeBase, McpServer } from '@/types/resource';

const { Title, Text } = Typography;

const STEPS = [
  { title: '基本信息' },
  { title: '模型配置' },
  { title: '资源绑定' },
  { title: '确认' },
];

/** 治理档位选项（取代原安全级别 / 护栏级别 / 规划模式 三开关） */
const GOVERNANCE_OPTIONS = [
  { value: GovernanceTier.STANDARD, label: '标准档', desc: '常规沙箱与工具管控，适合绝大多数通用场景' },
  { value: GovernanceTier.ENHANCED, label: '增强档', desc: '加强内容过滤与工具审批，适合敏感业务' },
  { value: GovernanceTier.STRICT, label: '严格档', desc: '隔离沙箱、强管控、强制人审与全量审计' },
];

/** 智能体类型选项（通用智能体由系统预置，用户可创建应用智能体与系统智能体） */
const AGENT_TYPE_OPTIONS = [
  { value: AgentType.APPLICATION, label: '应用智能体', desc: '用户创建，固定绑定资源，不可发布 API' },
  { value: AgentType.SYSTEM, label: '系统智能体', desc: '面向业务系统，绑定沙箱池常驻运行，审核通过后自动开通 API' },
];

/** 分类选项 */
const CATEGORY_OPTIONS = [
  { value: '问答型', label: '问答型' },
  { value: '执行型', label: '执行型' },
  { value: '分析型', label: '分析型' },
  { value: '创作型', label: '创作型' },
];

/** 图标选项 */
const ICON_OPTIONS = ['💰', '📊', '📈', '🧮', '💼', '📋', '🔢', '💡', '🤖', '📝', '🔍', '⚡', '🔧', '📚', '🎯', '✨'];

/** 主题色选项 */
const COLOR_OPTIONS = [
  { value: '#dbeafe', label: '蓝色' },
  { value: '#d1fae5', label: '绿色' },
  { value: '#fef3c7', label: '黄色' },
  { value: '#fce7f3', label: '粉色' },
  { value: '#ede9fe', label: '紫色' },
  { value: '#fed7aa', label: '橙色' },
];

const AgentCreate: React.FC = () => {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [current, setCurrent] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<AgentSaveParams>();

  // 可选资源列表（资源绑定步骤使用）
  const [skillList, setSkillList] = useState<Skill[]>([]);
  const [kbList, setKbList] = useState<KnowledgeBase[]>([]);
  const [mcpList, setMcpList] = useState<McpServer[]>([]);
  const [resourceLoading, setResourceLoading] = useState(false);
  // 选中的资源绑定（resourceType:resourceId 字符串 key）
  const [selectedBindings, setSelectedBindings] = useState<Set<string>>(new Set());

  /** 拉取可选资源列表（进入资源绑定步骤时懒加载） */
  const fetchResources = async () => {
    if (skillList.length > 0 || resourceLoading) return;
    setResourceLoading(true);
    try {
      const [skills, kbs, mcps] = await Promise.all([
        skillApi.list({ page: 1, size: 100 }),
        knowledgeApi.list({ page: 1, size: 100 }),
        mcpApi.listServices({ page: 1, size: 100 }),
      ]);
      setSkillList(extractList(skills));
      setKbList(extractList(kbs));
      setMcpList(extractList(mcps));
    } catch (err) {
      // 资源接口未就绪时不阻塞创建流程
      console.error('fetchResources error', err);
    } finally {
      setResourceLoading(false);
    }
  };

  useEffect(() => {
    if (current === 2) void fetchResources();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current]);

  /** 切换资源选中状态 */
  const toggleBinding = (resourceType: string, resourceId: string) => {
    const key = `${resourceType}:${resourceId}`;
    setSelectedBindings((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  /** 将选中的资源转为绑定请求列表 */
  const buildBindingRequests = (): AgentBindingRequest[] => {
    return Array.from(selectedBindings).map((key) => {
      const [resourceType, resourceIdStr] = key.split(':');
      return {
        resourceType,
        resourceId: resourceIdStr,
        resourceVersion: 'latest',
        bindingType: 'FIXED',
        enabled: true,
      };
    });
  };

  /** 下一步前校验当前步骤字段 */
  const next = async () => {
    try {
      const fields = current === 0 ? ['agentCode', 'agentName', 'agentType', 'category', 'governanceTier'] : [];
      if (fields.length > 0) {
        await form.validateFields(fields);
      }
      setCurrent((c) => c + 1);
    } catch {
      // 校验失败由 Form 提示
    }
  };

  const prev = () => setCurrent((c) => c - 1);

  /** 提交创建 */
  const onSubmit = async () => {
    try {
      const values = await form.validateFields();
      setSubmitting(true);
      // 合并选中的资源绑定
      const bindings = buildBindingRequests();
      // 系统智能体部署目标仅 SYSTEM 传递，APPLICATION 时不传
      const { deploymentPoolCode, reservedReplicas, apiConfig, ...rest } = values;
      const payload: AgentSaveParams = {
        ...rest,
        governanceTier: values.governanceTier ?? GovernanceTier.STANDARD,
        bindings: bindings.length > 0 ? bindings : undefined,
      };
      if (values.agentType === AgentType.SYSTEM) {
        payload.deploymentPoolCode = deploymentPoolCode;
        payload.reservedReplicas = reservedReplicas ?? 1;
        (payload as AgentSaveParams & { apiConfig?: AgentApiConfigParams }).apiConfig = apiConfig;
      }
      const id = await createAgent(payload);
      message.success(`智能体创建成功（ID: ${id}），当前为草稿态，可提交审核发布`);
      navigate(ROUTE_PATH.AGENT_LIST, { replace: true });
    } catch (err) {
      // 校验失败或接口错误，axios 拦截器已弹错
      console.error(err);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ height: '100%', overflowY: 'auto', paddingRight: 4 }}>
      <Title level={4}>创建智能体</Title>
      <Card>
        <Steps current={current} items={STEPS} style={{ marginBottom: 32 }} />

        <Form<AgentSaveParams>
          form={form}
          layout="vertical"
          initialValues={{
            agentType: AgentType.APPLICATION,
            governanceTier: GovernanceTier.STANDARD,
            modelTier: ModelTier.STANDARD,
            temperature: 0.7,
            maxTurns: 20,
            memoryStrategy: 'SESSION_LEVEL',
            icon: '🤖',
            color: '#dbeafe',
            category: '问答型',
            reservedReplicas: 1,
          }}
          style={{ maxWidth: 720 }}
        >
          {/* 步骤 1：基本信息 */}
          <div hidden={current !== 0}>
            <Row gutter={16}>
              <Col span={12}>
                <Form.Item
                  name="agentName"
                  label="智能体名称"
                  rules={[{ required: true, message: '请输入智能体名称' }]}
                >
                  <Input placeholder="如 客服助手" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="agentCode"
                  label="智能体编码"
                  rules={[
                    { required: true, message: '请输入智能体编码' },
                    {
                      pattern: /^[A-Z][A-Z0-9_]{2,31}$/,
                      message: '大写字母开头，仅含大写字母/数字/下划线，3-32 字符',
                    },
                  ]}
                  tooltip="租户内唯一，创建后不可修改"
                >
                  <Input placeholder="如 CUSTOMER_SERVICE" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="category"
                  label="分类"
                  rules={[{ required: true, message: '请选择分类' }]}
                >
                  <Select options={CATEGORY_OPTIONS} placeholder="请选择分类" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  name="agentType"
                  label="智能体类型"
                  rules={[{ required: true, message: '请选择类型' }]}
                >
                  <Select
                    options={AGENT_TYPE_OPTIONS}
                    optionRender={(option) => {
                      const desc = (option as { desc?: string }).desc;
                      return (
                        <div>
                          <div>{option.label}</div>
                          {desc && (
                            <div style={{ fontSize: 11, color: '#9ca3af' }}>{desc}</div>
                          )}
                        </div>
                      );
                    }}
                  />
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item name="icon" label="图标">
                  <Radio.Group>
                    {ICON_OPTIONS.map((ic) => (
                      <Radio.Button
                        key={ic}
                        value={ic}
                        style={{
                          width: 40,
                          height: 40,
                          display: 'inline-flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: 20,
                          padding: 0,
                          marginRight: 4,
                          marginBottom: 4,
                        }}
                      >
                        {ic}
                      </Radio.Button>
                    ))}
                  </Radio.Group>
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item name="color" label="主题色">
                  <Radio.Group>
                    {COLOR_OPTIONS.map((c) => (
                      <Radio.Button
                        key={c.value}
                        value={c.value}
                        style={{
                          background: c.value,
                          width: 32,
                          height: 32,
                          display: 'inline-flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          padding: 0,
                          borderColor: '#d9d9d9',
                        }}
                      />
                    ))}
                  </Radio.Group>
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item name="description" label="一句话描述">
                  <Input placeholder="简要介绍智能体能力（一句话）" />
                </Form.Item>
              </Col>
              <Col span={24}>
                <Form.Item
                  name="governanceTier"
                  label="治理档位"
                  rules={[{ required: true, message: '请选择治理档位' }]}
                  tooltip="统一驱动沙箱隔离、工具管控、内容过滤、人审与审计粒度，取代原安全级别 / 护栏级别 / 规划模式"
                >
                  <Radio.Group>
                    {GOVERNANCE_OPTIONS.map((g) => (
                      <Radio key={g.value} value={g.value} style={{ display: 'block', height: 32, lineHeight: '32px' }}>
                        <b>{g.label}</b> — {g.desc}
                      </Radio>
                    ))}
                  </Radio.Group>
                </Form.Item>
              </Col>
            </Row>
          </div>

          {/* 步骤 2：模型配置 */}
          <div hidden={current !== 1}>
            <Row gutter={16}>
              <Col span={24}>
                <Form.Item name="systemPrompt" label="系统提示词">
                  <Input.TextArea
                    rows={5}
                    placeholder="定义智能体的角色、能力边界与行为规范"
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="modelTier" label="模型档位">
                  <ModelTierSelector />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="memoryStrategy" label="记忆策略">
                  <Select
                    options={[
                      { value: 'SESSION_LEVEL', label: '会话级' },
                      { value: 'LONG_TERM', label: '长期记忆' },
                    ]}
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="temperature" label="温度（0-2）" tooltip="越高越发散，越低越确定">
                  <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="maxTurns" label="最大对话轮数">
                  <InputNumber min={1} max={100} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            {/* 系统智能体：API 发布配置 + 部署目标（仅 agentType=SYSTEM 时显示） */}
            <Form.Item shouldUpdate noStyle>
              {() => {
                const agentType = form.getFieldValue('agentType');
                if (agentType !== AgentType.SYSTEM) return null;
                const agentCode = form.getFieldValue('agentCode') as string | undefined;
                return (
                  <>
                    <ApiConfigForm form={form} agentCode={agentCode} agentName={form.getFieldValue('agentName')} />
                    <Card title="部署目标（沙箱池）" size="small" style={{ marginTop: 16 }}>
                      <Alert
                        message="系统智能体审核通过后，平台将根据您选择的治理档位自动匹配最合适的沙箱池，并自动开通独立 API 访问。"
                        type="info"
                        showIcon
                        style={{ marginBottom: 16 }}
                      />
                      <Row gutter={16}>
                        <Col span={12}>
                          <Form.Item
                            name="reservedReplicas"
                            label="预留副本数"
                            tooltip="最小常驻实例数，默认 1"
                          >
                            <InputNumber min={1} max={20} style={{ width: '100%' }} />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        📌 沙箱池将在审核通过后自动分配：
                        <br />
                        · 标准档 → 通用沙箱池（共享）
                        <br />
                        · 增强档 → 隔离沙箱池（独占）
                        <br />
                        · 严格档 → 物理隔离沙箱池（独占+加密）
                        <br />
                        · 若无匹配池，将自动降级至默认池
                      </Text>
                    </Card>
                  </>
                );
              }}
            </Form.Item>
          </div>

          {/* 步骤 3：资源绑定 */}
          <div hidden={current !== 2}>
            <Text type="secondary" style={{ display: 'block', marginBottom: 12, fontSize: 13 }}>
              可选步骤：为智能体绑定技能（SKILL）、知识库与 MCP 资源，调用时自动挂载。创建后也可在详情页追加绑定。
            </Text>
            <Spin spinning={resourceLoading}>
              <Row gutter={16}>
                <Col span={8}>
                  <Card
                    title={`🔧 技能 (${skillList.length})`}
                    size="small"
                    style={{ marginBottom: 12 }}
                  >
                    {skillList.length === 0 ? (
                      <Empty description="暂无可用技能" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    ) : (
                      skillList.map((s) => {
                        const key = `SKILL:${s.id}`;
                        return (
                          <ResourceCheckItem
                            key={key}
                            checked={selectedBindings.has(key)}
                            onToggle={() => s.id && toggleBinding('SKILL', s.id)}
                            name={s.skillName ?? `技能 #${s.id}`}
                            desc={s.description}
                          />
                        );
                      })
                    )}
                  </Card>
                </Col>
                <Col span={8}>
                  <Card
                    title={`📚 知识库 (${kbList.length})`}
                    size="small"
                    style={{ marginBottom: 12 }}
                  >
                    {kbList.length === 0 ? (
                      <Empty description="暂无可用知识库" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    ) : (
                      kbList.map((k) => {
                        const key = `KNOWLEDGE_BASE:${k.id}`;
                        return (
                          <ResourceCheckItem
                            key={key}
                            checked={selectedBindings.has(key)}
                            onToggle={() => k.id && toggleBinding('KNOWLEDGE_BASE', k.id)}
                            name={k.kbName ?? `知识库 #${k.id}`}
                            desc={k.description}
                          />
                        );
                      })
                    )}
                  </Card>
                </Col>
                <Col span={8}>
                  <Card
                    title={`🔌 MCP (${mcpList.length})`}
                    size="small"
                    style={{ marginBottom: 12 }}
                  >
                    {mcpList.length === 0 ? (
                      <Empty description="暂无可用 MCP" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                    ) : (
                      mcpList.map((m) => {
                        const key = `MCP_SERVICE:${m.id}`;
                        return (
                          <ResourceCheckItem
                            key={key}
                            checked={selectedBindings.has(key)}
                            onToggle={() => toggleBinding('MCP_SERVICE', m.id)}
                            name={m.mcpName ?? `MCP #${m.id}`}
                            desc={m.provider ?? m.mcpCode}
                          />
                        );
                      })
                    )}
                  </Card>
                </Col>
              </Row>
            </Spin>
            <div style={{ marginTop: 8 }}>
              <Tag color="blue">已选 {selectedBindings.size} 项资源</Tag>
            </div>
          </div>

          {/* 步骤 4：确认 */}
          <div hidden={current !== 3}>
            <Form.Item shouldUpdate noStyle>
              {() => {
                const v = form.getFieldsValue();
                const govLabel =
                  v.governanceTier === GovernanceTier.STRICT
                    ? '严格档'
                    : v.governanceTier === GovernanceTier.ENHANCED
                      ? '增强档'
                      : '标准档';
                return (
                  <div>
                    <Text strong>请确认以下信息无误后提交，创建后为草稿态，可在列表页提交审核：</Text>
                    <Card type="inner" title="基本信息" style={{ marginTop: 16 }}>
                      <p>名称：{v.agentName || '-'}</p>
                      <p>编码：{v.agentCode || '-'}</p>
                      <p>图标：{v.icon || '🤖'} 　主题色：{v.color || '-'}</p>
                      <p>分类：{v.category || '-'}</p>
                      <p>
                        类型：
                        {v.agentType === AgentType.UNIVERSAL
                          ? '通用'
                          : v.agentType === AgentType.SYSTEM
                            ? '系统'
                            : '应用'}
                        　|　描述：{v.description || '-'}
                      </p>
                      <p>治理档位：{govLabel}</p>
                    </Card>
                    <Card type="inner" title="模型配置" style={{ marginTop: 16 }}>
                      <p>模型档位：{v.modelTier}</p>
                      <p>温度：{v.temperature}</p>
                      <p>最大对话轮数：{v.maxTurns}</p>
                      <p>记忆策略：{v.memoryStrategy}</p>
                      <p>系统提示词：{v.systemPrompt ? `${v.systemPrompt.slice(0, 60)}...` : '-'}</p>
                    </Card>
                    {v.agentType === AgentType.SYSTEM && (
                      <Card type="inner" title="部署目标（沙箱池）" style={{ marginTop: 16 }}>
                        <p>预留副本数：{v.reservedReplicas ?? 1}</p>
                        <p>沙箱池：审核通过后自动匹配（基于治理档位）</p>
                      </Card>
                    )}
                    <Card type="inner" title={`资源绑定（${selectedBindings.size} 项）`} style={{ marginTop: 16 }}>
                      {selectedBindings.size === 0 ? (
                        <Text type="secondary">未绑定资源（创建后可在详情页追加）</Text>
                      ) : (
                        <Space size={[6, 6]} wrap>
                          {Array.from(selectedBindings).map((key) => {
                            const [rtype, rid] = key.split(':');
                            return (
                              <Tag color="blue" key={key}>
                                {rtype} #{rid}
                              </Tag>
                            );
                          })}
                        </Space>
                      )}
                    </Card>
                  </div>
                );
              }}
            </Form.Item>
          </div>
        </Form>

        <Space style={{ marginTop: 24 }}>
          <Button disabled={current === 0} onClick={prev}>
            上一步
          </Button>
          {current < STEPS.length - 1 ? (
            <Button type="primary" onClick={next}>
              下一步
            </Button>
          ) : (
            <Button type="primary" loading={submitting} onClick={onSubmit}>
              提交创建
            </Button>
          )}
          <Button onClick={() => navigate(ROUTE_PATH.AGENT_LIST)}>取消</Button>
        </Space>
      </Card>
    </div>
  );
};

export default AgentCreate;

/** 资源选择项（勾选绑定） */
const ResourceCheckItem: React.FC<{
  checked: boolean;
  onToggle: () => void;
  name: string;
  desc?: string;
}> = ({ checked, onToggle, name, desc }) => (
  <div
    onClick={onToggle}
    style={{
      display: 'flex',
      alignItems: 'flex-start',
      gap: 8,
      padding: '8px 10px',
      marginBottom: 6,
      borderRadius: 6,
      cursor: 'pointer',
      background: checked ? '#eff6ff' : '#f9fafb',
      border: `1px solid ${checked ? '#bfdbfe' : '#f0f0f0'}`,
      transition: 'all 0.2s',
    }}
  >
    <Checkbox checked={checked} style={{ marginTop: 2 }} />
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ fontSize: 13, fontWeight: 600, color: checked ? '#1e40af' : '#374151' }}>
        {name}
      </div>
      {desc && (
        <div
          style={{
            fontSize: 11,
            color: '#9ca3af',
            marginTop: 2,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {desc}
        </div>
      )}
    </div>
  </div>
);
