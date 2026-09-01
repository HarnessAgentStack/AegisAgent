/**
 * @file 资源类型定义
 * @description 技能、知识库、MCP、工具等资源类型（对应后端 /admin/resource 管理端接口）
 * @author wang.zhen
 * @since 1.0.0
 */
import type { LifeStatus, SecurityLevel } from './enum';

/** 资源基础字段（业务卡片组件使用） */
export interface ResourceBase {
  /** 资源 ID（雪花ID，前端一律 string） */
  id: string;
  /** 资源名称 */
  name: string;
  /** 资源类型 */
  resourceType?: string;
  /** 资源描述 */
  description?: string;
  /** 安全级别 */
  securityLevel: SecurityLevel;
  /** 生命周期状态 */
  lifeStatus?: LifeStatus;
  /** 版本号 */
  version?: string;
}

/** 知识库（Knowledge Base） */
export interface KnowledgeBase {
  id?: string;
  kbCode: string;
  kbName: string;
  icon?: string;
  description?: string;
  securityLevel: SecurityLevel;
  lifeStatus?: LifeStatus;
  version?: string;
  authorUserId?: string;
  authorDeptId?: string;
  docCount?: number;
  chunkStrategy?: string;
  chunkSize?: number;
  chunkOverlap?: number;
  embeddingModel?: string;
  retrievalStrategy?: string;
  topK?: number;
  similarityThreshold?: number;
  enableRerank?: boolean;
  enableQueryRewrite?: boolean;
  subsCount?: number;
  visibility?: string;
  createdAt?: string;
  updatedAt?: string;
}
export interface KbDocument {
  id: string;
  kbId?: string;
  fileName: string;
  fileType?: string;
  fileSize: number;
  objectKey?: string;
  status: 'PENDING' | 'SCANNING' | 'CHUNKING' | 'CHUNKED' | 'FAILED';
  chunkCount?: number;
  uploadedAt?: string;
}

/** 知识库文档切片 */
export interface KbChunk {
  id: string;
  docId: string;
  chunkIndex: number;
  content: string;
  tokenCount: number;
  charCount: number;
  metadata?: string;
}

/** 预签名上传响应 */
export interface UploadApplyResult {
  uploadUrl: string;
  objectKey: string;
}

/** 技能（Skill） */
export interface Skill {
  id?: string;
  skillCode: string;
  skillName: string;
  icon?: string;
  description?: string;
  skillType: 'ATOMIC' | 'COMPOSITE';
  category?: string;
  tags?: string;
  securityLevel: SecurityLevel;
  lifeStatus?: LifeStatus;
  version?: string;
  authorUserId?: string;
  inputs?: string;
  outputs?: string;
  bindingTools?: string;
  mappingConfig?: string;
  /** 执行配置（P1-ITEM-4）：模型档位/温度/maxTurns/安全护栏等运行时参数，JSON 字符串 */
  execConfig?: string;
  subsCount?: number;
  visibility?: string;
  scope?: 'GLOBAL' | 'LOCAL';
  isSystem?: boolean;
  instructions?: string;
  /** 当前生效版本（指针式发布） */
  activeVersion?: string;
  /** 灰度版本（NULL 表示无灰度） */
  canaryVersion?: string;
  /** 灰度发布百分比（1-100），NULL 或 0 表示无灰度。P2-ITEM-6 新增。 */
  canaryPercent?: number;
  healthStatus?: string;
  lastHealthCheckAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

/** MCP 协议类型 */
export type McpProtocol = 'SSE' | 'STDIO' | 'STREAMABLE_HTTP';

/** MCP 鉴权类型 */
export type McpAuthType = 'NONE' | 'BEARER' | 'BASIC' | 'APIKEY';

/** MCP 服务行 */
export interface McpServer {
  id: string;
  mcpCode: string;
  mcpName: string;
  provider?: string;
  protocol: McpProtocol;
  endpoint: string;
  securityLevel: SecurityLevel;
  /** 服务接入状态：ACTIVE（已接入）/ PENDING（待接入） */
  status: 'ACTIVE' | 'PENDING';
  toolCount: number;
  /** 生命周期状态：DRAFT / REVIEWING / PUBLISHED / ARCHIVED / REJECTED */
  lifeStatus: LifeStatus;
  authType?: McpAuthType;
  authConfig?: string;
  /** 订阅数 */
  subsCount?: number;
  /** 当前用户是否已订阅（运行时计算） */
  subscribed?: boolean;
  /** 版本号 */
  version?: string;
  /** 服务描述 */
  description?: string;
  /** 最近发布时间 */
  publishedTime?: string;
  /** 创建时间 */
  createTime?: string;
}

/** 工具行 */
export interface Tool {
  id: string;
  toolCode: string;
  toolName: string;
  toolType: 'BUILTIN' | 'CUSTOM' | 'MCP_BOUND' | 'SKILL_BOUND';
  sourceType: 'SYSTEM' | 'USER' | 'MCP' | 'SKILL';
  securityLevel: SecurityLevel;
  lifeStatus: LifeStatus;
  status: 'ACTIVE' | 'INACTIVE' | 'DEPRECATED';
  signature?: string;
  description?: string;
  requireApproval?: boolean;
  inputSchema?: string;
  outputSchema?: string;
  sourceRef?: string;
  createdAt: string;
}

/** MCP 工具视图对象（用于动态展示 MCP 服务暴露的工具） */
export interface ToolVO {
  id?: string;
  toolCode: string;
  toolName: string;
  description?: string;
  toolType?: string;
  sourceType?: string;
  readOnly?: boolean;
  inputSchema?: string;
  outputSchema?: string;
  securityLevel?: SecurityLevel;
  status?: string;
}

/** 技能版本 */
/** 技能版本快照（P1-ITEM-1：真实快照字段） */
export interface SkillVersion {
  id: string;
  version: string;
  skillName?: string;
  description?: string;
  category?: string;
  tags?: string;
  securityLevel?: string;
  /** 是否为当前生效版本 */
  isActive?: boolean;
  /** 是否为灰度版本 */
  isCanary?: boolean;
  /** 是否系统技能 */
  isSystem?: number;
  /** 创建人ID（雪花ID，前端一律 string） */
  createBy?: string;
  /** 创建时间 */
  createTime?: string;
  /** 老数据兼容：仅有指针无快照 */
  isPointerOnly?: boolean;
}

/** 技能版本差异（P1-ITEM-1：字段级 diff） */
export interface SkillVersionDiff {
  versionA: string;
  versionB: string;
  fields: Record<string, {
    changed: boolean;
    from: unknown;
    to: unknown;
  }>;
}

/** 已订阅技能 */
export interface SubscribedSkill extends Skill {
  subscribedAt?: string;
}

/** 资源查询参数（管理端分页查询） */
export interface ResourceQueryParams {
  /** 页码（从 1 开始） */
  page?: number;
  /** 每页条数（管理端使用 size） */
  size?: number;
  /** 搜索关键词 */
  keyword?: string;
  /** 查询范围：market 市场 / mine 我的 */
  scope?: 'market' | 'mine';
  /** 安全级别筛选 */
  securityLevel?: SecurityLevel;
  /** 技能类型筛选 */
  type?: string;
}
