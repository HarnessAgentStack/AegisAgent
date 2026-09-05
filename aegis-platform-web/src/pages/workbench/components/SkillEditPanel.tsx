/**
 * @file SKILL 编辑面板
 * @description 工作台 SKILL 编辑模式右侧面板。与 SkillStudioPanel（生成面板）同构：
 *              相同的 340 侧栏容器、渐变信息卡、分段标题、Tag 颜色体系、底部 flex 操作栏。
 *              差异仅在控件可编辑性（Input/Select/TextArea/Switch），实现"以生成面板为准"的视觉与字段对齐。
 *              支持自动保存草稿（debounce 2s）、提交审核、MCP 工具绑定。
 * @author aegis
 * @since 3.1.0
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  App,
  Button,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import {
  ApiOutlined,
  CheckCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  FileOutlined,
  SaveOutlined,
  ThunderboltOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import type { Skill, McpServer } from '@/types/resource';
import { skillApi, mcpApi } from '@/api/resource';
import { CATEGORY_OPTIONS, parseJsonArray, SECURITY_OPTIONS, SKILL_TYPE_TAG } from '@/pages/resource/skill/constants';
import { getSkillFiles } from '@/api/skill';
import { safeJsonParse } from '@/utils/number';

const { Text } = Typography;
const { TextArea } = Input;

interface SkillEditPanelProps {
  /** 当前编辑的 SKILL */
  skill: Skill;
  /** 关闭面板 */
  onClose: () => void;
  /** 提交审核成功回调 */
  onSubmitted?: (skill: Skill) => void;
  /** 保存成功回调 */
  onSaved?: (skill: Skill) => void;
  /** 容器获取函数（保留接口兼容，侧栏内嵌无需用） */
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

/** 技能文件项（编辑面板从 DB 拉取文件树，对齐生成面板） */
interface SkillFileItem {
  name: string;
  path: string;
  content?: string;
}

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

