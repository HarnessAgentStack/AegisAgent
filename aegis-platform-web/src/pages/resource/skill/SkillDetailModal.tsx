/**
 * @file 技能详情弹窗（增强版）
 * @description 多Tab结构化展示SKILL完整产物：概览、指令/方法论、绑定工具、版本历史。
 *              支持编辑权限判断，有权限者可跳转工作台编辑SKILL。
 * @author aegis
 * @since 2.0.0
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Avatar,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Empty,
  List,
  Modal,
  Row,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import {
  ApiOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  EditOutlined,
  EyeOutlined,
  HistoryOutlined,
  InfoCircleOutlined,
  StarFilled,
  ThunderboltOutlined,
  ToolOutlined,
  UserOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate } from 'react-router-dom';
import { SecurityLevelTag } from '@/components/common/SecurityLevelTag';
import { LifeStatusTag } from '@/components/common/LifeStatusTag';
import { LifeStatus } from '@/types/enum';
import { useAuthStore } from '@/stores/authStore';
import { ROUTE_PATH } from '@/utils/constants';
import type { Skill, SkillVersion } from '@/types/resource';
import type { SkillVersionDiff } from '@/types/resource';
import { skillApi } from '@/api/resource';
import {
  ICON,
  ICON_BG,
  SECURITY_TAG,
  SKILL_TYPE_TAG,
  parseJsonArray,
} from './constants';
import { safeJsonParse } from '@/utils/number';

const { Text, Title, Paragraph } = Typography;

// ============================================================================
// 类型定义
// ============================================================================

interface SkillDetailModalProps {
  visible: boolean;
  record: Skill | null;
  onCancel: () => void;
  /** 点击编辑回调（由父组件决定跳转逻辑） */
  onEdit?: (record: Skill) => void;
  /** 订阅状态变化 */
  onSubscribedChange?: (skillId: string, subscribed: boolean) => void;
}

interface SkillBinding {
  mcpId?: number;
  mcpName: string;
  tools: BoundTool[];
}

interface BoundTool {
  toolCode: string;
  toolName?: string;
  signature?: string;
  description?: string;
  selected: boolean;
}

// ============================================================================
// 权限检查
// ============================================================================

function checkEditPermission(skill: Skill, currentUserId: string | null): boolean {
  if (!currentUserId || !skill.id) return false;
  // 创建者本人可编辑
  if (skill.authorUserId === currentUserId) return true;
  // 管理员可编辑（通过角色判断）
  const roles = useAuthStore.getState().user?.roles ?? [];
  if (roles.includes('ROLE_SKILL_ADMIN') || roles.includes('ROLE_ADMIN')) return true;
  return false;
}

// ============================================================================
// 数据解析工具
// ============================================================================

/** 解析绑定工具 JSON → 按 MCP 分组的结构 */
function parseBindingTools(bindingToolsStr?: string): SkillBinding[] {
  if (!bindingToolsStr) return [];
  const data = safeJsonParse<unknown>(bindingToolsStr);
  if (data != null && typeof data === 'object' && !Array.isArray(data)) {
    // 格式1: { mcpName: [toolCode, ...], ... }
    const obj = data as Record<string, unknown[]>;
    return Object.entries(obj).map(([mcpName, tools]) => ({
      mcpName,
      tools: Array.isArray(tools)
        ? tools.map((t: unknown) => {
            const tool = t as string | Record<string, unknown>;
            return {
              toolCode: typeof tool === 'string' ? tool : String((tool as Record<string, unknown>).toolCode ?? tool),
              toolName: typeof tool === 'string' ? undefined : String((tool as Record<string, unknown>).toolName ?? ''),
              signature: typeof tool === 'string' ? undefined : String((tool as Record<string, unknown>).signature ?? ''),
              description: typeof tool === 'string' ? undefined : String((tool as Record<string, unknown>).description ?? ''),
              selected: true,
            };
          })
        : [],
    }));
  }
  if (Array.isArray(data)) {
    // 格式2: 扁平数组 — 兼容纯字符串（旧版）和后端 enrich 后的对象（新版）
    const tools: BoundTool[] = (data as unknown[]).map((raw) => {
      if (typeof raw === 'string') {
        return { toolCode: raw, selected: true } as BoundTool;
      }
      if (raw && typeof raw === 'object') {
        const obj = raw as Record<string, unknown>;
        return {
          toolCode: String(obj.toolCode ?? obj.tool_code ?? ''),
          toolName: String(obj.toolName ?? obj.tool_name ?? ''),
          signature: String(obj.signature ?? obj.functionSignature ?? ''),
          description: String(obj.description ?? ''),
          selected: true,
        } as BoundTool;
      }
      return { toolCode: String(raw), selected: true } as BoundTool;
    });
    return [{ mcpName: '默认', tools }];
  }
  return [];
}

