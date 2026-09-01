/**
 * @file 会话类型定义
 * @description 会话、消息、工具调用、HITL 审批、SSE 事件等类型
 * @author wang.zhen
 * @since 1.0.0
 */
import type { HitlStatus, MessageRole, TaskStatus } from './enum';
import type { TurnEvent, TurnMeta, MessageContext } from './turn';

/** 会话 */
export interface Session {
  /** 数据库自增 ID */
  id: string;
  /** 会话 UUID（用于 API 调用） */
  sessionId: string;
  /** 会话标题 */
  title?: string;
  /** 智能体 ID（雪花ID，字符串形式） */
  agentId: string;
  /** 智能体名称 */
  agentName?: string;
  /** 租户 ID（雪花ID，前端一律 string） */
  tenantId: string;
  /** 用户 ID（雪花ID，前端一律 string） */
  userId: string;
  /** 任务状态 */
  status: TaskStatus;
  /** 消息数 */
  messageCount?: number;
  /** 输入 Token 累计 */
  inputTokens?: number;
  /** 输出 Token 累计 */
  outputTokens?: number;
  /** 创建时间 */
  createdAt?: string;
  /** 最近活跃时间 */
  lastActiveAt?: string;
  /** 最近活跃时间（后端字段名兼容） */
  lastActiveTime?: string;
}

/** 消息 */
export interface Message {
  /** 消息 ID */
  id: string;
  /** 会话 ID */
  sessionId: string;
  /** 角色 */
  role: MessageRole;
  /** 文本内容 */
  content: string;
  /** 思考链/推理过程 */
  reasoning?: string;
  /** 思考链是否已折叠 */
  reasoningCollapsed?: boolean;
  /** 工具调用列表 */
  toolCalls?: ToolCall[];
  /** 知识库引用列表 */
  kbReferences?: KbReference[];
  /** 是否为错误消息（沙箱异常/工具执行失败等） */
  isError?: boolean;
  /** 错误码（isError 时用于渲染特定操作按钮，如 CONFLICT 显示"中断并重试"） */
  errorCode?: string;
  /** 错误关联的会话ID（CONFLICT 时用于调用中断接口后重试） */
  errorSessionId?: string;
  /** 错误是否可恢复（后端 CONFLICT 时返回 true，前端据此渲染操作按钮） */
  recoverable?: boolean;
  /** HITL 审批信息 */
  hitl?: HitlApproval;
  /** 输入 Token */
  inputTokens?: number;
  /** 输出 Token */
  outputTokens?: number;
  /** 创建时间 */
  createdAt?: string;
  // ===== 轮次执行流（4.0 新增，统一思考/工具/回答时序） =====
  /** 轮次执行事件流（按 timestamp 升序）；为空时降级用 reasoning/toolCalls 旧字段 */
  events?: TurnEvent[];
  /** 轮次元信息（模型/耗时/Token/步数/完成态） */
  turnMeta?: TurnMeta;
  /** 轮次折叠状态（轮次级；null/undefined 视为展开） */
  turnCollapsed?: boolean;
  /** 用户消息发送时的资源引用快照（历史回显"当时引用了什么"） */
  context?: MessageContext;
}

/** 工具调用 */
export interface ToolCall {
  /** 调用 ID */
  id: string;
  /** 工具名称 */
  name: string;
  /** 调用参数 */
  arguments?: Record<string, unknown>;
  /** 调用结果 */
  result?: unknown;
  /** 调用状态：running / success / failed */
  status: 'running' | 'success' | 'failed';
  /** 耗时（毫秒） */
  durationMs?: number;
  /** 错误信息 */
  error?: string;
}

/** 知识库引用 */
export interface KbReference {
  /** 引用 ID */
  id: string;
  /** 知识库 ID（雪花ID，前端一律 string） */
  knowledgeBaseId: string;
  /** 知识库名称 */
  knowledgeBaseName?: string;
  /** 文档名 */
  documentName?: string;
  /** 命中片段 */
  snippet?: string;
  /** 相似度得分（0~1） */
  score?: number;
  /** 来源 URL */
  sourceUrl?: string;
}