export const SkillEditPanel: React.FC<SkillEditPanelProps> = ({
  skill,
  onClose,
  onSubmitted,
  onSaved,
}) => {
  const { message } = App.useApp();

  const [editName, setEditName] = useState(skill.skillName ?? '');
  const [editCategory, setEditCategory] = useState(skill.category ?? '');
  const [editDescription, setEditDescription] = useState(skill.description ?? '');
  const [editInstructions, setEditInstructions] = useState(skill.instructions ?? '');
  const [editTags, setEditTags] = useState<string[]>(parseJsonArray(skill.tags));
  const [editSkillType, setEditSkillType] = useState<string>(skill.skillType ?? 'ATOMIC');
  const [editSecurityLevel, setEditSecurityLevel] = useState<string>(skill.securityLevel ?? 'L1');
  const [editInputs, setEditInputs] = useState<string>(skill.inputs ?? '');
  const [editOutputs, setEditOutputs] = useState<string>(skill.outputs ?? '');

  const [bindingGroups, setBindingGroups] = useState<ToolBindingGroup[]>(
    parseBindingTools(skill.bindingTools),
  );
  const [mcpList, setMcpList] = useState<McpServer[]>([]);
  const [loadingMcp, setLoadingMcp] = useState(false);
  const [showMcpSelector, setShowMcpSelector] = useState(false);

  // 技能文件树（从 DB 拉取，对齐生成面板的文件结构区）
  const [skillFiles, setSkillFiles] = useState<SkillFileItem[]>([]);
  const [expandedFiles, setExpandedFiles] = useState(true);
  const [showInputsOutputs, setShowInputsOutputs] = useState(false);

  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [lastSavedAt, setLastSavedAt] = useState<number | null>(null);
  const autoSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    setLoadingMcp(true);
    mcpApi.listServices({ size: 100 })
      .then((res) => {
        const list = Array.isArray(res) ? res : (res?.records ?? []);
        setMcpList(list);
      })
      .catch(() => { })
      .finally(() => setLoadingMcp(false));
  }, []);

  // 从 DB 拉取技能持久化文件（res_skill_file），补齐生成面板同款文件树
  useEffect(() => {
    if (!skill.id) return;
    getSkillFiles(String(skill.id))
      .then((files) => {
        setSkillFiles(files.map((f) => ({ name: f.fileName, path: f.filePath, content: f.content })));
      })
      .catch(() => { });
  }, [skill.id]);

  const markDirty = useCallback(() => setDirty(true), []);

  const collectFormData = useCallback((): Partial<Skill> => {
    const bindingsJson = serializeBindingTools(bindingGroups);
    return {
      skillName: editName,
      category: editCategory,
      description: editDescription,
      instructions: editInstructions,
      skillType: editSkillType as Skill['skillType'],
      securityLevel: editSecurityLevel as Skill['securityLevel'],
      tags: editTags.length > 0 ? JSON.stringify(editTags) : undefined,
      bindingTools: bindingsJson || undefined,
      inputs: editInputs,
      outputs: editOutputs,
    };
  }, [editName, editCategory, editDescription, editInstructions, editSkillType, editSecurityLevel, editTags, bindingGroups, editInputs, editOutputs]);

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
  }, [dirty, skill, onSaved, collectFormData, message]);

  useEffect(() => {
    if (autoSaveTimerRef.current) clearTimeout(autoSaveTimerRef.current);
    if (dirty) {
      autoSaveTimerRef.current = setTimeout(() => { autoSave(); }, 2000);
    }
    return () => { if (autoSaveTimerRef.current) clearTimeout(autoSaveTimerRef.current); };
  }, [dirty, autoSave]);

  const selectedToolCount = useMemo(
    () => bindingGroups.reduce((sum, g) => sum + g.tools.filter((t) => t.selected).length, 0),
    [bindingGroups],
  );

  const detectedVariables = useMemo(() => {
    const regex = /\{(\w+)\}/g;
    const found = new Set<string>();
    let match: RegExpExecArray | null;
    while ((match = regex.exec(editInstructions)) !== null) found.add(match[1]);
    return Array.from(found);
  }, [editInstructions]);

  const addTag = (v: string) => {
    const t = v.trim();
    if (t && !editTags.includes(t)) { setEditTags([...editTags, t]); markDirty(); }
  };
  const removeTag = (t: string) => { setEditTags(editTags.filter((x) => x !== t)); markDirty(); };

  const toggleTool = (mcpIdx: number, toolIdx: number) => {
    setBindingGroups((prev) => prev.map((g, gi) => gi !== mcpIdx ? g : {
      ...g, tools: g.tools.map((t, ti) => ti === toolIdx ? { ...t, selected: !t.selected } : t),
    }));
    markDirty();
  };
  const removeGroup = (mcpIdx: number) => { setBindingGroups((prev) => prev.filter((_, i) => i !== mcpIdx)); markDirty(); };
  const addMcpBinding = (mcp: McpServer) => {
    if (bindingGroups.some((g) => g.mcpName === mcp.mcpName || g.mcpId === mcp.id)) {
      message.info(`MCP「${mcp.mcpName}」已绑定`); return;
    }
    setBindingGroups((prev) => [...prev, { mcpId: mcp.id ?? '', mcpName: mcp.mcpName, mcpCode: mcp.mcpCode, tools: [] }]);
    setShowMcpSelector(false);
    markDirty();
    message.success(`已添加 MCP「${mcp.mcpName}」，请在下方选择绑定的工具`);
  };

  const handleManualSave = async () => {
    if (!skill.id) return;
    setSaving(true);
    try {
      const data = collectFormData();
      await skillApi.update(skill.id, data);
      setLastSavedAt(Date.now()); setDirty(false);
      message.success('草稿保存成功');
      onSaved?.({ ...skill, ...data });
    } catch (err) { console.error('保存失败:', err); message.error('保存失败，请重试'); }
    finally { setSaving(false); }
  };

  const handleSubmitReview = async () => {
    if (!skill.id) return;
    let updatedData: Record<string, unknown> = {};
    if (dirty) {
      setSaving(true);
      try {
        updatedData = collectFormData();
        await skillApi.update(skill.id, updatedData);
        setDirty(false);
      } catch (err) { message.error('保存失败，无法提交审核'); setSaving(false); return; }
    }
    try {
      await skillApi.submitReview(skill.id);
      message.success('已提交审核，等待审核人员审批');
      onSubmitted?.({ ...skill, ...updatedData });
    } catch (err) { console.error('提交审核失败:', err); message.error('提交审核失败'); }
    finally { setSaving(false); }
  };

  // ============ 渲染：与 SkillStudioPanel 同构 ============

  const renderHeader = () => (
    <div style={{
      padding: '10px 14px', borderBottom: '1px solid #e8e8e8',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      background: '#fff', flexShrink: 0,
    }}>
      <Space size={8}>
        <div style={{
          width: 26, height: 26, borderRadius: 6,
          background: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <ThunderboltOutlined style={{ fontSize: 13, color: '#fff' }} />
        </div>
        <div>
          <div style={{ fontWeight: 600, fontSize: 12, color: '#1f2937' }}>编辑技能</div>
          <div style={{ fontSize: 10, color: '#9ca3af' }}>
            {dirty ? <span style={{ color: '#faad14' }}>● 有未保存修改</span>
              : lastSavedAt ? `已保存 ${new Date(lastSavedAt).toLocaleTimeString()}`
              : `ID: #${skill.id}`}
          </div>
        </div>
      </Space>
      <Button type="text" size="small" icon={<CloseOutlined style={{ fontSize: 12 }} />} onClick={onClose} title="关闭编辑面板" />
    </div>
  );

  // 渐变信息卡：对齐 SkillStudioPanel.renderSkillInfo，控件换可编辑版本
  const renderSkillInfo = () => (
    <div style={{
      padding: 12,
      background: 'linear-gradient(135deg, #f8f9ff 0%, #f0f4ff 100%)',
      border: '1px solid #e0e7ff', borderRadius: 8, marginBottom: 12,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
        <Input
          value={editName}
          onChange={(e) => { setEditName(e.target.value); markDirty(); }}
          size="small" maxLength={64} placeholder="技能名称"
          style={{ fontWeight: 600, fontSize: 14, flex: 1, color: '#1f2937' }}
        />
        <Select
          value={editSkillType} size="small" style={{ width: 90 }}
          onChange={(v) => { setEditSkillType(v); markDirty(); }}
          options={(Object.keys(SKILL_TYPE_TAG) as Array<keyof typeof SKILL_TYPE_TAG>).map((k) => ({ value: k, label: SKILL_TYPE_TAG[k].text }))}
        />
      </div>
      <div style={{ fontSize: 11, color: '#6b7280', fontFamily: 'monospace', marginBottom: 6 }}>
        @{skill.skillCode || 'SKILL_CODE'}
      </div>
      <TextArea
        value={editDescription}
        onChange={(e) => { setEditDescription(e.target.value); markDirty(); }}
        size="small" rows={2} maxLength={500} placeholder="技能描述"
        style={{ fontSize: 12, color: '#6b7280', lineHeight: '18px', resize: 'none' }}
      />
      {/* 标签 —— 对齐生成面板的 closable Tag + 内联小 Input 形态 */}
      <div style={{ marginTop: 8 }}>
        <span style={{ fontSize: 12, color: '#999' }}>标签</span>
        <div style={{ marginTop: 4 }}>
          {editTags.map((t) => (
            <Tag key={t} closable onClose={() => removeTag(t)}>{t}</Tag>
          ))}
          <Input
            size="small" style={{ width: 100 }} placeholder="添加标签"
            onPressEnter={(e) => {
              const v = (e.target as HTMLInputElement).value.trim();
              if (v && !editTags.includes(v)) addTag(v);
              (e.target as HTMLInputElement).value = '';
            }}
          />
        </div>
      </div>
      {/* meta Tag 行 —— category/securityLevel 改为 Select，对齐生成面板 Tag 颜色 */}
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8, alignItems: 'center' }}>
        <Select
          value={editCategory || undefined} size="small" placeholder="分类" allowClear
          style={{ width: 110 }}
          onChange={(v) => { setEditCategory(v ?? ''); markDirty(); }}
          options={CATEGORY_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
        />
        <Select
          value={editSecurityLevel} size="small" style={{ width: 90 }}
          onChange={(v) => { setEditSecurityLevel(v); markDirty(); }}
          options={SECURITY_OPTIONS.filter((o) => o.value !== 'all').map((o) => ({ value: o.value, label: o.label }))}
        />
        {selectedToolCount > 0 && <Tag color="green" style={{ fontSize: 11 }}>{selectedToolCount} 工具</Tag>}
      </div>
    </div>
  );

  // 指令区 —— 对齐 SkillStudioPanel.renderInstructions（无 Alert 噪声），控件换可编辑 TextArea
  const renderInstructions = () => (
    <div style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 6 }}>
        <CodeOutlined style={{ fontSize: 12 }} />
        <span>指令</span>
        <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>{editInstructions.split('\n').length} 行</Tag>
      </div>
      <TextArea
        value={editInstructions}
        onChange={(e) => { setEditInstructions(e.target.value); markDirty(); }}
        autoSize={{ minRows: 5, maxRows: 24 }}
        style={{
          fontFamily: 'Consolas, "Courier New", monospace', fontSize: 12, lineHeight: '18px',
          whiteSpace: 'pre-wrap', border: '1px solid #f0f0f0', borderRadius: 6,
        }}
      />
      {detectedVariables.length > 0 && (
        <div style={{ marginTop: 6, display: 'flex', gap: 4, flexWrap: 'wrap' }}>
          {detectedVariables.map((v) => (
            <Tag key={v} color="processing" style={{ fontSize: 10 }}>{'{'}{v}{'}'}</Tag>
          ))}
        </div>
      )}
    </div>
  );

  // 入参/出参 —— 保留编辑能力（运行时消费），折叠收起降低噪声，对齐生成面板"不默认展开"
  const renderInputsOutputs = () => (
    <div style={{ marginBottom: 12 }}>
      <div
        style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 6, cursor: 'pointer' }}
        onClick={() => setShowInputsOutputs(!showInputsOutputs)}
      >
        <ApiOutlined style={{ fontSize: 12 }} />
        <span>入参 / 出参</span>
        <span style={{ fontSize: 11, color: '#9ca3af', marginLeft: 'auto' }}>{showInputsOutputs ? '收起' : '展开'}</span>
      </div>
      {showInputsOutputs && (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>输入参数 Schema</Text>
            <TextArea
              value={editInputs}
              onChange={(e) => { setEditInputs(e.target.value); markDirty(); }}
              autoSize={{ minRows: 3, maxRows: 10 }}
              style={{ fontFamily: 'Consolas, "Courier New", monospace', fontSize: 12, marginTop: 2 }}
            />
          </div>
          <div>
            <Text type="secondary" style={{ fontSize: 11 }}>输出参数 Schema</Text>
            <TextArea
              value={editOutputs}
              onChange={(e) => { setEditOutputs(e.target.value); markDirty(); }}
              autoSize={{ minRows: 3, maxRows: 10 }}
              style={{ fontFamily: 'Consolas, "Courier New", monospace', fontSize: 12, marginTop: 2 }}
            />
          </div>
        </Space>
      )}
    </div>
  );

  // 绑定工具 —— 对齐生成面板 renderBindingTools 的分组卡风格，控件加 Switch 勾选 + MCP选择器
  const renderBindingTools = () => (
    <div style={{ marginBottom: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: '#374151', marginBottom: 6 }}>
        <ToolOutlined style={{ fontSize: 12 }} />
        <span>绑定工具</span>
        {selectedToolCount > 0 && <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>{selectedToolCount}</Tag>}
        <Button size="small" type="text" icon={<ApiOutlined />} onClick={() => setShowMcpSelector(true)} style={{ marginLeft: 'auto', fontSize: 11, color: '#6b7280' }}>
          添加
        </Button>
      </div>
      {bindingGroups.length === 0 ? (
        <div style={{ fontSize: 11, color: '#9ca3af', padding: '8px', textAlign: 'center', border: '1px dashed #e5e7eb', borderRadius: 6 }}>
          暂未绑定工具，点击"添加"选择 MCP
        </div>
      ) : (
        bindingGroups.map((group, gi) => (
          <div key={group.mcpName} style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: '8px 10px', background: '#fafafa', marginBottom: 6 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
              <span style={{ color: '#1677ff' }}>🔌</span>
              <Text strong style={{ fontSize: 12 }}>{group.mcpName}</Text>
              <Tag color="blue" style={{ fontSize: 10, margin: 0 }}>{group.tools.filter((t) => t.selected).length}/{group.tools.length}</Tag>
              <Button size="small" type="text" danger onClick={() => removeGroup(gi)} style={{ marginLeft: 'auto', fontSize: 11 }}>移除</Button>
            </div>
            {group.tools.length === 0 ? (
              <div style={{ color: '#999', fontSize: 11 }}>尚未选择工具</div>
            ) : (
              group.tools.map((tool, ti) => (
                <div key={tool.toolCode} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '3px 0', fontSize: 11 }}>
                  <Switch size="small" checked={tool.selected} onChange={() => toggleTool(gi, ti)} />
                  <Text code style={{ fontSize: 11 }}>{tool.toolCode}</Text>
                  {tool.toolName && <Text type="secondary" style={{ fontSize: 11 }}>{tool.toolName}</Text>}
                </div>
              ))
            )}
          </div>
        ))
      )}
    </div>
  );

  // 技能文件 —— 复刻生成面板 renderFileStructure，从 DB 拉取
  const renderFileStructure = () => {
    if (skillFiles.length === 0) return null;
    return (
      <div style={{ marginBottom: 12 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: '#374151' }}>
            <FileOutlined style={{ fontSize: 12 }} />
            <span>技能文件</span>
            <Tag color="default" style={{ fontSize: 10, margin: 0 }}>{skillFiles.length}</Tag>
          </div>
          <Button size="small" type="text" onClick={() => setExpandedFiles(!expandedFiles)} style={{ fontSize: 11, color: '#6b7280' }}>
            {expandedFiles ? '收起' : '展开'}
          </Button>
        </div>
        <div style={{ border: '1px solid #e8e8e8', borderRadius: 6, background: '#fafbfc', padding: 6 }}>
          {expandedFiles ? (
            <div style={{ maxHeight: 180, overflowY: 'auto' }}>
              {skillFiles.map((f) => (
                <div
                  key={f.path}
                  style={{ padding: '4px 8px', display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer', fontSize: 12, borderRadius: 4 }}
                  onMouseEnter={(e) => { (e.currentTarget as HTMLElement).style.background = '#e6f4ff'; }}
                  onMouseLeave={(e) => { (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
                  onClick={() => {
                    if (f.content) Modal.info({
                      title: f.name, width: 560,
                      content: <pre style={{ background: '#f5f5f5', padding: 14, borderRadius: 6, maxHeight: 400, overflow: 'auto', fontSize: 12, fontFamily: 'monospace', whiteSpace: 'pre-wrap', lineHeight: '18px' }}>{f.content}</pre>,
                    });
                  }}
                >
                  <FileOutlined style={{ color: f.name === 'SKILL.md' ? '#667eea' : '#1677ff', fontSize: 13 }} />
                  <span style={{ fontWeight: f.name === 'SKILL.md' ? 600 : 400, color: '#1f2937' }}>{f.name}</span>
                  <span style={{ fontSize: 10, color: '#9ca3af', marginLeft: 'auto', fontFamily: 'monospace' }}>{f.path}</span>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ padding: '4px 8px', fontSize: 12, color: '#6b7280', textAlign: 'center' }}>{skillFiles.length} 个文件</div>
          )}
        </div>
      </div>
    );
  };

  // 底部操作栏 —— 对齐生成面板 renderActionBar 的 flex 等分布局
  const renderActionBar = () => (
    <div style={{ borderTop: '1px solid #e8e8e8', padding: '12px 16px', background: '#fff', flexShrink: 0, display: 'flex', gap: 10 }}>
      <Button
        size="middle" icon={<SaveOutlined />} onClick={handleManualSave}
        loading={saving} disabled={!dirty} style={{ flex: 1 }}
      >
        保存
      </Button>
      <Button
        size="middle" type="primary" icon={<CheckCircleOutlined />} onClick={handleSubmitReview}
        loading={saving} disabled={dirty} style={{ flex: 1 }}
      >
        提交审核
      </Button>
    </div>
  );

  return (
    <div style={{
      width: 340, minWidth: 340, height: '100%',
      display: 'flex', flexDirection: 'column',
      background: '#fafbfc', borderLeft: '1px solid #e8e8e8',
      overflow: 'hidden', boxShadow: '-2px 0 8px rgba(0,0,0,0.04)',
    }}>
      {renderHeader()}
      <div style={{ flex: 1, overflowY: 'auto', padding: '10px 12px' }}>
        {renderSkillInfo()}
        {renderInstructions()}
        {renderInputsOutputs()}
        {renderBindingTools()}
        {renderFileStructure()}
      </div>
      {renderActionBar()}

      <McpSelectorModal
        open={showMcpSelector} mcpList={mcpList} loading={loadingMcp}
        existingBindings={bindingGroups.map((g) => g.mcpName)}
        onSelect={(mcp) => addMcpBinding(mcp)}
        onCancel={() => setShowMcpSelector(false)}
      />
    </div>
  );
};

interface McpSelectorModalProps {
  open: boolean;
  mcpList: McpServer[];
  loading: boolean;
  existingBindings: string[];
  onSelect: (mcp: McpServer) => void;
  onCancel: () => void;
}

const McpSelectorModal: React.FC<McpSelectorModalProps> = ({
  open, mcpList, loading, existingBindings, onSelect, onCancel,
}) => {
  const [keyword, setKeyword] = useState('');
  const filteredList = useMemo(() => {
    if (!keyword) return mcpList;
    const kw = keyword.toLowerCase();
    return mcpList.filter((m) =>
      m.mcpName.toLowerCase().includes(kw) ||
      m.mcpCode.toLowerCase().includes(kw) ||
      (m.description?.toLowerCase().includes(kw) ?? false));
  }, [mcpList, keyword]);

  if (!open) return null;
  return (
    <Modal title={<Space><ApiOutlined /><span>选择 MCP 服务</span></Space>} open={open} onCancel={onCancel} footer={null} width={520}>
      <Input.Search placeholder="搜索 MCP 服务名称或编码" allowClear value={keyword} onChange={(e) => setKeyword(e.target.value)} style={{ marginBottom: 12 }} />
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
                  display: 'flex', justifyContent: 'space-between', alignItems: 'center',
                  padding: '10px 12px', border: '1px solid #f0f0f0', borderRadius: 6, marginBottom: 6,
                  cursor: isBound ? 'not-allowed' : 'pointer', background: isBound ? '#f5f5f5' : '#fff', transition: 'all .15s',
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
                {isBound ? <Tag color="default">已绑定</Tag> : <Button size="small" type="primary">选择</Button>}
              </div>
            );
          })}
        </div>
      )}
    </Modal>
  );
};

export default SkillEditPanel;
