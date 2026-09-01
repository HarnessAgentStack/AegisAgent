/**
 * @file 枚举类型定义
 * @description 平台通用枚举：安全级别、生命周期状态、对象可见范围、智能体类型、资源类型等。
 *              所有枚举均为字符串型，与后端 Java 枚举（@JsonValue 序列化为 name）保持一致。
 * @author wang.zhen
 * @since 1.0.0
 */

/**
 * 安全级别（从低到高）。
 * 对应后端 {@code com.aegis.core.enums.SecurityLevel}，决定沙箱分配与工具管控策略。
 */
export enum SecurityLevel {
  /** L1 公开级：通用问答，通用沙箱池，白名单 MCP 出站 */
  L1 = 'L1',
  /** L2 内部级：内部文档，标准沙箱池，白名单 + 工具出站 */
  L2 = 'L2',
  /** L3 机密级：涉密业务，隔离沙箱池，严格出站 + 审计 */
  L3 = 'L3',
  /** L4 绝密级：核心涉密，无外网沙箱，禁止出站，全程加密 */
  L4 = 'L4',
}

/**
 * 智能体生命周期状态。
 * 对应后端 {@code com.aegis.core.enums.AgentLifeStatus}。
 *
 * 状态流转：DRAFT → REVIEWING → PUBLISHED → ARCHIVED；
 * REVIEWING → REJECTED（审核驳回，可修改后重新提交）。
 */
export enum LifeStatus {
  /** 草稿：创建初始态，可编辑，仅创建者可见 */
  DRAFT = 'DRAFT',
  /** 审核中：已提交审核，主体字段冻结 */
  REVIEWING = 'REVIEWING',
  /** 已发布：进入智能体市场，可被订阅 */
  PUBLISHED = 'PUBLISHED',
  /** 已归档：主动下架，历史会话只读 */
  ARCHIVED = 'ARCHIVED',
  /** 已拒绝：审核驳回，可修改后重新提交 */
  REJECTED = 'REJECTED',
  /** 已激活：个人智能体激活后可用，仅创建者可见 */
  ACTIVE = 'ACTIVE',
}

/**
 * 资源发布可见范围。
 * 对应后端 {@code com.aegis.core.enums.Visibility}。
 *
 * 租户隔离原则：所有资源仅限本租户内发布和订阅，禁止跨租户发布。
 */
export enum Visibility {
  /** 本租户可见：仅当前租户用户可订阅（默认且唯一可用值） */
  TENANT = 'TENANT',
}

/**
 * 智能体类型。对应后端 {@code com.aegis.core.enums.AgentType}。
 */
export enum AgentType {
  /** 通用智能体：平台唯一，默认所有用户可用，按用户动态加载资源 */
  UNIVERSAL = 'UNIVERSAL',
  /** 应用智能体：用户创建，固定绑定资源，不可发布 API */
  APPLICATION = 'APPLICATION',
  /** 系统智能体：面向业务系统发布，常驻 K8S POD，可发布 API、支持系统回调与指定输出格式 */
  SYSTEM = 'SYSTEM',
}

/** 资源类型 */
export enum ResourceType {
  /** 技能 */
  SKILL = 'SKILL',
  /** 知识库 */
  KNOWLEDGE_BASE = 'KNOWLEDGE_BASE',
  /** MCP 服务 */
  MCP = 'MCP',
  /** 工具 */
  TOOL = 'TOOL',
  /** 数据集 */
  DATASET = 'DATASET',
}

/**
 * 模型档位。对应后端 AgentConfig.modelTier 字段。
 */
export enum ModelTier {
  /** 轻量 */
  LIGHT = 'LIGHT',
  /** 标准 */
  STANDARD = 'STANDARD',
  /** 高性能 */
  STRONG = 'STRONG',
}

/** 模型供应商 */
export enum ModelProvider {
  OPENAI = 'openai',
  ANTHROPIC = 'anthropic',
  AZURE = 'azure',
  DOUBAO = 'doubao',
  QWEN = 'qwen',
  DEEPSEEK = 'deepseek',
  CUSTOM = 'custom',
}

/** 会话消息角色 */
export enum MessageRole {
  USER = 'user',
  ASSISTANT = 'assistant',
  SYSTEM = 'system',
  TOOL = 'tool',
}

/** 任务状态 */
export enum TaskStatus {
  PENDING = 'pending',
  RUNNING = 'running',
  AWAITING_APPROVAL = 'awaiting_approval',
  SUCCESS = 'success',
  FAILED = 'failed',
  CANCELED = 'canceled',
}

/** HITL 审批状态 */
export enum HitlStatus {
  PENDING = 'pending',
  APPROVED = 'approved',
  REJECTED = 'rejected',
}

/** 租户状态（对齐后端 TenantStatus 枚举） */
export enum TenantStatus {
  NORMAL = 'NORMAL',
  FROZEN = 'FROZEN',
}

/** 用户状态（对齐后端 CommonStatus 枚举） */
export enum UserStatus {
  NORMAL = 'NORMAL',
  DISABLED = 'DISABLED',
}

/** 使用场景 */
export enum UsageScenario {
  /** 个人使用：创建后立即可用，仅自己可见 */
  PERSONAL = 'PERSONAL',
  /** 共享发布：需审核通过后发布，供他人订阅使用 */
  SHARED = 'SHARED',
}

/**
 * 治理档位（单一判别器）。
 * 对应后端 {@code com.aegis.core.enums.agent.GovernanceTier}，取代原先分散的
 * 安全级别 / 护栏级别 / 规划模式 三个开关，统一驱动沙箱隔离、工具管控、内容过滤、人审与审计粒度。
 */
export enum GovernanceTier {
  /** 标准：默认档位，常规沙箱与工具管控 */
  STANDARD = 'STANDARD',
  /** 增强：加强内容过滤与工具审批 */
  ENHANCED = 'ENHANCED',
  /** 严格：隔离沙箱、强管控、强制人审与全量审计 */
  STRICT = 'STRICT',
}

/** 审核状态 */
export enum ReviewStatus {
  PENDING = 'PENDING',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED',
}

/** 技能作用域枚举 */
export enum SkillScope {
  GLOBAL = 'GLOBAL',
  LOCAL = 'LOCAL',
}

/** 技能作用域标签映射 */
export const SKILL_SCOPE_LABEL: Record<SkillScope, string> = {
  [SkillScope.GLOBAL]: '全局',
  [SkillScope.LOCAL]: '局部',
};
