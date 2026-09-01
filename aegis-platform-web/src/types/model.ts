/**
 * @file 模型类型定义
 * @description 模型供应商、模型实例、模型档位、路由策略等类型
 * @author wang.zhen
 * @since 1.0.0
 */
import type { ModelProvider, ModelTier } from './enum';
import type { QueryParams } from './api';

/** 模型实例 */
export interface ModelInfo {
  /** 模型 ID（雪花ID，前端一律 string） */
  id: string;
  /** 模型名称 */
  modelName: string;
  /** 显示名称 */
  displayName?: string;
  /** 供应商 */
  provider: ModelProvider;
  /** 模型档位 */
  tier: ModelTier;
  /** 上下文窗口长度 */
  contextLength?: number;
  /** 最大输出长度 */
  maxOutput?: number;
  /** 是否支持流式 */
  supportStream: boolean;
  /** 是否支持函数调用 */
  supportFunctionCall: boolean;
  /** 是否支持视觉 */
  supportVision?: boolean;
  /** 输入单价（元 / 千 Token） */
  inputPrice?: number;
  /** 输出单价（元 / 千 Token） */
  outputPrice?: number;
  /** 是否启用 */
  enabled: boolean;
  /** 租户 ID（雪花ID，前端一律 string） */
  tenantId?: string;
}

/** 模型档位配置 */
export interface ModelTierConfig {
  /** 档位 */
  tier: ModelTier;
  /** 档位名称 */
  tierName: string;
  /** 默认模型 ID（雪花ID，前端一律 string） */
  defaultModelId: string;
  /** 候选模型 ID 列表 */
  candidateModelIds?: string[];
  /** 路由策略：priority 优先 / loadbalance 负载均衡 / fallback 降级 */
  routingStrategy?: 'priority' | 'loadbalance' | 'fallback';
  /** 限流 QPS */
  rateLimit?: number;
}

/** 模型供应商配置 */
export interface ModelProviderConfig {
  /** 供应商 */
  provider: ModelProvider;
  /** API Base URL */
  baseUrl: string;
  /** API Key（脱敏） */
  apiKeyMasked?: string;
  /** 是否启用 */
  enabled: boolean;
}

/** 模型查询参数 */
export interface ModelQueryParams extends QueryParams {
  /** 供应商 */
  provider?: ModelProvider;
  /** 档位 */
  tier?: ModelTier;
  /** 是否启用 */
  enabled?: boolean;
}