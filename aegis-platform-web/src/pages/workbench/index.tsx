/**
 * @file 工作台（容器层）
 * @description 对齐产品原型。拼装 AgentDrawer + HistoryDrawer + ChatArea +
 *              EnhancedMessageInput + SkillStudioPanel/SkillEditPanel。
 *              业务逻辑已拆分到 hooks/ 与 components/。
 * @author wang.zhen
 * @since 1.0.0
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { App, Avatar, Button, Modal, Tag } from 'antd';
import { PlusOutlined, RobotOutlined, SwapOutlined, HistoryOutlined, CompressOutlined } from '@ant-design/icons';

// ---- API ----
import { getSessionList, getMessages, getAvailableResources } from '@/api/session';
import { getAvailableSkills, debugSkill, submitSkillForReview, getSkillFiles } from '@/api/skill';
import { skillApi, extractList } from '@/api/resource';

// ---- Types & Enum ----
import type { Message, AgentSkill } from '@/types/session';
import type { Skill } from '@/types/resource';
import { MessageRole } from '@/types/enum';
import type { SkillType } from '@/pages/resource/skill/constants';
import type { AttachmentRef, SkillRef } from '@/api/session';

// ---- UI ----

// ---- Hooks ----
import { useAuthStore } from '@/stores/authStore';
import { useAgentSelection } from './hooks/useAgentSelection';
import { useSessionManagement } from './hooks/useSessionManagement';
import { useWorkbenchChat } from './hooks/useWorkbenchChat';

// ---- Components ----
import { ChatArea } from './components/ChatArea';
import { AgentDrawer } from './components/AgentDrawer';
import { HistoryDrawer } from './components/HistoryDrawer';
import { ResourcePanel } from './components/ResourcePanel';
import { UploadPanel } from './components/UploadPanel';
import { SkillPanel } from './components/SkillPanel';
import { EnhancedMessageInput } from '@/components/chat/EnhancedMessageInput';
import { SkillStudioPanel, type SkillDraft, type SkillFileItem } from './components/SkillStudioPanel';
import { SkillEditPanel } from './components/SkillEditPanel';
import { collectEditablePayload, type SkillEditableFields } from '@/pages/resource/skill/skill-field-contract';

// ---- Utils ----
import { ROUTE_PATH } from '@/utils/constants';
import { markdownStyles } from './utils';
import type { CollapsePolicy } from '@/types/collapsePolicy';
import { DEFAULT_COLLAPSE_POLICY } from '@/types/collapsePolicy';

/** 空技能草稿工厂 */
const createEmptySkillDraft = (): SkillDraft => ({
  skillName: '', skillCode: '', skillType: 'ATOMIC' as SkillType, category: '',
  description: '', instructions: '', securityLevel: 'L1',
  tags: [], bindingTools: '',
});

/** 技能模板按钮 */
interface TemplateButtonProps { icon: string; title: string; desc: string; prompt: string; onClick: (text: string) => void; }
const TemplateButton: React.FC<TemplateButtonProps> = ({ icon, title, desc, prompt, onClick }) => (
  <button
    type="button" onClick={() => onClick(prompt)}
    style={{ padding: '12px 14px', border: '1px solid #e5e7eb', borderRadius: 8, background: '#fff', textAlign: 'left', cursor: 'pointer', transition: 'all 0.15s', display: 'flex', flexDirection: 'column', gap: 4 }}
    onMouseEnter={(e) => { e.currentTarget.style.borderColor = '#6366f1'; e.currentTarget.style.boxShadow = '0 2px 8px rgba(99,102,241,0.15)'; }}
    onMouseLeave={(e) => { e.currentTarget.style.borderColor = '#e5e7eb'; e.currentTarget.style.boxShadow = 'none'; }}
  >
    <div style={{ fontSize: 20 }}>{icon}</div>
    <div style={{ fontSize: 13, fontWeight: 600, color: '#111827' }}>{title}</div>
    <div style={{ fontSize: 11, color: '#6b7280' }}>{desc}</div>
  </button>
);

