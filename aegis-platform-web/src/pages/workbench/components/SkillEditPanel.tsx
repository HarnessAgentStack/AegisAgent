/**
 * @file SKILL 编辑面板
 * @description 工作台 SKILL 编辑模式的右侧面板，支持编辑基础信息、指令、绑定工具、执行配置。
 *              支持自动保存草稿（debounce 2s）、提交审核、权限校验。
 * @author aegis
 * @since 2.0.0
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Alert,
  App,
  Badge,
  Button,
  Card,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  ApiOutlined,
  CheckCircleOutlined,
  CloseOutlined,
  CloudOutlined,
  CodeOutlined,
  EditOutlined,
  SaveOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  ToolOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { Skill, McpServer } from '@/types/resource';
import { skillApi, mcpApi } from '@/api/resource';
import { CATEGORY_OPTIONS, parseJsonArray } from '@/pages/resource/skill/constants';
import { safeJsonParse } from '@/utils/number';

const { Text } = Typography;
const { TextArea } = Input;

// ============================================================================
// 类型定义
// ============================================================================

interface SkillEditPanelProps {
  /** 当前编辑的 SKILL */
  skill: Skill;
  /** 关闭面板 */
  onClose: () => void;
  /** 提交审核成功回调 */
  onSubmitted?: (skill: Skill) => void;
  /** 保存成功回调 */
  onSaved?: (skill: Skill) => void;
  /** 容器获取函数（用于嵌入工作台） */
  getContainer?: () => HTMLElement;
}

/** 绑定工具分组（按 MCP） */
interface ToolBindingGroup {
  mcpId: string;
  mcpName: string;
  mcpCode?: string;
  tools: ToolBindingItem[];
}

interface ToolBindingItem {
  toolCode: string;
  toolName?: string;
  signature?: string;
  selected: boolean;
}

/** 执行配置 */
interface SkillExecConfig {
  modelTier?: string;
  temperature?: number;
  maxTurns?: number;
  enableInputFilter?: boolean;
  enableOutputAudit?: boolean;
  enablePiiDetection?: boolean;
  enableRateLimit?: boolean;
  memoryStrategy?: string;
}

// ============================================================================
// 解析工具
// ============================================================================

/** 解析绑定工具 JSON → 按 MCP 分组 */
function parseBindingTools(bindingToolsStr?: string): ToolBindingGroup[] {
  if (!bindingToolsStr) return [];
  const data = safeJsonParse<unknown>(bindingToolsStr);
  if (data != null && typeof data === 'object' && !Array.isArray(data)) {
    const obj = data as Record<string, unknown[]>;
    return Object.entries(obj).map(([mcpName, tools]) => ({
      mcpId: '',
      mcpName,
      tools: Array.isArray(tools)
        ? tools.map((t: unknown) => {
            const tool = t as string | Record<string, unknown>;
            return {
              toolCode: typeof tool === 'string' ? tool : String((tool as Record<string, unknown>).toolCode ?? tool),
              toolName: typeof tool === 'string' ? undefined : String((tool as Record<string, unknown>).toolName ?? ''),
              signature: typeof tool === 'string' ? undefined : String((tool as Record<string, unknown>).signature ?? ''),
              selected: true,
            };
          })
        : [],
    }));
  }
  if (Array.isArray(data)) {
    return [{
      mcpId: '',
      mcpName: '默认',
      tools: (data as (string | Record<string, unknown>)[]).map((t) => {
        // 兼容后端 enrichBindingTools 增强后的混合结构：字符串 toolCode 与 {toolCode,toolName,description,toolType} 对象共存
        if (typeof t === 'string') return { toolCode: t, selected: true };
        const obj = t as Record<string, unknown>;
        return {
          toolCode: String(obj.toolCode ?? ''),
          toolName: String(obj.toolName ?? ''),
          signature: String(obj.signature ?? ''),
          selected: true,
        };
      }),
    }];
  }
  return [];
}