/** HITL 审批 */
export interface HitlApproval {
  /** 审批 ID */
  id: string;
  /** 审批状态 */
  status: HitlStatus;
  /** 审批内容摘要 */
  summary: string;
  /** 审批详情（待审批操作的完整参数） */
  payload?: Record<string, unknown>;
  /** 审批人 ID（雪花ID，前端一律 string） */
  approverId?: string;
  /** 审批意见 */
  comment?: string;
  /** 审批时间 */
  approvedAt?: string;
  /** 是否自动放行（低风险工具无需审批） */
  autoApproved?: boolean;
}

/** @SKILL 结构化引用（与后端 SkillRef 对齐） */
export interface SkillRef {
  /** 技能编码 */
  skillCode: string;
  /** 版本号（可选，缺省取激活版本） */
  version?: string;
}

/** @SKILL 选择器可选项（与后端 SkillChatController.SkillOption 对齐） */
export interface SkillOption {
  skillCode: string;
  skillName: string;
  description?: string;
  category?: string;
}

/** 技能激活事件载荷（skill.activated） */
export interface SkillActivatedPayload {
  skills: string[];
  timestamp?: number;
}

/** 技能驳回事件载荷（skill.rejected） */
export interface SkillRejectedPayload {
  skills: string[];
  reason: string;
  timestamp?: number;
}

/** SSE 事件类型（与后端 AgentEvent 事件名对齐） */
export type SseEventType =
  | 'agent_start'
  | 'reasoning.delta'
  | 'text.delta'
  | 'tool.call'
  | 'tool.result'
  | 'kb.reference'
  | 'hitl.request'
  | 'task.status'
  | 'error'
  | 'done'
  | 'message.delta'
  | 'skill.activated'
  | 'skill.rejected'
  | 'skill.creator.stage'
  | 'skill.creator.debug'
  | 'skill.creator.package'
  | 'skill.draft.created'
  | 'skill.draft.updated'
  | 'skill.debug.result'
  | 'skill.package.result';

/** SSE 事件 */
export interface SseEvent<T = unknown> {
  /** 事件类型 */
  event: SseEventType;
  /** 事件数据 */
  data: T;
  /** 事件 ID */
  id?: string;
  /** 重试间隔（毫秒） */
  retry?: number;
}

/**
 * 智能体执行事件（与后端 AgentEvent 对齐）。
 *
 * 后端事件类型：agent_start / reasoning.delta / text.delta / tool.call / tool.result
 *              / kb.reference / hitl.request / task.status / error / done
 */
export interface AgentEvent {
  /** 事件类型 */
  event: string;
  /** 事件数据（结构视事件类型而定） */
  data: unknown;
}

/** skill_creator 阶段事件载荷 */
export interface SkillCreatorStagePayload {
  stage: string;
  description: string;
  progress: number;
  skillId?: string;
}

/** skill_creator 调试事件载荷 */
export interface SkillCreatorDebugPayload {
  skillCode: string;
  success: boolean;
  message: string;
}

/** 技能文件项（打包事件中返回的文件结构） */
export interface SkillPackageFileItem {
  name: string;
  type: 'file' | 'folder';
  path: string;
  content?: string;
  children?: SkillPackageFileItem[];
}

/** skill_creator 打包事件载荷 */
export interface SkillCreatorPackagePayload {
  skillCode: string;
  fileName: string;
  size: number;
  success: boolean;
  files?: SkillPackageFileItem[];
}

/** 技能创建请求（对话创建） */
export interface SkillCreateDraftRequest {
  skillName: string;
  description?: string;
  instructions?: string;
  inputs?: string;
  outputs?: string;
  bindingTools?: string;
}

/** 技能草稿结果 */
export interface SkillDraftResult {
  success: boolean;
  message: string;
  skillId?: string;
  skillCode?: string;
}

/** 技能元数据响应 */
export interface SkillMetadataResponse {
  id: string;
  skillCode: string;
  skillName: string;
  description?: string;
  instructions?: string;
  inputs?: string;
  outputs?: string;
  bindingTools?: string;
  scope: string;
  version?: string;
  lifeStatus?: string;
  isSystem?: boolean;
}