// ============================================================================
// 子组件：概览 Tab
// ============================================================================

const SkillOverviewTab: React.FC<{ skill: Skill; stats?: { calls?: number; successRate?: number; latency?: number } }> = ({
  skill,
  stats,
}) => {
  const tags = parseJsonArray(skill.tags);
  const icon = ICON[skill.skillType === 'COMPOSITE' ? 'COMPOSITE' : 'ATOMIC'] || '🛠️';
  const iconBg = ICON_BG[skill.skillType === 'COMPOSITE' ? 'COMPOSITE' : 'ATOMIC'] || '#e6f4ff';

  return (
    <div>
      {/* 顶部卡片：名称+状态 */}
      <Card style={{ marginBottom: 16 }} bodyStyle={{ padding: 20 }}>
        <Space align="start" size={16} style={{ width: '100%' }}>
          <Avatar
            size={64}
            style={{ backgroundColor: iconBg, fontSize: 28 }}
            icon={<span>{icon}</span>}
          />
          <div style={{ flex: 1 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <Title level={4} style={{ margin: 0 }}>{skill.skillName}</Title>
              <Tag color={SECURITY_TAG[skill.securityLevel]?.color ?? 'default'}>
                {SECURITY_TAG[skill.securityLevel]?.text ?? '未知'}
              </Tag>
              <Tag color={SKILL_TYPE_TAG[skill.skillType]?.color}>
                {SKILL_TYPE_TAG[skill.skillType]?.text}
              </Tag>
              {skill.lifeStatus && <LifeStatusTag status={skill.lifeStatus} />}
            </div>
            <Text type="secondary" style={{ fontSize: 13 }}>
              编码：<Text code>{skill.skillCode}</Text>
              {skill.version && <>  ·  版本：v{skill.version}</>}
              {skill.category && <>  ·  分类：{skill.category}</>}
            </Text>
            {skill.description && (
              <Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }} ellipsis={{ rows: 3, expandable: true }}>
                {skill.description}
              </Paragraph>
            )}
          </div>
        </Space>
      </Card>

      {/* 使用统计 */}
      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <StatItem
              icon={<ThunderboltOutlined style={{ color: '#1677ff' }} />}
              label="调用次数"
              value={stats?.calls ?? skill.subsCount ?? 0}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <StatItem
              icon={<CheckCircleOutlined style={{ color: '#52c41a' }} />}
              label="成功率"
              value={stats?.successRate != null ? `${stats.successRate}%` : '-'}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <StatItem
              icon={<ClockCircleOutlined style={{ color: '#faad14' }} />}
              label="平均耗时"
              value={stats?.latency != null ? `${stats.latency}ms` : '-'}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card size="small" style={{ textAlign: 'center' }}>
            <StatItem
              icon={<StarFilled style={{ color: '#eb2f96' }} />}
              label="订阅数"
              value={skill.subsCount ?? 0}
            />
          </Card>
        </Col>
      </Row>

      {/* 详细描述 */}
      <Descriptions column={2} size="small" bordered style={{ marginBottom: 12 }}>
        <Descriptions.Item label="SKILL 编码" span={1}>
          <Text code>{skill.skillCode}</Text>
        </Descriptions.Item>
        <Descriptions.Item label="版本" span={1}>
          {skill.version ? `v${skill.version}` : '-'}
        </Descriptions.Item>
        <Descriptions.Item label="安全等级">
          <SecurityLevelTag level={skill.securityLevel} />
        </Descriptions.Item>
        <Descriptions.Item label="可见范围">
          {skill.visibility === 'PUBLIC' ? '公开' : skill.visibility === 'TENANT' ? '租户内' : skill.visibility ?? '-'}
        </Descriptions.Item>
        {skill.scope && (
          <Descriptions.Item label="作用域">
            <Tag color={skill.scope === 'GLOBAL' ? 'purple' : 'blue'}>
              {skill.scope === 'GLOBAL' ? '全局' : '局部'}
            </Tag>
          </Descriptions.Item>
        )}
        {skill.isSystem && (
          <Descriptions.Item label="系统技能">是</Descriptions.Item>
        )}
        {skill.authorUserId && (
          <Descriptions.Item label="作者">
            <Space>
              <Avatar size="small" icon={<UserOutlined />} />
              ID: {skill.authorUserId}
            </Space>
          </Descriptions.Item>
        )}
        {skill.createdAt && (
          <Descriptions.Item label="创建时间">
            {skill.createdAt}
          </Descriptions.Item>
        )}
      </Descriptions>

      {/* 标签 */}
      {tags.length > 0 && (
        <Card size="small" title="标签" style={{ marginBottom: 12 }}>
          <Space size={[8, 8]} wrap>
            {tags.map((t) => (
              <Tag key={t} color="blue">{t}</Tag>
            ))}
          </Space>
        </Card>
      )}

      {/* 输入/输出定义 */}
      <Row gutter={[12, 12]}>
        <Col span={12}>
          <Card size="small" title="📥 输入参数" extra={<Text type="secondary" style={{ fontSize: 12 }}>Schema 定义</Text>}>
            <SchemaViewer schemaStr={skill.inputs} emptyText="暂无输入参数" />
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small" title="📤 输出参数" extra={<Text type="secondary" style={{ fontSize: 12 }}>Schema 定义</Text>}>
            <SchemaViewer schemaStr={skill.outputs} emptyText="暂无输出参数" />
          </Card>
        </Col>
      </Row>
    </div>
  );
};