/** 将按 MCP 分组的结构序列化回 JSON */
function serializeBindingTools(groups: ToolBindingGroup[]): string {
  const obj: Record<string, unknown[]> = {};
  groups.forEach((g) => {
    const selected = g.tools.filter((t) => t.selected);
    if (selected.length > 0) {
      obj[g.mcpName] = selected.map((t) => ({
        toolCode: t.toolCode,
        toolName: t.toolName,
        signature: t.signature,
      }));
    }
  });
  return Object.keys(obj).length > 0 ? JSON.stringify(obj) : '';
}

// ============================================================================
// 主组件：SkillEditPanel
// ============================================================================

export const SkillEditPanel: React.FC<SkillEditPanelProps> = ({
  skill,
  onClose,
  onSubmitted,
  onSaved,
  getContainer,
}) => {
  const { message } = App.useApp();

  // 本地编辑状态
  const [editName, setEditName] = useState(skill.skillName);
  const [editCategory, setEditCategory] = useState(skill.category ?? '');
  const [editDescription, setEditDescription] = useState(skill.description ?? '');
  const [editInstructions, setEditInstructions] = useState(skill.instructions ?? '');
  const [editTags, setEditTags] = useState<string[]>(parseJsonArray(skill.tags));
  const [tagInput, setTagInput] = useState('');

  // 绑定工具
  const [bindingGroups, setBindingGroups] = useState<ToolBindingGroup[]>(
    parseBindingTools(skill.bindingTools),
  );
  const [mcpList, setMcpList] = useState<McpServer[]>([]);
  const [loadingMcp, setLoadingMcp] = useState(false);
  const [showMcpSelector, setShowMcpSelector] = useState(false);

  // 执行配置
  const [execConfig, setExecConfig] = useState<SkillExecConfig>(() => {
    // P1-ITEM-4：统一从 skill.execConfig 读取（后端唯一字段名），移除 config/executionConfig 双命名兼容
    const configStr = skill.execConfig;
    return configStr ? (safeJsonParse<SkillExecConfig>(configStr) ?? {}) : {};
  });

  // 自动保存状态
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [lastSavedAt, setLastSavedAt] = useState<number | null>(null);
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 草稿模式：DRAFT 状态直接编辑，REJECTED 也可编辑
  // isDraftMode 预留用于后续状态显示增强

  // 加载可用 MCP 服务
  useEffect(() => {
    setLoadingMcp(true);
    mcpApi.listServices({ size: 100 })
      .then((res) => {
        const list = Array.isArray(res) ? res : (res?.records ?? []);
        setMcpList(list);
      })
      .catch(() => {
        // 静默失败，MCP 选择器仅影响添加功能
      })
      .finally(() => setLoadingMcp(false));
  }, []);

  // 标记为脏（有未保存修改）
  const markDirty = useCallback(() => {
    setDirty(true);
  }, []);

  // 自动保存（debounce 2s）
  const autoSave = useCallback(async () => {
    if (!dirty || !skill.id) return;
    setSaving(true);
    try {
      const data = collectFormData();
      await skillApi.update(skill.id, data);
      setLastSavedAt(Date.now());
      setDirty(false);
      onSaved?.({ ...skill, ...data });
    } catch (err) {
      console.error('自动保存失败:', err);
      message.error('自动保存失败，请检查网络后重试');
    } finally {
      setSaving(false);
    }
  }, [dirty, skill, onSaved]);

  // debounce 自动保存
  useEffect(() => {
    if (autoSaveTimerRef.current) {
      clearTimeout(autoSaveTimerRef.current);
    }
    if (dirty) {
      autoSaveTimerRef.current = setTimeout(() => {
        autoSave();
      }, 2000);
    }
    return () => {
      if (autoSaveTimerRef.current) {
        clearTimeout(autoSaveTimerRef.current);
      }
    };
  }, [dirty, autoSave]);

  /** 收集所有编辑表单数据 */
  const collectFormData = useCallback((): Partial<Skill> => {
    const bindingsJson = serializeBindingTools(bindingGroups);
    return {
      skillName: editName,
      category: editCategory,
      description: editDescription,
      instructions: editInstructions,
      tags: editTags.length > 0 ? JSON.stringify(editTags) : undefined,
      bindingTools: bindingsJson || undefined,
      // P1-ITEM-4：修复空 spread bug —— execConfig 序列化后提交，编辑配置不再丢失
      execConfig:
        execConfig && Object.keys(execConfig).length > 0
          ? JSON.stringify(execConfig)
          : undefined,
    };
  }, [editName, editCategory, editDescription, editInstructions, editTags, bindingGroups, execConfig, skill]);

  // ========== 标签管理 ==========

  const addTag = () => {
    const t = tagInput.trim();
    if (t && !editTags.includes(t)) {
      setEditTags([...editTags, t]);
      markDirty();
    }
    setTagInput('');
  };

  const removeTag = (t: string) => {
    setEditTags(editTags.filter((x) => x !== t));
    markDirty();
  };

  // ========== 绑定工具管理 ==========

  const toggleTool = (mcpIdx: number, toolIdx: number) => {
    setBindingGroups((prev) => {
      const next = prev.map((g, gi) => {
        if (gi !== mcpIdx) return g;
        return {
          ...g,
          tools: g.tools.map((t, ti) =>
            ti === toolIdx ? { ...t, selected: !t.selected } : t,
          ),
        };
      });
      return next;
    });
    markDirty();
  };

  const removeGroup = (mcpIdx: number) => {
    setBindingGroups((prev) => prev.filter((_, i) => i !== mcpIdx));
    markDirty();
  };

  const addMcpBinding = (mcp: McpServer) => {
    // 检查是否已添加
    if (bindingGroups.some((g) => g.mcpName === mcp.mcpName || g.mcpId === mcp.id)) {
      message.info(`MCP「${mcp.mcpName}」已绑定`);
      return;
    }
    // 创建新分组
    const newGroup: ToolBindingGroup = {
      mcpId: mcp.id ?? '',
      mcpName: mcp.mcpName,
      mcpCode: mcp.mcpCode,
      tools: [],
    };
    setBindingGroups((prev) => [...prev, newGroup]);
    setShowMcpSelector(false);
    markDirty();
    message.success(`已添加 MCP「${mcp.mcpName}」，请在下方选择绑定的工具`);
  };

  // ========== 执行配置 ==========

  const updateExecConfig = (key: keyof SkillExecConfig, value: unknown) => {
    setExecConfig((prev) => ({ ...prev, [key]: value }));
    markDirty();
  };

  // ========== 保存与提交 ==========

  const handleManualSave = async () => {
    if (!skill.id) return;
    setSaving(true);
    try {
      const data = collectFormData();
      await skillApi.update(skill.id, data);
      setLastSavedAt(Date.now());
      setDirty(false);
      message.success('草稿保存成功');
      onSaved?.({ ...skill, ...data });
    } catch (err) {
      console.error('保存失败:', err);
      message.error('保存失败，请重试');
    } finally {
      setSaving(false);
    }
  };

  const handleSubmitReview = async () => {
    if (!skill.id) return;
    // 先保存
    let updatedData: Record<string, unknown> = {};
    if (dirty) {
      setSaving(true);
      try {
        updatedData = collectFormData();
        await skillApi.update(skill.id, updatedData);
        setDirty(false);
      } catch (err) {
        message.error('保存失败，无法提交审核');
        setSaving(false);
        return;
      }
    }
    try {
      await skillApi.submitReview(skill.id);
      message.success('已提交审核，等待审核人员审批');
      onSubmitted?.({ ...skill, ...updatedData });
    } catch (err) {
      console.error('提交审核失败:', err);
      message.error('提交审核失败');
    } finally {
      setSaving(false);
    }
  };

  // ========== 变量识别 ==========
  const detectedVariables = useMemo(() => {
    const regex = /\{(\w+)\}/g;
    const found = new Set<string>();
    let match: RegExpExecArray | null;
    while ((match = regex.exec(editInstructions)) !== null) {
      found.add(match[1]);
    }
    return Array.from(found);
  }, [editInstructions]);

  // ========== 底部按钮 ==========

  const footerButtons = (
    <Space>
      <Button
        icon={<CloseOutlined />}
        onClick={onClose}
      >
        关闭
      </Button>
      <Button
        icon={<SaveOutlined />}
        onClick={handleManualSave}
        loading={saving}
        disabled={!dirty}
      >
        保存草稿
      </Button>
      <Button
        type="primary"
        icon={<CheckCircleOutlined />}
        onClick={handleSubmitReview}
        loading={saving}
        disabled={dirty}
      >
        提交审核
      </Button>
    </Space>
  );

  return (
    <Drawer
      title={
        <Space>
          <ThunderboltOutlined style={{ color: '#faad14' }} />
          <span>编辑 SKILL：{editName || skill.skillName}</span>
          <Tag color="blue">{skill.skillCode}</Tag>
          {dirty && (
            <Tag color="orange" style={{ marginLeft: 8 }}>
              <WarningOutlined /> 有未保存修改
            </Tag>
          )}
          {!dirty && lastSavedAt && (
            <Tag color="green" style={{ marginLeft: 8 }}>
              <CheckCircleOutlined /> 已保存 {new Date(lastSavedAt).toLocaleTimeString()}
            </Tag>
          )}
        </Space>
      }
      placement="right"
      width={680}
      open={true}
      onClose={onClose}
      bodyStyle={{ padding: '12px 16px', overflowY: 'auto', height: 'calc(100vh - 64px)' }}
      footer={footerButtons}
      footerStyle={{ padding: '12px 16px', borderTop: '1px solid #f0f0f0', background: '#fafafa' }}
      maskClosable={false}
      closable={true}
      getContainer={getContainer}
    >
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {/* =============== 基础信息 =============== */}
        <Card
          size="small"
          title={
            <Space>
              <EditOutlined />
              <span>基础信息</span>
            </Space>
          }
        >
          <Form layout="vertical" size="small">
            <Form.Item label="SKILL 名称" required>
              <Input
                value={editName}
                onChange={(e) => {
                  setEditName(e.target.value);
                  markDirty();
                }}
                placeholder="输入 SKILL 名称"
                maxLength={64}
                showCount
              />
            </Form.Item>
            <Form.Item label="分类">
              <Select
                value={editCategory || undefined}
                onChange={(v) => {
                  setEditCategory(v);
                  markDirty();
                }}
                placeholder="选择分类"
                allowClear
                options={CATEGORY_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
              />
            </Form.Item>
            <Form.Item label="描述">
              <TextArea
                value={editDescription}
                onChange={(e) => {
                  setEditDescription(e.target.value);
                  markDirty();
                }}
                placeholder="简要描述此 SKILL 的用途和能力"
                rows={3}
                maxLength={500}
                showCount
              />
            </Form.Item>
            <Form.Item label="标签">
              <Space direction="vertical" style={{ width: '100%' }}>
                <Space wrap>
                  {editTags.map((t) => (
                    <Tag
                      key={t}
                      closable
                      onClose={() => removeTag(t)}
                      color="blue"
                    >
                      {t}
                    </Tag>
                  ))}
                </Space>
                <Space.Compact>
                  <Input
                    value={tagInput}
                    onChange={(e) => setTagInput(e.target.value)}
                    onPressEnter={addTag}
                    placeholder="输入标签后回车添加"
                    style={{ width: 200 }}
                    allowClear
                  />
                  <Button onClick={addTag}>添加</Button>
                </Space.Compact>
              </Space>
            </Form.Item>
          </Form>
        </Card>

        {/* =============== 指令/方法论 =============== */}
        <Card
          size="small"
          title={
            <Space>
              <CodeOutlined />
              <span>指令 / 方法论（Prompt）</span>
              <Tag color="blue">{editInstructions.split('\n').length} 行</Tag>
            </Space>
          }
          extra={
            <Tooltip title="指令是 SKILL 的核心，定义了 AI 的行为逻辑">
              <InfoCircleIcon />
            </Tooltip>
          }
        >
          <Alert
            type="info"
            showIcon
            message="指令编写提示"
            description="使用 {variable} 定义变量；使用 # 或 ## 添加注释；使用 - 创建列表项。指令修改后，SKILL 行为将随之变化。"
            style={{ marginBottom: 12 }}
          />
          <TextArea
            value={editInstructions}
            onChange={(e) => {
              setEditInstructions(e.target.value);
              markDirty();
            }}
            placeholder={`<system_prompt>
你是一个专业的[领域]助手，帮助用户完成[任务]。

## 工作流程
1. 理解用户意图
2. 提取关键信息
3. 调用对应工具
4. 返回格式化结果

## 注意事项
- 使用 {order_id} 作为订单编号
- 对于 {status} 参数，支持 pending/shipped/delivered
</system_prompt>`}
            autoSize={{ minRows: 8, maxRows: 30 }}
            style={{
              fontFamily: 'Consolas, "Courier New", monospace',
              fontSize: 13,
              lineHeight: '22px',
            }}
          />

          {/* 变量识别 */}
          {detectedVariables.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <Text type="secondary" style={{ fontSize: 12 }}>
                已识别变量：
              </Text>
              <Space size={[4, 4]} wrap style={{ marginTop: 4 }}>
                {detectedVariables.map((v) => (
                  <Tag key={v} color="processing">
                    {'{'}{v}{'}'}
                  </Tag>
                ))}
              </Space>
            </div>
          )}
        </Card>

        {/* =============== 绑定工具 =============== */}
        <Card
          size="small"
          title={
            <Space>
              <ToolOutlined />
              <span>绑定工具</span>
              {bindingGroups.length > 0 && (
                <Badge
                  count={bindingGroups.reduce((sum, g) => sum + g.tools.filter((t) => t.selected).length, 0)}
                  style={{ backgroundColor: '#1677ff' }}
                />
              )}
            </Space>
          }
          extra={
            <Button
              size="small"
              type="primary"
              icon={<ApiOutlined />}
              onClick={() => setShowMcpSelector(true)}
            >
              添加 MCP
            </Button>
          }
        >
          {bindingGroups.length === 0 ? (
            <Empty
              description={
                <div>
                  <div style={{ marginBottom: 4 }}>暂未绑定任何工具</div>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    点击"添加 MCP"选择 MCP 服务并绑定其中的工具
                  </Text>
                </div>
              }
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {bindingGroups.map((group, gi) => (
                <div
                  key={group.mcpName}
                  style={{
                    border: '1px solid #f0f0f0',
                    borderRadius: 6,
                    padding: '8px 12px',
                    background: '#fafafa',
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                    <Space>
                      <ApiOutlined style={{ color: '#1677ff' }} />
                      <Text strong>{group.mcpName}</Text>
                      <Tag color="blue">
                        {group.tools.filter((t) => t.selected).length}/{group.tools.length} 已选
                      </Tag>
                    </Space>
                    <Button
                      size="small"
                      type="text"
                      danger
                      onClick={() => removeGroup(gi)}
                    >
                      移除
                    </Button>
                  </div>
                  {group.tools.length === 0 ? (
                    <div style={{ color: '#999', fontSize: 12 }}>
                      尚未选择工具，点击下方添加
                    </div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                      {group.tools.map((tool, ti) => (
                        <div
                          key={tool.toolCode}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '4px 8px',
                            borderRadius: 4,
                            background: tool.selected ? '#e6f4ff' : 'transparent',
                            cursor: 'pointer',
                          }}
                          onClick={() => toggleTool(gi, ti)}
                        >
                          <span onClick={(e) => e.stopPropagation()}>
                            <Switch
                              size="small"
                              checked={tool.selected}
                              onChange={() => toggleTool(gi, ti)}
                            />
                          </span>
                          <Text code style={{ fontSize: 13 }}>{tool.toolCode}</Text>
                          {tool.toolName && (
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {tool.toolName}
                            </Text>
                          )}
                          {tool.signature && (
                            <Tooltip title={tool.signature}>
                              <Text
                                type="secondary"
                                style={{
                                  fontSize: 11,
                                  overflow: 'hidden',
                                  textOverflow: 'ellipsis',
                                  whiteSpace: 'nowrap',
                                  maxWidth: 200,
                                }}
                              >
                                {tool.signature}
                              </Text>
                            </Tooltip>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </Card>

        {/* =============== 执行配置 =============== */}
        <Card
          size="small"
          title={
            <Space>
              <SettingOutlined />
              <span>执行配置</span>
            </Space>
          }
        >
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Form.Item label="模型档位" style={{ marginBottom: 8 }}>
              <Select
                value={execConfig.modelTier || 'STANDARD'}
                onChange={(v) => updateExecConfig('modelTier', v)}
                options={[
                  { value: 'LIGHT', label: '轻量版（快速、低成本）' },
                  { value: 'STANDARD', label: '标准版（平衡）' },
                  { value: 'STRONG', label: '专业版（高质量）' },
                ]}
              />
            </Form.Item>
            <Form.Item label="温度" style={{ marginBottom: 8 }}>
              <InputNumber
                min={0}
                max={2}
                step={0.1}
                value={execConfig.temperature ?? 0.7}
                onChange={(v) => updateExecConfig('temperature', v ?? 0.7)}
                style={{ width: '100%' }}
              />
            </Form.Item>
            <Form.Item label="最大轮数" style={{ marginBottom: 8 }}>
              <InputNumber
                min={1}
                max={50}
                value={execConfig.maxTurns ?? 10}
                onChange={(v) => updateExecConfig('maxTurns', v ?? 10)}
                style={{ width: '100%' }}
              />
            </Form.Item>
            <Form.Item label="记忆策略" style={{ marginBottom: 8 }}>
              <Select
                value={execConfig.memoryStrategy || 'SESSION_LEVEL'}
                onChange={(v) => updateExecConfig('memoryStrategy', v)}
                options={[
                  { value: 'SESSION_LEVEL', label: '会话级（不跨会话保留）' },
                  { value: 'LONG_TERM', label: '长期记忆（跨会话保留）' },
                ]}
              />
            </Form.Item>
          </div>

          <Divider style={{ margin: '8px 0' }}>安全护栏</Divider>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
            <Form.Item label="输入过滤" style={{ marginBottom: 4 }}>
              <Switch
                checked={execConfig.enableInputFilter ?? true}
                onChange={(v) => updateExecConfig('enableInputFilter', v)}
              />
            </Form.Item>
            <Form.Item label="输出审核" style={{ marginBottom: 4 }}>
              <Switch
                checked={execConfig.enableOutputAudit ?? true}
                onChange={(v) => updateExecConfig('enableOutputAudit', v)}
              />
            </Form.Item>
            <Form.Item label="PII 检测" style={{ marginBottom: 4 }}>
              <Switch
                checked={execConfig.enablePiiDetection ?? true}
                onChange={(v) => updateExecConfig('enablePiiDetection', v)}
              />
            </Form.Item>
            <Form.Item label="速率限制" style={{ marginBottom: 4 }}>
              <Switch
                checked={execConfig.enableRateLimit ?? true}
                onChange={(v) => updateExecConfig('enableRateLimit', v)}
              />
            </Form.Item>
          </div>
        </Card>
      </div>

      {/* MCP 选择弹窗 */}
      <McpSelectorModal
        open={showMcpSelector}
        mcpList={mcpList}
        loading={loadingMcp}
        existingBindings={bindingGroups.map((g) => g.mcpName)}
        onSelect={(mcp) => addMcpBinding(mcp)}
        onCancel={() => setShowMcpSelector(false)}
      />
    </Drawer>
  );
};

// ============================================================================
// Info Circle 图标组件（内联 SVG）
// ============================================================================
const InfoCircleIcon: React.FC = () => (
  <span
    style={{
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      width: 16,
      height: 16,
      borderRadius: '50%',
      background: '#e6f4ff',
      color: '#1677ff',
      fontSize: 11,
      fontWeight: 600,
      cursor: 'help',
    }}
  >
    i
  </span>
);

// ============================================================================
// MCP 选择弹窗
// ============================================================================

interface McpSelectorModalProps {
  open: boolean;
  mcpList: McpServer[];
  loading: boolean;
  existingBindings: string[];
  onSelect: (mcp: McpServer) => void;
  onCancel: () => void;
}

const McpSelectorModal: React.FC<McpSelectorModalProps> = ({
  open,
  mcpList,
  loading,
  existingBindings,
  onSelect,
  onCancel,
}) => {
  const [keyword, setKeyword] = useState('');

  const filteredList = useMemo(() => {
    if (!keyword) return mcpList;
    const kw = keyword.toLowerCase();
    return mcpList.filter(
      (m) =>
        m.mcpName.toLowerCase().includes(kw) ||
        m.mcpCode.toLowerCase().includes(kw) ||
        (m.description?.toLowerCase().includes(kw) ?? false),
    );
  }, [mcpList, keyword]);

  if (!open) return null;

  return (
    <Modal
      title={
        <Space>
          <CloudOutlined />
          <span>选择 MCP 服务</span>
        </Space>
      }
      open={open}
      onCancel={onCancel}
      footer={null}
      width={520}
      bodyStyle={{ padding: 16 }}
    >
      <Input.Search
        placeholder="搜索 MCP 服务名称或编码"
        allowClear
        value={keyword}
        onChange={(e) => setKeyword(e.target.value)}
        style={{ marginBottom: 12 }}
      />
      {loading ? (
        <div style={{ textAlign: 'center', padding: 24, color: '#999' }}>加载中...</div>
      ) : filteredList.length === 0 ? (
        <Empty description={keyword ? '未找到匹配的 MCP 服务' : '暂无可选 MCP 服务'} />
      ) : (
        <div style={{ maxHeight: 320, overflowY: 'auto' }}>
          {filteredList.map((mcp) => {
            const isBound = existingBindings.includes(mcp.mcpName) || existingBindings.includes(String(mcp.id));
            return (
              <div
                key={mcp.id}
                onClick={() => !isBound && onSelect(mcp)}
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  padding: '10px 12px',
                  border: '1px solid #f0f0f0',
                  borderRadius: 6,
                  marginBottom: 6,
                  cursor: isBound ? 'not-allowed' : 'pointer',
                  background: isBound ? '#f5f5f5' : '#fff',
                  transition: 'all .15s',
                }}
              >
                <Space>
                  <ApiOutlined style={{ color: '#1677ff' }} />
                  <div>
                    <div style={{ fontWeight: 500 }}>{mcp.mcpName}</div>
                    <div style={{ fontSize: 12, color: '#999' }}>
                      <Text code style={{ fontSize: 11 }}>{mcp.mcpCode}</Text>
                      {mcp.toolCount > 0 && <Tag color="blue" style={{ marginLeft: 4 }}>{mcp.toolCount} 工具</Tag>}
                    </div>
                  </div>
                </Space>
                {isBound ? (
                  <Tag color="default">已绑定</Tag>
                ) : (
                  <Button size="small" type="primary">选择</Button>
                )}
              </div>
            );
          })}
        </div>
      )}
    </Modal>
  );
};

export default SkillEditPanel;