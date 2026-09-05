/**
 * @file 智能体类型定义
 * @description 智能体定义（对齐后端 AgentDef）、技能绑定、模型档位配置等类型。
 *              后端管理平面接口 {@code /api/admin/agent} 直接接收 / 返回 AgentDef。
 * @author wang.zhen
 * @since 1.0.0
 */
import type { AgentType, LifeStatus, ModelTier, GovernanceTier, Visibility } from './enum';
import type { QueryParams } from './api';
import type { AgentApiConfigParams } from './agentApi';

/**
 * 智能体定义（对齐后端 com.aegis.core.domain.agent.AgentDef）。
 *
 * 后端 {@code POST /api/admin/agent} 与 {@code GET /api/admin/agent/{id}} 直接使用此结构。
 * 智能体配置（systemPrompt / modelTier / temperature 等）位于 AgentConfig，
 * 详情接口在 AgentDef 顶层亦返回部分配置字段（详情态扩展）。
 */
export interface Agent {
  /** 智能体 ID（雪花ID，后端 Long 序列化为字符串，防止前端精度丢失） */
  id: string;
  /** 智能体编码，租户内唯一，创建后不可修改 */
  agentCode: string;
  /** 智能体名称 */
  agentName: string;
  /** 智能体类型：UNIVERSAL（通用）/ APPLICATION（应用）/ SYSTEM（系统） */
  agentType: AgentType;
  /** 图标 URL */
  icon?: string;
  /** 主题色（十六进制色值，前端展示用） */
  color?: string;
  /** 智能体描述 */
  description?: string;
  /** 智能体分类（市场检索用） */
  category?: string;
  /** 治理档位：STANDARD（标准）/ ENHANCED（增强）/ STRICT（严格），取代原安全级别 / 护栏级别 / 规划模式 */
  governanceTier?: GovernanceTier;
  /** 生命周期状态 */
  lifeStatus: LifeStatus;
  /** 当前版本号（每次发布递增，如 1.0.0） */
  version?: string;
  /** 所属租户 ID（雪花ID，字符串） */
  tenantId: string;
  /** 创建者用户 ID（雪花ID，前端一律 string） */
  authorUserId?: string;
  /** 创建者部门 ID（雪花ID，前端一律 string） */
  authorDeptId?: string;
  /** 订阅数（市场客观排序用缓存统计） */
  subsCount?: number;
  /** 当前用户是否已订阅（市场列表接口返回，B-3） */
  subscribed?: boolean;
  /** 发布可见范围：当前仅 TENANT（本租户可见） */
  visibility?: Visibility;
  /** 发布时间 */
  publishedTime?: string;
  /** 归档时间 */
  archivedTime?: string;
  /** 创建时间 */
  createTime?: string;
  /** 更新时间 */
  updateTime?: string;

  /* 以下为详情态扩展字段（对应后端 AgentConfig / AgentVO 详情接口） */
  /** 系统提示词（详情接口返回） */
  systemPrompt?: string;
  /** 模型档位：LIGHT / STANDARD / STRONG */
  modelTier?: ModelTier;
  /** 温度参数（0-2） */
  temperature?: number;
  /** 最大对话轮数 */
  maxTurns?: number;
  /** 记忆策略：SESSION_LEVEL / LONG_TERM */
  memoryStrategy?: string;
  /** 启用工具ID列表 JSON 数组字符串（详情接口返回，如 "[1,2,3]"） */
  enabledTools?: string;

  /* 系统智能体部署配置（仅 agentType=SYSTEM 时有值） */
  /** 部署目标：沙箱池编码（创建/编辑时提交） */
  deploymentPoolCode?: string;
  /** 预留副本数（创建/编辑时提交） */
  reservedReplicas?: number;

  /** 资源绑定列表（详情接口返回） */
  bindings?: AgentBindingVO[];
  /** 驳回原因（REJECTED 状态时返回） */
  rejectReason?: string;
}