/** 技能调试结果 */
export interface SkillDebugResult {
  success: boolean;
  message: string;
  /** 执行步骤（P0-ITEM-2：真实调试返回） */
  steps?: Array<{ name: string; status?: string; detail?: string }>;
  /** 执行输出 */
  output?: string;
  findings?: Array<{ level: string; message: string }>;
}

/** 技能打包结果 */
export interface SkillPackageResult {
  success: boolean;
  message: string;
  fileName?: string;
  size?: number;
}

/**
 * 思考步骤（用于思考过程时序展示）
 */
export interface ThinkingStep {
  /** 步骤序号 (1, 2, 3...) */
  stepIndex: number;
  /** 步骤标题 */
  stepTitle: string;
  /** 步骤详情（可选） */
  stepDetail?: string;
  /** 状态 */
  status: 'RUNNING' | 'SUCCESS' | 'FAILED';
  /** 耗时（毫秒） */
  durationMs?: number;
}

/**
 * 思考过程（时序化展示）
 */
export interface ThinkingProcess {
  /** 思考步骤列表 */
  steps: ThinkingStep[];
  /** 思考摘要（一句话总结） */
  summary?: string;
}

/**
 * 工具调用节点（用于时间线展示）
 */
export interface ToolCallNode {
  /** 调用唯一ID */
  id: string;
  /** 工具名称 */
  name: string;
  /** 触发原因说明 */
  triggerReason?: string;
  /** 调用状态 */
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  /** 调用参数 */
  arguments?: Record<string, unknown>;
  /** 调用结果 */
  result?: unknown;
  /** 耗时（毫秒） */
  durationMs?: number;
  /** 是否为并行调用 */
  isParallel?: boolean;
  /** 并行组ID（同一组的工具会并行执行） */
  groupId?: string;
}

/**
 * 执行统计（用于时间线底部总览）
 */
export interface ExecutionStats {
  /** 总耗时（毫秒） */
  totalDurationMs: number;
  /** 工具调用总数 */
  toolCount: number;
  /** 并行组数 */
  parallelGroups: number;
  /** 思考步骤数 */
  thinkingSteps: number;
}

/**
 * 资源引用（知识库+MCP服务）
 */
export interface ResourceRef {
  /** 知识库ID列表（使用字符串避免JavaScript精度丢失） */
  kbIds?: string[];
  /** MCP服务ID列表（使用字符串避免JavaScript精度丢失） */
  mcpIds?: string[];
}

/**
 * 会话（用于 API 返回）
 */
export interface ChatSession {
  /** 会话 ID */
  id: string;
  /** 会话 UUID */
  sessionId: string;
  /** 会话标题 */
  title?: string;
  /** 智能体 ID */
  agentId: string;
  /** 智能体名称 */
  agentName?: string;
  /** 会话状态 */
  status?: string;
  /** 消息数量 */
  messageCount?: number;
  /** 输入 Token */
  inputTokens?: number;
  /** 输出 Token */
  outputTokens?: number;
  /** 创建时间 */
  createdAt?: string;
  /** 最近活跃时间 */
  lastActiveAt?: string;
}

/**
 * 消息（用于 API 返回）
 */
export interface ChatMessage {
  /** 消息 ID */
  id: string;
  /** 会话 ID */
  sessionId: string;
  /** 角色 */
  role: MessageRole;
  /** 消息内容 */
  content: string;
  /** 消息状态 */
  status?: string;
  /** 附件列表 */
  attachments?: AttachmentRef[];
  /** 创建时间 */
  createdAt?: string;
}

/**
 * 附件引用（与后端 AttachmentRef 对齐）
 */
export interface AttachmentRef {
  /** 附件ID */
  id?: string;
  /** 附件文件ID（后端存储的文件标识） */
  fileId?: string;
  /** 附件名称（显示用） */
  name?: string;
  /** 文件名（完整文件名，包含扩展名） */
  fileName?: string;
  /** 文件大小（KB） */
  sizeKB?: number;
  /** 文件大小（字节数） */
  fileSize?: number;
  /** 内容类型（MIME） */
  contentType?: string;
  /** MIME 类型 */
  mimeType?: string;
  /** 下载 URL */
  downloadUrl?: string;
  /** 上传进度（0-100） */
  uploadProgress?: number;
  /** 是否正在上传 */
  uploading?: boolean;
  /** 原始 File 对象（前端使用） */
  file?: File;
}