// ============================================================================
// 子组件：统计项
// ============================================================================

const StatItem: React.FC<{ icon: React.ReactNode; label: string; value: React.ReactNode }> = ({ icon, label, value }) => (
  <div>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 4, marginBottom: 4 }}>
      {icon}
      <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
    </div>
    <div style={{ fontSize: 24, fontWeight: 600 }}>{value}</div>
  </div>
);

// ============================================================================
// 子组件：Schema 查看器
// ============================================================================

const SchemaViewer: React.FC<{ schemaStr?: string; emptyText: string }> = ({ schemaStr, emptyText }) => {
  if (!schemaStr) return <Empty description={emptyText} image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  const schema = safeJsonParse<{ properties?: Record<string, { type?: string; description?: string }>; required?: string[] }>(schemaStr);
  if (!schema) return <Empty description={emptyText} image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  const properties = schema.properties ?? {};
  const required = schema.required ?? [];
  const keys = Object.keys(properties);
    if (keys.length === 0) return <Empty description={emptyText} image={Empty.PRESENTED_IMAGE_SIMPLE} />;
    return (
      <List
        size="small"
        dataSource={keys}
        renderItem={(key) => {
          const prop = properties[key];
          const isRequired = required.includes(key);
          return (
            <List.Item>
              <Space>
                <Text code>{key}</Text>
                <Tag color="blue">{prop.type ?? 'string'}</Tag>
                {isRequired && <Tag color="orange">必填</Tag>}
                {prop.description && <Text type="secondary" style={{ fontSize: 12 }}>{prop.description}</Text>}
              </Space>
            </List.Item>
          );
        }}
      />
    );
  };