const Workbench: React.FC = () => {
  const { user } = useAuthStore();
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  // ---------- 三个核心 Hook ----------
  const agentSel = useAgentSelection();
  const sessionMgr = useSessionManagement({ currentAgentId: agentSel.currentAgentId });

  // 技能草稿相关状态
  const [skillCreatorMode, setSkillCreatorMode] = useState(false);
  const [draftSkillId, setDraftSkillId] = useState<string | null>(null);
  const [skillCreatorStage, setSkillCreatorStage] = useState<{ phase: string; description: string; progress: number; ts: number } | null>(null);
  const [skillDebugResult, setSkillDebugResult] = useState<{ success: boolean; message?: string; ts: number; steps?: Array<{ name: string; status?: string; detail?: string }>; output?: string; findings?: Array<{ level: string; message: string }> } | null>(null);
  const [skillFiles, setSkillFiles] = useState<SkillFileItem[]>([]);
  const [skillDraft, setSkillDraft] = useState<SkillDraft>(createEmptySkillDraft());

  const chat = useWorkbenchChat({
    currentAgentId: agentSel.currentAgentId,
    currentSessionId: sessionMgr.currentSessionId,
    setCurrentSessionId: sessionMgr.setCurrentSessionId,
    skillCreatorMode, setSkillCreatorMode, createEmptySkillDraft,
    setDraftSkillId, setSkillCreatorStage, setSkillDebugResult, setSkillFiles, setSkillDraft,
    getSelectedKbIds: () => selectedKbIds,
    getSelectedMcpIds: () => selectedMcpIds,
    getSelectedAttachments: () => selectedAttachments,
    getSelectedSkills: () => selectedSkills,
    getUserId: () => user?.id != null ? String(user.id) : undefined,
    getTenantId: () => user?.tenantId != null ? String(user.tenantId) : undefined,
    message,
    loadSessions: sessionMgr.loadSessions,
  });

  // 页面刷新后从 DB 恢复技能文件树（SSE 事件只在创建瞬间下发，刷新即丢失）
  useEffect(() => {
    if (!draftSkillId || skillFiles.length > 0) return;
    let cancelled = false;
    getSkillFiles(draftSkillId).then((records) => {
      if (cancelled || !records || records.length === 0) return;
      const mapped: SkillFileItem[] = records.map((r) => ({
        name: r.fileName,
        type: 'file' as const,
        path: r.filePath,
        content: r.content,
      }));
      setSkillFiles(mapped);
    }).catch(() => { /* 静默：DB 无文件时不阻塞 */ });
    return () => { cancelled = true; };
  }, [draftSkillId, skillFiles.length]);

  // ---------- 资源引用状态 ----------
  const [selectedKbIds, setSelectedKbIds] = useState<string[]>([]);
  const [selectedMcpIds, setSelectedMcpIds] = useState<string[]>([]);
  const [selectedAttachments, setSelectedAttachments] = useState<AttachmentRef[]>([]);
  const [selectedSkills, setSelectedSkills] = useState<SkillRef[]>([]);
  const [availableResources, setAvailableResources] = useState<{ kbs: any[]; mcps: any[]; totalKbCount?: number; totalMcpCount?: number }>({ kbs: [], mcps: [] });
  const [skills, setSkills] = useState<AgentSkill[]>([]);
  const [skillsLoading, setSkillsLoading] = useState(false);

  // ---------- 浮层状态 ----------
  const [agentDrawerOpen, setAgentDrawerOpen] = useState(false);
  const [historyDrawerOpen, setHistoryDrawerOpen] = useState(false);
  const [showKbPanel, setShowKbPanel] = useState(false);
  const [showUploadPanel, setShowUploadPanel] = useState(false);
  const [showSkillPanel, setShowSkillPanel] = useState(false);

  // ---------- SKILL 编辑模式 ----------
  const [skillEditMode, setSkillEditMode] = useState(false);
  const [editingSkill, setEditingSkill] = useState<Skill | null>(null);
  const [skillEditLoading, setSkillEditLoading] = useState(false);
  const autoSentRef = useRef(false);

  // ---------- 辅助函数 ----------
  const resetSkillDraft = useCallback(() => {
    setSkillDraft(createEmptySkillDraft()); setDraftSkillId(null);
    setSkillFiles([]); setSkillCreatorStage(null); setSkillDebugResult(null);
  }, []);

  const handleAIDirective = useCallback((msg: string) => chat.sendMessage(msg), [chat]);
  const handleSend = useCallback((text: string) => chat.sendMessage(text), [chat.sendMessage]);
  const handleStop = useCallback(async () => { await chat.stopStream(); }, [chat]);
  const handleClearAll = useCallback(() => {
    setSelectedKbIds([]); setSelectedMcpIds([]); setSelectedAttachments([]); setSelectedSkills([]);
  }, [setSelectedKbIds, setSelectedMcpIds, setSelectedAttachments, setSelectedSkills]);
  const handleNewTask = useCallback(() => { sessionMgr.resetSession(); chat.resetChat(); }, [sessionMgr, chat]);

  /** 切换智能体 */
  const handleAgentSwitch = useCallback((agentId: string) => {
    const agent = agentSel.agents.find((a) => a.id === agentId);
    agentSel.setCurrentAgentId(agentId);
    sessionMgr.resetSession();
    chat.resetChat();
    message.success(`已切换到 ${agent?.agentName ?? '智能体'}，整个任务过程将基于此智能体执行`);
    setTimeout(() => {
      getSessionList({ page: 1, size: 1, agentId })
        .then(res => {
          const sessions = res.sessions || [];
          if (sessions.length > 0) {
            const sid = sessions[0].sessionId || sessions[0].id;
            if (sid) { sessionMgr.setCurrentSessionId(sid); return getMessages(sid); }
          }
          return null;
        })
        .then(msgs => { if (msgs && msgs.length > 0) chat.setMessages(msgs); })
        .catch(() => { /* 静默失败 */ });
    }, 100);
  }, [agentSel, sessionMgr, chat, message]);

  const handleDeleteMessage = useCallback(async (sessionId: string, msgId: string) => {
    const { deleteMessage: deleteMessageApi } = await import('@/api/session');
    // 雪花ID（纯数字字符串，来自后端持久化）→ 调后端删除；
    // 临时ID（u/a 前缀，实时未刷新）→ 后端无此记录，仅本地移除。
    const isPersisted = /^\d+$/.test(msgId);
    try {
      if (isPersisted) {
        await deleteMessageApi(sessionId, msgId);
        message.success('消息已删除');
      } else {
        message.info('该消息尚未持久化，已从本地移除（刷新后会恢复）');
      }
    } catch (e) {
      message.error('删除失败: ' + (e as Error).message);
      return;
    }
    chat.setMessages(prev => prev.filter((m) => m.id !== msgId));
  }, [chat, message]);

  const handleResumeFromConflict = useCallback(async () => {
    chat.setMessages((prev) => prev.filter((m) => !m.isError));
  }, [chat]);

  /** 任务 8：重新生成 AI 消息 —— 委托给 chat.regenerate */
  const handleRegenerate = useCallback((messageId: string) => {
    chat.regenerate(messageId);
  }, [chat]);

  /** 任务 8：编辑用户消息 —— 当前选中的附件/技能引用由 hook 内 getSelected* 注入（验收 #5） */
  const handleEditMessage = useCallback((messageId: string, newText: string) => {
    chat.editMessage(messageId, newText);
  }, [chat]);

  // ---------- useEffects ----------
  useEffect(() => {
    if (!agentSel.currentAgentId) return;
    getAvailableResources(agentSel.currentAgentId)
      .then((res) => setAvailableResources({ kbs: res.kbs || [], mcps: res.mcps || [], totalKbCount: res.totalKbCount, totalMcpCount: res.totalMcpCount }))
      .catch((err) => console.error('加载可用资源失败:', err));
  }, [agentSel.currentAgentId]);

  useEffect(() => {
    if (!agentSel.currentAgentId) return;
    setSkillsLoading(true);
    getAvailableSkills(undefined, agentSel.currentAgentId)
      .then((options) => setSkills(options.map(o => ({ skillCode: o.skillCode, skillName: o.skillName, description: o.description, category: o.category }))))
      .catch(() => setSkills([]))
      .finally(() => setSkillsLoading(false));
  }, [agentSel.currentAgentId]);

  // URL 参数：agentId 指定初始智能体（如智能体列表「开始对话」入口）
  useEffect(() => {
    const id = searchParams.get('agentId');
    if (!id || agentSel.agentsLoading) return;
    if (agentSel.agents.some((a) => a.id === id)) {
      agentSel.setCurrentAgentId(id);
    }
  }, [searchParams, agentSel.agents, agentSel.agentsLoading, agentSel.setCurrentAgentId]);

  // URL 参数：mode=skill_creator 自动激活
  useEffect(() => {
    const mode = searchParams.get('mode');
    const skillParam = searchParams.get('skill');
    const initialPrompt = searchParams.get('prompt');
    const isSkillCreator = mode === 'skill_creator' || !!skillParam;
    if (!isSkillCreator || autoSentRef.current || agentSel.agentsLoading || !agentSel.currentAgentId) return;
    autoSentRef.current = true;
    setSkillCreatorMode(true); resetSkillDraft();

    const titleMsg: Message = skillParam
      ? { id: `skill-creator-activated-${Date.now()}`, sessionId: '', role: MessageRole.SYSTEM, content: '🔧 技能编辑模式已激活：输入你的修改需求，对话将辅助你调试和交付技能', createdAt: new Date().toISOString() }
      : { id: `skill-creator-activated-${Date.now()}`, sessionId: '', role: MessageRole.SYSTEM,
          content: initialPrompt ? '🔧 技能创建模式已激活：已为你准备好初始需求，开始对话即可创建技能' : '🔧 技能创建模式已激活：在下方输入你想创建的技能描述，对话将辅助你完成技能开发', createdAt: new Date().toISOString() };
    chat.setMessages([titleMsg]);
    message.success(skillParam ? '已进入技能编辑模式' : '已进入技能创建模式');
    if (initialPrompt) setTimeout(() => handleSend(initialPrompt), 300);
  }, [searchParams, agentSel.agentsLoading, agentSel.currentAgentId]);

  // URL 参数：mode=edit 加载 SKILL 详情
  useEffect(() => {
    const mode = searchParams.get('mode');
    const skillIdParam = searchParams.get('skillId');
    const skillCodeParam = searchParams.get('skillCode');
    if (mode !== 'edit' || (!skillIdParam && !skillCodeParam)) return;
    const loadSkillForEdit = async () => {
      setSkillEditLoading(true); setSkillEditMode(true);
      try {
        let skill: Skill | null = null;
        if (skillIdParam) { skill = await skillApi.detail(skillIdParam); }
        else if (skillCodeParam) {
          const list = extractList(await skillApi.list({ keyword: skillCodeParam, page: 1, size: 10 }));
          const found = list.find((s: Skill) => s.skillCode === skillCodeParam);
          if (found && found.id != null) skill = await skillApi.detail(found.id);
        }
        if (skill) {
          setEditingSkill(skill);
          chat.setMessages([{ id: `skill-edit-activated-${Date.now()}`, sessionId: '', role: MessageRole.SYSTEM, content: `🎯 SKILL 编辑模式已激活：正在编辑「${skill.skillName}」(${skill.skillCode})。`, createdAt: new Date().toISOString() }]);
          message.success(`已进入 SKILL 编辑模式：${skill.skillName}`);
        } else { message.error('未找到指定的 SKILL'); setSkillEditMode(false); }
      } catch (error) { console.error('加载 SKILL 详情失败:', error); message.error('加载 SKILL 详情失败'); setSkillEditMode(false); }
      finally { setSkillEditLoading(false); }
    };
    loadSkillForEdit();
  }, [searchParams]);

  useEffect(() => () => { chat.abortRef.current?.abort(); }, [chat.abortRef]);

  // ---------- 派生值 ----------
  // 折叠策略（精简模式/thinkingStyle/collapsedTools），默认 collapsedPreview/all
  const [collapsePolicy, setCollapsePolicy] = useState<CollapsePolicy>(DEFAULT_COLLAPSE_POLICY);

  /** 切换精简模式 */
  const toggleCompact = useCallback(() => {
    setCollapsePolicy((p) => ({ ...p, compact: !p.compact }));
  }, []);

  const currentAgent = agentSel.currentAgent;
  const isUniversal = agentSel.isUniversal;
  const bindingCount = !isUniversal && currentAgent?.bindings ? currentAgent.bindings.length : 0;
  const hasLongTermMemory = currentAgent?.memoryStrategy?.includes('LONG') ?? false;

  const skillCreateEmpty = chat.messages.length === 0 && skillCreatorMode && !skillEditMode;
  const normalEmpty = chat.messages.length === 0 && !skillCreatorMode;
  const wideLayout = skillCreatorMode && !skillEditMode;

  // ---------- 渲染 ----------
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', background: 'var(--color-bg-layout)', borderRadius: 8, overflow: 'hidden' }}>
      {/* 顶栏 */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 20px', background: 'var(--color-bg-topbar)', borderBottom: '1px solid var(--color-border-secondary)', flexShrink: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
          <Avatar size={32} icon={<RobotOutlined />} style={{ background: 'linear-gradient(135deg, #4f46e5, #7c3aed)', minWidth: 32 }} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 600, fontSize: 14, color: 'var(--color-text-primary)' }}>{currentAgent?.agentName ?? '通用助手'}</div>
            {currentAgent && (
              <div style={{ fontSize: 11, color: 'var(--color-text-tertiary)' }}>
                {isUniversal ? '通用智能体' : '应用智能体'} · {bindingCount > 0 ? `已绑定 ${bindingCount} 项资源` : '无绑定资源'}
                {hasLongTermMemory && ' · 🧠 长期记忆'}
              </div>
            )}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
          {skillCreatorMode && (
            <Tag color="purple" style={{ margin: 0 }} closable onClose={() => { setSkillCreatorMode(false); resetSkillDraft(); }}>🔧 技能创建模式</Tag>
          )}
          {skillEditMode && editingSkill && (
            <Tag color="geekblue" style={{ margin: 0, cursor: 'pointer' }} closable onClose={() => { setSkillEditMode(false); setEditingSkill(null); }}>🎯 SKILL 编辑：{editingSkill.skillName}</Tag>
          )}
          <Button size="small" type="text" icon={<SwapOutlined />} onClick={() => setAgentDrawerOpen(true)} style={{ fontSize: 13 }}>切换</Button>
          <Button size="small" type="text" icon={<PlusOutlined />} onClick={handleNewTask} style={{ fontSize: 13 }}>新建</Button>
          <Button size="small" type="text" icon={<HistoryOutlined />} onClick={() => setHistoryDrawerOpen(true)} style={{ fontSize: 13 }}>历史</Button>
          <Button size="small" type={collapsePolicy.compact ? 'primary' : 'text'} icon={<CompressOutlined />} onClick={toggleCompact} style={{ fontSize: 13 }} title="切换精简/完整视图">精简</Button>
        </div>
      </div>

      {/* 内容区 */}
      <div style={{ flex: 1, display: 'flex', minHeight: 0, overflow: 'hidden', flexDirection: wideLayout ? 'row' : 'column' }}>
        <div id="chat-container" style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, background: 'var(--color-bg-container)', width: '100%', boxShadow: 'var(--shadow-sm)', position: 'relative', overflow: 'hidden' }}>
          {/* 消息列表区 */}
          <div style={{ flex: 1, overflowY: 'auto', padding: wideLayout ? '64px 24px' : '64px 160px', maxWidth: wideLayout ? 'none' : 1400, margin: '0 auto', width: '100%', boxSizing: 'border-box' }}>
            {skillCreateEmpty && (
              <div style={{ textAlign: 'center', paddingTop: 40, color: '#6b7280' }}>
                <div style={{ fontSize: 48, marginBottom: 8 }}>🛠️</div>
                <div style={{ fontSize: 18, fontWeight: 600, color: '#111827', marginBottom: 6 }}>技能创建模式已激活</div>
                <div style={{ fontSize: 13, color: '#6b7280', marginBottom: 20, maxWidth: 480, margin: '0 auto 20px' }}>
                  用自然语言描述你想要的技能，AI 将自动完成配置、绑定工具、调试并交付。
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 10, maxWidth: 640, margin: '0 auto 20px', padding: '0 20px' }}>
                  <TemplateButton icon="🔍" title="数据查询" desc="根据条件查询数据库" prompt="帮我创建一个数据查询技能，能够根据用户输入的条件从数据库中查询相关信息，并返回格式化的结果。" onClick={handleSend} />
                  <TemplateButton icon="📊" title="报告生成" desc="自动生成分析报告" prompt="帮我创建一个报告生成技能，能够根据输入的数据自动生成格式化的分析报告，包含图表和关键洞察。" onClick={handleSend} />
                  <TemplateButton icon="💬" title="客服问答" desc="回答产品常见问题" prompt="帮我创建一个客服问答技能，能够回答用户关于产品的常见问题，并在必要时转接人工。" onClick={handleSend} />
                  <TemplateButton icon="✍️" title="内容创作" desc="撰写文章/文案" prompt="帮我创建一个内容创作技能，能够根据给定的主题和要求撰写文章、文案或社交媒体帖子。" onClick={handleSend} />
                </div>
                <div style={{ fontSize: 12, color: '#9ca3af', marginTop: 12 }}>💡 提示：也可以直接在下方输入框中描述你的自定义需求</div>
              </div>
            )}
            {normalEmpty && (
              <div style={{ textAlign: 'center', paddingTop: 80, color: '#9ca3af' }}>
                <RobotOutlined style={{ fontSize: 48, marginBottom: 16, color: '#d1d5db' }} />
                <div style={{ fontSize: 16, fontWeight: 600, color: '#374151', marginBottom: 8 }}>你好！我是 {currentAgent?.agentName ?? '通用助手'}</div>
                <div style={{ fontSize: 13, color: '#9ca3af' }}>{currentAgent?.description ?? '请问有什么可以帮你？'}</div>
              </div>
            )}
            {chat.messages.length > 0 && (
              <ChatArea
                messages={chat.messages} streaming={chat.streaming} markdownStyles={markdownStyles}
                policy={collapsePolicy}
                userNickname={user?.nickname} agentName={agentSel.currentAgent?.agentName}
                onDeleteMessage={handleDeleteMessage} onApproveHitl={chat.approveHitl} onRejectHitl={chat.rejectHitl}
                onResumeFromConflict={handleResumeFromConflict}
                onRegenerate={handleRegenerate} onEditMessage={handleEditMessage}
              />
            )}
          </div>

          {/* 输入区 */}
          <div style={{ flexShrink: 0, background: 'var(--color-bg-input)', borderTop: '1px solid var(--color-border-secondary)', padding: wideLayout ? '40px 24px' : '40px 160px', maxWidth: wideLayout ? 'none' : 1400, margin: '0 auto', width: '100%', boxSizing: 'border-box' }}>
            {(selectedKbIds.length > 0 || selectedMcpIds.length > 0 || selectedAttachments.length > 0 || selectedSkills.length > 0) && (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 8, alignItems: 'center' }}>
                {selectedKbIds.map(id => {
                  const kb = (availableResources.kbs || []).find((k: any) => k.id === id);
                  return <Tag key={`kb-${id}`} closable onClose={() => setSelectedKbIds(selectedKbIds.filter(x => x !== id))} color="blue" style={{ margin: 0, fontSize: 11 }}>📚 {kb?.name || `知识库${id}`}</Tag>;
                })}
                {selectedMcpIds.map(id => {
                  const mcp = (availableResources.mcps || []).find((m: any) => m.id === id);
                  return <Tag key={`mcp-${id}`} closable onClose={() => setSelectedMcpIds(selectedMcpIds.filter(x => x !== id))} color="cyan" style={{ margin: 0, fontSize: 11 }}>🔌 {mcp?.name || `MCP${id}`}</Tag>;
                })}
                {selectedAttachments.map(att => (
                  <Tag key={att.id || att.fileName} closable onClose={() => setSelectedAttachments(prev => prev.filter(a => a.id !== att.id))} color="purple" style={{ margin: 0, fontSize: 11 }}>📎 {att.fileName || att.name}</Tag>
                ))}
                {selectedSkills.map(sk => (
                  <Tag key={sk.skillCode} closable onClose={() => setSelectedSkills(prev => prev.filter(s => s.skillCode !== sk.skillCode))} color="gold" style={{ margin: 0, fontSize: 11 }}>⚡ {sk.skillCode}</Tag>
                ))}
              </div>
            )}
            <EnhancedMessageInput
              onSend={handleSend} onStop={handleStop} onClear={handleClearAll}
              onResourceClick={() => setShowKbPanel(true)}
              onAttachmentClick={() => setShowUploadPanel(true)}
              onSkillClick={() => setShowSkillPanel(true)}
              loading={chat.streaming}
              placeholder={skillCreatorMode ? '输入技能创建需求，AI将辅助你完成开发...' : '输入消息... (Enter 发送)'}
            />
          </div>
        </div>

        {/* 技能工作台 */}
        {skillCreatorMode && !skillEditMode && (
          <SkillStudioPanel
            draftSkillId={draftSkillId} draft={skillDraft} files={skillFiles} stage={skillCreatorStage} debugResult={skillDebugResult}
            onAIDirective={handleAIDirective}
            onDebug={async () => {
              if (!draftSkillId) return;
              try {
                const data = await debugSkill(draftSkillId, {});
                setSkillDebugResult({ success: Boolean(data.success), message: data.message ?? (data.success ? '调试成功' : '调试失败'), steps: data.steps, output: data.output, findings: data.findings, ts: Date.now() });
              } catch { setSkillDebugResult({ success: false, message: '调试请求失败', ts: Date.now() }); }
            }}
            onSave={async () => {
              if (!draftSkillId) return;
              try {
                const fields: SkillEditableFields = {
                  skillName: skillDraft.skillName,
                  category: skillDraft.category,
                  description: skillDraft.description,
                  instructions: skillDraft.instructions,
                  skillType: skillDraft.skillType,
                  securityLevel: skillDraft.securityLevel,
                  tags: skillDraft.tags ?? [],
                  bindingTools: skillDraft.bindingTools ?? '[]',
                  inputs: typeof skillDraft.inputs === 'string' ? skillDraft.inputs : JSON.stringify(skillDraft.inputs ?? {}),
                  outputs: typeof skillDraft.outputs === 'string' ? skillDraft.outputs : JSON.stringify(skillDraft.outputs ?? {}),
                };
                const payload = collectEditablePayload(fields);
                await skillApi.update(String(draftSkillId), payload);
                message.success('技能已保存');
                Modal.confirm({
                  title: '技能已保存',
                  content: '技能草稿已成功保存到「我的技能」。是否立即前往查看？',
                  okText: '前往我的技能',
                  cancelText: '继续编辑',
                  onOk: () => { setSkillCreatorMode(false); resetSkillDraft(); navigate(ROUTE_PATH.RESOURCE_SKILL); },
                });
              } catch { message.error('保存失败'); }
            }}
            onSubmitted={async (sid: string) => {
              try {
                const result = await submitSkillForReview(sid);
                if (result.success) {
                  message.success(`技能 #${sid} 已提交审核`);
                  Modal.confirm({ title: '技能已提交审核', content: '技能已成功提交审核。审核通过后将出现在技能市场。是否退出创建模式？', okText: '退出创建', cancelText: '继续编辑', onOk: () => { setSkillCreatorMode(false); resetSkillDraft(); navigate(ROUTE_PATH.RESOURCE_SKILL); } });
                } else { message.error(result.message ?? '提交审核失败'); }
              } catch { message.error('提交审核请求失败'); }
            }}
            onClose={() => { setSkillCreatorMode(false); resetSkillDraft(); message.info('已退出技能创建模式'); }}
            onTagsChange={(tags) => setSkillDraft((prev) => ({ ...prev, tags }))}
            streaming={chat.streaming}
          />
        )}
      </div>

      {/* SKILL 编辑面板 */}
      {skillEditMode && skillEditLoading && (
        <div style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: 680, background: '#fff', borderLeft: '1px solid #f0f0f0', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100 }}>
          <div style={{ textAlign: 'center', color: '#999' }}><div style={{ fontSize: 24, marginBottom: 8 }}>⏳</div><div>加载 SKILL 详情中...</div></div>
        </div>
      )}
      {skillEditMode && editingSkill && !skillEditLoading && (
        <SkillEditPanel skill={editingSkill} onClose={() => { setSkillEditMode(false); setEditingSkill(null); }}
          onSaved={(updated) => { setEditingSkill(updated); message.success('SKILL 已保存'); }}
          onSubmitted={() => { setSkillEditMode(false); setEditingSkill(null); message.success('SKILL 已提交审核'); }}
          getContainer={() => document.getElementById('chat-container') || document.body}
        />
      )}

      {/* 抽屉 */}
      <AgentDrawer open={agentDrawerOpen} agents={agentSel.agents} agentsLoading={agentSel.agentsLoading} currentAgentId={agentSel.currentAgentId} onClose={() => setAgentDrawerOpen(false)} onSelect={handleAgentSwitch} />
      <HistoryDrawer
        open={historyDrawerOpen} sessions={sessionMgr.sessions} currentSessionId={sessionMgr.currentSessionId} streaming={chat.streaming}
        onClose={() => setHistoryDrawerOpen(false)}
        onNewTask={handleNewTask}
        onSwitch={async (sid) => { const msgs = await sessionMgr.switchSession(sid); chat.setMessages(msgs); }}
        onDeleteSession={sessionMgr.deleteSessionItem} loadSessions={sessionMgr.loadSessions}
        onCurrentSessionDeleted={() => { sessionMgr.setCurrentSessionId(undefined); chat.setMessages([]); }}
        abortStream={async () => { await chat.stopStream(); chat.setStreaming(false); }}
      />

      {/* Modal 面板 */}
      <ResourcePanel open={showKbPanel} onCancel={() => setShowKbPanel(false)} agentId={agentSel.currentAgentId}
        selectedKbIds={selectedKbIds} selectedMcpIds={selectedMcpIds} disabled={chat.streaming}
        onChange={(kbIds, mcpIds) => { setSelectedKbIds(kbIds); setSelectedMcpIds(mcpIds); }} />
      <UploadPanel open={showUploadPanel} onClose={() => setShowUploadPanel(false)} selected={selectedAttachments} onChange={setSelectedAttachments} />
      <SkillPanel open={showSkillPanel} onClose={() => setShowSkillPanel(false)} skills={skills} skillsLoading={skillsLoading} selected={selectedSkills} onChange={setSelectedSkills} />
    </div>
  );
};

export default Workbench;