/**
 * 对话请求体（与后端 ChatRequest 对齐）
 */
export interface ChatRequestBody {
  /** 智能体 ID */
  agentId: string;
  /** 会话 ID */
  sessionId?: string;
  /** 消息内容（regenerate/edit 时不传，由后端处理） */
  message?: string;
  /** 消息 ID（重新生成/编辑时指定目标消息，对应后端 DB messageId） */
  messageId?: number | string;
  /** 租户 ID（雪花ID，前端一律 string） */
  tenantId?: string;
  /** 用户 ID（雪花ID，前端一律 string） */
  userId?: string;
  /** 附件列表 */
  attachments?: AttachmentRef[];
  /** @SKILL 结构化引用 */
  skills?: SkillRef[];
  /** 会话级资源引用 */
  resources?: {
    /** 会话级引用的知识库ID列表（使用字符串避免JavaScript精度丢失） */
    kbIds?: string[];
    /** 会话级引用的MCP服务ID列表（使用字符串避免JavaScript精度丢失） */
    mcpIds?: string[];
  };
}

/**
 * 知识库资源（对齐后端 KbResourceItem）
 */
export interface KnowledgeBaseResource {
  /** 知识库 ID（使用字符串避免JavaScript精度丢失） */
  id: string;
  /** 知识库名称 */
  name: string;
  /** 知识库描述 */
  description?: string;
  /** 安全等级（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET） */
  securityLevel?: string;
  /** 是否已订阅 */
  subscribed?: boolean;
  /** 文档数量 */
  documentCount?: number;
  /** 创建者 */
  creator?: string;
  /** 创建时间 */
  createTime?: string;
  /** 标签/分类 */
  tags?: string[];
  /**
   * 是否允许引用该知识库。
   * false 表示安全等级限制（L3 需审批 / L4 拒绝），面板以禁用态展示并提示 blockReason
   */
  selectable?: boolean;
  /** 不可选原因（档位不匹配说明，selectable=false 时有值） */
  blockReason?: string;
  /**
   * 知识库生命周期状态（DRAFT/REVIEWING/PUBLISHED/ARCHIVED/REJECTED）。
   * 非 PUBLISHED 的知识库为当前用户自建（仅本人可见可引用），面板展示"未发布"标识
   */
  lifeStatus?: string;
  /** 是否当前用户创建（自建库标识） */
  owned?: boolean;
}

/**
 * MCP 服务资源（对齐后端 McpResourceItem）
 */
export interface McpServiceResource {
  /** MCP 服务 ID（使用字符串避免JavaScript精度丢失） */
  id: string;
  /** 服务名称 */
  name: string;
  /** 服务描述 */
  description?: string;
  /** 安全等级（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET） */
  securityLevel?: string;
  /** 工具数量 */
  toolCount?: number;
  /** 是否已订阅 */
  subscribed?: boolean;
  /** 订阅数 */
  subsCount?: number;
  /** 创建者 */
  creator?: string;
  /** 创建时间 */
  createTime?: string;
  /** 标签/分类 */
  tags?: string[];
}

/**
 * 可用资源（API 返回，对齐后端 AvailableResourcesResponse）
 */
export interface AvailableResource {
  /** 知识库列表 */
  kbs?: KnowledgeBaseResource[];
  /** MCP 服务列表 */
  mcps?: McpServiceResource[];
  /** 知识库总数 */
  totalKbCount?: number;
  /** MCP 服务总数 */
  totalMcpCount?: number;
}

/**
 * 智能体技能
 */
export interface AgentSkill {
  /** 技能编码 */
  skillCode: string;
  /** 技能名称 */
  skillName: string;
  /** 技能描述 */
  description?: string;
  /** 技能分类 */
  category?: string;
  /** 是否系统技能 */
  isSystem?: boolean;
  /** 版本号 */
  version?: string;
}