/** 资源绑定视图（对齐后端 AgentVO.BindingVO） */
export interface AgentBindingVO {
  /** 绑定ID（雪花ID，前端一律 string） */
  id?: string;
  /** 资源类型：SKILL / KNOWLEDGE_BASE / MCP / TOOL / DATASET */
  resourceType: string;
  /** 资源ID（雪花ID，前端一律 string） */
  resourceId: string;
  /** 资源版本 */
  resourceVersion?: string;
  /** 绑定类型：FIXED / DYNAMIC */
  bindingType?: string;
  /** 是否启用 */
  enabled?: boolean;
}

/** 资源绑定请求（创建时提交，对齐后端 AgentCreateRequest.BindingRequest） */
export interface AgentBindingRequest {
  /** 资源类型：SKILL / KNOWLEDGE_BASE / MCP / TOOL / DATASET */
  resourceType: string;
  /** 资源ID（雪花ID，前端一律 string） */
  resourceId: string;
  /** 资源版本，固定绑定为具体版本号，动态绑定为 latest */
  resourceVersion?: string;
  /** 绑定类型：FIXED / DYNAMIC */
  bindingType?: string;
  /** 是否启用 */
  enabled?: boolean;
}

/**
 * 智能体创建 / 更新参数（对应后端 AgentDef 主体字段）。
 *
 * 创建时后端 {@code POST /api/admin/agent} 接收 AgentDef，
 * tenantId / authorUserId 由后端从请求头补全，前端可不传。
 */
export interface AgentSaveParams {
  /** 智能体编码（创建时必填，租户内唯一） */
  agentCode: string;
  /** 智能体名称 */
  agentName: string;
  /** 智能体类型 */
  agentType: AgentType;
  /** 图标 URL */
  icon?: string;
  /** 主题色 */
  color?: string;
  /** 描述 */
  description?: string;
  /** 分类 */
  category?: string;
  /** 治理档位：STANDARD / ENHANCED / STRICT（取代原安全级别 / 护栏级别 / 规划模式） */
  governanceTier?: GovernanceTier;
  /** 系统提示词（写入 AgentConfig） */
  systemPrompt?: string;
  /** 模型档位 */
  modelTier?: ModelTier;
  /** 温度参数 */
  temperature?: number;
  /** 最大对话轮数 */
  maxTurns?: number;
  /** 记忆策略 */
  memoryStrategy?: string;
  /** 启用工具ID列表（雪花ID，前端一律 string[]） */
  enabledTools?: string[];
  /** 资源绑定列表（创建时绑定 SKILL/MCP/知识库） */
  bindings?: AgentBindingRequest[];

  /* 以下为系统智能体（SYSTEM）部署配置，仅 agentType=SYSTEM 时传递 */
  /** 部署目标：沙箱池编码（系统智能体常驻运行所需的沙箱资源池） */
  deploymentPoolCode?: string;
  /** 预留副本数（最小常驻实例数，默认 1） */
  reservedReplicas?: number;
  /** API 发布配置（系统智能体专属，包含 Schema/鉴权/限流等） */
  apiConfig?: AgentApiConfigParams;
}

/** 智能体查询参数 */
export interface AgentQueryParams extends QueryParams {
  /** 智能体类型 */
  agentType?: AgentType;
  /** 治理档位（取代原安全级别过滤） */
  governanceTier?: GovernanceTier;
  /** 生命周期状态 */
  lifeStatus?: LifeStatus;
  /** 可见范围（市场过滤） */
  visibility?: Visibility;
  /** 标签 */
  tags?: string[];
}

/** 技能绑定项 */
export interface SkillBinding {
  /** 技能 ID（雪花ID，前端一律 string） */
  skillId: string;
  /** 技能名称 */
  skillName: string;
  /** 绑定参数（覆盖默认入参） */
  params?: Record<string, unknown>;
  /** 是否启用 */
  enabled: boolean;
}