// ============================================================================
// 子组件：指令/方法论 Tab
// ============================================================================

const SkillInstructionsTab: React.FC<{ skill: Skill }> = ({ skill }) => {
  const [showLineNumbers, setShowLineNumbers] = useState(true);

  if (!skill.instructions) {
    return (
      <Empty
        description={
          <div>
            <Text strong>暂无指令/方法论</Text>
            <div>该 SKILL 尚未配置 Prompt 指令，编辑后即可在此展示。</div>
          </div>
        }
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  const lines = skill.instructions.split('\n');

  return (
    <div>
      <Alert
        type="info"
        showIcon
        icon={<InfoCircleOutlined />}
        message="指令/方法论说明"
        description="这是 SKILL 的核心 Prompt，定义了 AI 的行为逻辑、工作流程和决策规则。修改后 SKILL 的行为将随之变化。"
        style={{ marginBottom: 12 }}
      />

      <Card
        size="small"
        title={
          <Space>
            <CodeOutlined />
            <span>Prompt 指令（只读）</span>
            <Tag color="blue">{lines.length} 行</Tag>
          </Space>
        }
        extra={
          <Button size="small" onClick={() => setShowLineNumbers((v) => !v)}>
            {showLineNumbers ? '隐藏行号' : '显示行号'}
          </Button>
        }
        bodyStyle={{ padding: 0 }}
      >
        <div
          style={{
            background: '#1e1e1e',
            color: '#d4d4d4',
            padding: '12px 16px',
            fontFamily: 'Consolas, "Courier New", monospace',
            fontSize: 13,
            lineHeight: '22px',
            maxHeight: 500,
            overflow: 'auto',
            borderRadius: '0 0 4px 4px',
          }}
        >
          {lines.map((line, idx) => (
            <div key={idx} style={{ display: 'flex' }}>
              {showLineNumbers && (
                <span
                  style={{
                    color: '#858585',
                    textAlign: 'right',
                    width: 40,
                    marginRight: 16,
                    userSelect: 'none',
                    flexShrink: 0,
                  }}
                >
                  {idx + 1}
                </span>
              )}
              <span style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {colorizeLine(line)}
              </span>
            </div>
          ))}
        </div>
      </Card>

      {/* 变量识别 */}
      <Card
        size="small"
        title={
          <Space>
            <ToolOutlined />
            <span>变量识别</span>
          </Space>
        }
        style={{ marginTop: 12 }}
      >
        <VariableDetector instructions={skill.instructions} />
      </Card>
    </div>
  );
};

/** 简单的语法高亮（关键字/字符串/注释） */
function colorizeLine(line: string): React.ReactNode {
  if (/^\s*#|^\s*\/\//.test(line)) {
    return <span style={{ color: '#6a9955' }}>{line}</span>;
  }
  // Markdown 标题
  if (/^\s*#{1,6}\s/.test(line)) {
    return <span style={{ color: '#569cd6', fontWeight: 600 }}>{line}</span>;
  }
  // 列表项
  if (/^\s*[-*]\s/.test(line)) {
    return <span style={{ color: '#dcdcaa' }}>{line}</span>;
  }
  // JSON key-value
  const jsonMatch = line.match(/^(\s*)"([^"]+)"(\s*:\s*)"(.*)"$/);
  if (jsonMatch) {
    return (
      <span>
        <span style={{ color: '#808080' }}>{jsonMatch[1]}</span>
        <span style={{ color: '#9cdcfe' }}>"{jsonMatch[2]}"</span>
        <span style={{ color: '#ce9178' }}>{jsonMatch[3]}"{jsonMatch[4]}"</span>
      </span>
    );
  }
  return line;
}

/** 变量检测器 */
const VariableDetector: React.FC<{ instructions: string }> = ({ instructions }) => {
  const variables = useMemo(() => {
    const regex = /\{(\w+)\}/g;
    const found = new Set<string>();
    let match;
    while ((match = regex.exec(instructions)) !== null) {
      found.add(match[1]);
    }
    return Array.from(found);
  }, [instructions]);

  if (variables.length === 0) {
    return <Text type="secondary">未检测到变量。使用 <Text code>{'{variable}'}</Text> 语法定义变量。</Text>;
  }

  return (
    <Space size={[8, 8]} wrap>
      {variables.map((v) => (
        <Tag key={v} color="processing">
          {'{'}{v}{'}'}
        </Tag>
      ))}
    </Space>
  );
};

// ============================================================================
// 子组件：绑定工具 Tab
// ============================================================================

const SkillToolsTab: React.FC<{ skill: Skill }> = ({ skill }) => {
  const bindings = useMemo(() => parseBindingTools(skill.bindingTools), [skill.bindingTools]);

  if (bindings.length === 0) {
    return (
      <Empty
        description={
          <div>
            <Text strong>未绑定任何工具</Text>
            <div>SKILL 绑定的工具将在此分组展示，包括 MCP 来源和每个工具的函数签名。</div>
          </div>
        }
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  }

  return (
    <div>
      <Alert
        type="info"
        showIcon
        icon={<ApiOutlined />}
        message={`共 ${bindings.length} 个 MCP 服务，${bindings.reduce((sum, b) => sum + b.tools.length, 0)} 个工具`}
        description="点击 MCP 展开查看具体绑定的工具列表和函数签名"
        style={{ marginBottom: 12 }}
      />

      {bindings.map((binding) => (
        <Card
          key={binding.mcpName}
          size="small"
          title={
            <Space>
              <ApiOutlined style={{ color: '#1677ff' }} />
              <span>{binding.mcpName}</span>
              <Badge count={binding.tools.length} style={{ backgroundColor: '#1677ff' }} />
            </Space>
          }
          style={{ marginBottom: 12 }}
        >
          <ToolTable tools={binding.tools} />
        </Card>
      ))}
    </div>
  );
};

const ToolTable: React.FC<{ tools: BoundTool[] }> = ({ tools }) => {
  const columns: ColumnsType<BoundTool> = [
    {
      title: '工具编码',
      dataIndex: 'toolCode',
      width: 200,
      render: (code: string) => <Text code>{code}</Text>,
    },
    {
      title: '工具名称',
      dataIndex: 'toolName',
      width: 160,
      render: (name?: string) => name || <Text type="secondary">-</Text>,
    },
    {
      title: '函数签名',
      dataIndex: 'signature',
      render: (sig?: string) =>
        sig ? <Text code style={{ fontSize: 12 }}>{sig}</Text> : <Text type="secondary">-</Text>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      render: (desc?: string) => desc || <Text type="secondary">-</Text>,
    },
  ];

  return (
    <Table<BoundTool>
      rowKey="toolCode"
      columns={columns}
      dataSource={tools}
      pagination={false}
      size="small"
      bordered
    />
  );
};

// ============================================================================
// 子组件：版本历史 Tab
// ============================================================================

const SkillVersionTab: React.FC<{ skillId: string }> = ({ skillId }) => {
  const [versions, setVersions] = useState<SkillVersion[]>([]);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffData, setDiffData] = useState<SkillVersionDiff | null>(null);
  const [diffVersions, setDiffVersions] = useState<{ v1: string; v2: string } | null>(null);

  useEffect(() => {
    if (!skillId) return;
    skillApi.getVersionHistory(skillId).then((list) => {
      setVersions((list as unknown as SkillVersion[]) || []);
    }).catch(() => {
      setVersions([]);
    });
  }, [skillId]);

  const loadDiff = async (v1: string, v2: string) => {
    setDiffLoading(true);
    setDiffVersions({ v1, v2 });
    try {
      const diff = await skillApi.getVersionDiff(skillId, v1, v2);
      setDiffData(diff);
    } catch {
      setDiffData(null);
    } finally {
      setDiffLoading(false);
    }
  };

  if (versions.length === 0) {
    return <Empty description="暂无版本历史" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }

  return (
    <div>
      {/* 版本时间线 */}
      <Timeline
        items={versions.slice(0, 10).map((v) => ({
          color: v.isActive ? 'blue' : 'gray',
          dot: v.isActive ? <StarFilled /> : <HistoryOutlined />,
          children: (
            <div>
              <Space>
                <Text strong>v{v.version}</Text>
                <Tag color={v.isActive ? 'blue' : 'default'}>
                  {v.isActive ? '当前版本' : v.isCanary ? '灰度版本' : '历史版本'}
                </Tag>
                {v.isPointerOnly && <Tag color="default">指针记录</Tag>}
              </Space>
              {v.description && (
                <div style={{ marginTop: 4 }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>{v.description}</Text>
                </div>
              )}
              <div style={{ marginTop: 4, fontSize: 12, color: '#8c8c8c' }}>
                {v.createTime ? new Date(v.createTime).toLocaleString() : '-'}
              </div>
            </div>
          ),
        }))}
      />

      {/* 版本对比 */}
      {versions.length >= 2 && (
        <Card
          size="small"
          title={
            <Space>
              <HistoryOutlined />
              <span>版本对比</span>
            </Space>
          }
          style={{ marginTop: 16 }}
        >
          <Space>
            <Text>选择版本对比：</Text>
            <Button
              size="small"
              onClick={() => {
                const v1 = versions[1]?.version;
                const v2 = versions[0]?.version;
                if (v1 && v2) loadDiff(v1, v2);
              }}
            >
              对比最近两个版本
            </Button>
          </Space>

          {diffLoading && <Spin style={{ marginLeft: 16 }} />}

          {diffData && diffVersions && (
            <div style={{ marginTop: 12 }}>
              <Divider style={{ margin: '8px 0' }} />
              <Text type="secondary">
                差异：v{diffVersions.v1} → v{diffVersions.v2}
              </Text>
              <div style={{ marginTop: 8 }}>
                {(() => {
                  const changedFields = Object.entries(diffData.fields || {})
                    .filter(([, v]) => (v as { changed: boolean }).changed);
                  if (changedFields.length === 0) {
                    return <Text type="secondary">无差异</Text>;
                  }
                  return (
                    <Space size={[4, 4]} wrap>
                      {changedFields.slice(0, 15).map(([field]) => (
                        <Tag key={field} color="orange">{field}</Tag>
                      ))}
                      {changedFields.length > 15 && (
                        <Tag>...+{changedFields.length - 15} 项</Tag>
                      )}
                    </Space>
                  );
                })()}
              </div>
            </div>
          )}
        </Card>
      )}
    </div>
  );
};

// ============================================================================
// 主组件：SkillDetailModal
// ============================================================================

const SkillDetailModal: React.FC<SkillDetailModalProps> = ({
  visible,
  record,
  onCancel,
  onEdit,
  onSubscribedChange,
}) => {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const authUser = useAuthStore((s) => s.user);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailSkill, setDetailSkill] = useState<Skill | null>(null);
  const [activeTab, setActiveTab] = useState('overview');
  const [subscribed, setSubscribed] = useState(false);

  // 弹窗打开时拉取完整详情 + 初始订阅状态（P1-ITEM-2：修复订阅按钮状态不确定）
  useEffect(() => {
    if (!visible || !record) return;
    setDetailSkill(null);
    setActiveTab('overview');
    setDetailLoading(true);
    skillApi
      .detail(record.id!)
      .then((full) => {
        setDetailSkill(full);
      })
      .catch(() => {
        // 失败时用列表 record 兜底展示
        setDetailSkill(record);
      })
      .finally(() => {
        setDetailLoading(false);
      });

    // P1-ITEM-2：打开详情时查询当前技能的订阅状态，按钮初始展示正确
    skillApi
      .subStatus(record.id!)
      .then((res) => {
        setSubscribed(Boolean(res?.subscribed));
      })
      .catch(() => {
        // 查询失败保持默认未订阅
        setSubscribed(false);
      });
  }, [visible, record]);

  const s = detailSkill;
  const canEdit = s ? checkEditPermission(s, authUser?.id ?? null) : false;
  // P2-11：ARCHIVED 状态操作闭包 -- 归档技能禁用试用/订阅操作（编辑仍允许，便于重新激活后修改）
  const isArchived = s?.lifeStatus === LifeStatus.ARCHIVED;

  // 订阅/取消订阅
  const toggleSubscribe = async () => {
    if (!record?.id) return;
    try {
      if (subscribed) {
        await skillApi.unsubscribe(record.id);
        message.success('已取消订阅');
        setSubscribed(false);
        onSubscribedChange?.(record.id, false);
      } else {
        await skillApi.subscribe(record.id);
        message.success('订阅成功');
        setSubscribed(true);
        onSubscribedChange?.(record.id, true);
      }
    } catch {
      // 错误已处理
    }
  };

  // 编辑跳转
  const handleEdit = useCallback(() => {
    if (!s) return;
    if (onEdit) {
      onEdit(s);
    } else {
      // 默认跳转到工作台编辑模式
      const skillParam = encodeURIComponent(JSON.stringify({
        skillId: s.id,
        skillCode: s.skillCode,
        mode: 'edit',
      }));
      navigate(`${ROUTE_PATH.WORKBENCH}?skill=${skillParam}`);
    }
  }, [s, onEdit, navigate]);

  const tabItems = useMemo(() => [
    {
      key: 'overview',
      label: (
        <Space>
          <EyeOutlined />
          概览
        </Space>
      ),
      children: s ? <SkillOverviewTab skill={s} /> : null,
    },
    {
      key: 'instructions',
      label: (
        <Space>
          <CodeOutlined />
          指令/方法论
        </Space>
      ),
      children: s ? <SkillInstructionsTab skill={s} /> : null,
    },
    {
      key: 'tools',
      label: (
        <Space>
          <ToolOutlined />
          绑定工具
        </Space>
      ),
      children: s ? <SkillToolsTab skill={s} /> : null,
    },
    {
      key: 'history',
      label: (
        <Space>
          <HistoryOutlined />
          版本历史
        </Space>
      ),
      children: s && s.id ? <SkillVersionTab skillId={s.id} /> : null,
    },
  ], [s]);

  return (
    <Modal
      title={
        <Space>
          <ThunderboltOutlined style={{ color: '#faad14' }} />
          <span>SKILL 详情</span>
          {s && <Tag color="blue">{s.skillName}</Tag>}
        </Space>
      }
      open={visible}
      onCancel={onCancel}
      width={960}
      bodyStyle={{ maxHeight: 'calc(100vh - 200px)', overflowY: 'auto', padding: '16px 24px' }}
      footer={[
        <Button
          key="edit"
          icon={<EditOutlined />}
          type="primary"
          onClick={handleEdit}
          disabled={!s || !canEdit}
          title={canEdit ? '编辑此 SKILL' : '仅创建者或管理员可编辑'}
        >
          {canEdit ? '编辑' : '无编辑权限'}
        </Button>,
        <Button
          key="sub"
          icon={<StarFilled />}
          type={subscribed ? 'default' : 'primary'}
          onClick={toggleSubscribe}
          disabled={!s || isArchived}
          title={isArchived ? '已归档技能不可订阅' : undefined}
        >
          {subscribed ? '取消订阅' : '订阅'}
        </Button>,
        <Button key="close" onClick={onCancel}>
          关闭
        </Button>,
      ]}
    >
      <Spin spinning={detailLoading}>
        {s ? (
          <Tabs
            activeKey={activeTab}
            onChange={setActiveTab}
            items={tabItems}
            size="small"
            style={{ marginBottom: 8 }}
          />
        ) : (
          !detailLoading && <Empty description="无法加载 SKILL 详情" />
        )}
      </Spin>
    </Modal>
  );
};

export default SkillDetailModal;
