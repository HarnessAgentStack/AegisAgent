/**
 * @file 模型管理 - 共享常量与类型定义
 * @description 选项常量、状态映射、行/表单接口类型，供主容器及各 Tab 子组件共用
 * @author wang.zhen
 * @since 1.0.0
 */
import { ModelTier } from '@/types/enum';

/** 模型档位 ->Tag 颜色 */
export const TIER_TAG: Record<ModelTier, { color: string; text: string }> = {
  [ModelTier.LIGHT]: { color: 'blue', text: '轻量' },
  [ModelTier.STANDARD]: { color: 'cyan', text: '标准' },
  [ModelTier.STRONG]: { color: 'purple', text: '高性能' },
};

/** 模型档位选项 */
export const MODEL_TIER_OPTIONS = [
  { value: ModelTier.LIGHT, label: '轻量' },
  { value: ModelTier.STANDARD, label: '标准' },
  { value: ModelTier.STRONG, label: '高性能' },
];

/** 作用域选项 */
export const SCOPE_OPTIONS = [
  { value: 'PLATFORM', label: '全平台' },
  { value: 'DEPT', label: '部门' },
  { value: 'USER', label: '个人' },
];

/** 作用域 ->Tag 颜色 / 文案 */
export const SCOPE_TAG: Record<string, { color: string; text: string }> = {
  PLATFORM: { color: 'purple', text: '全平台' },
  DEPT: { color: 'blue', text: '部门' },
  USER: { color: 'cyan', text: '个人' },
};

/** 模型类型选项（与后端 ModelType 枚举一致） */
export const MODEL_TYPE_OPTIONS = [
  { value: 'TEXT', label: '纯文本对话' },
  { value: 'MULTIMODAL', label: '多模态' },
  { value: 'EMBEDDING', label: '向量嵌入' },
  { value: 'VISION', label: '视觉理解' },
];

/** 模型类型 Tag 颜色 */
export const MODEL_TYPE_TAG: Record<string, { color: string; text: string }> = {
  TEXT: { color: 'blue', text: '纯文本' },
  MULTIMODAL: { color: 'purple', text: '多模态' },
  EMBEDDING: { color: 'green', text: '向量嵌入' },
  VISION: { color: 'orange', text: '视觉理解' },
};

/** 供应商状态选项 */
export const PROVIDER_STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '已接入' },
  { value: 'PENDING', label: '待接入' },
];

/** 限流动作选项 */
export const RATE_LIMIT_ACTION_OPTIONS = [
  { value: 'ALERT', label: '告警' },
  { value: 'LIMIT', label: '限流' },
  { value: 'PASS', label: '放行' },
];

/** 限流动作 ->Tag 颜色 / 文案 */
export const RATE_LIMIT_ACTION_TAG: Record<string, { color: string; text: string }> = {
  ALERT: { color: 'orange', text: '告警' },
  LIMIT: { color: 'red', text: '限流' },
  PASS: { color: 'green', text: '放行' },
};

// ===== 行类型 =====

/** 供应商行 */
export interface ProviderRow {
  id: string;
  providerCode: string;
  providerName: string;
  status: 'ACTIVE' | 'PENDING';
  endpoint: string;
  modelCount: number;
  apiKeyMasked?: string;
}

/** 模型实例行 */
export interface ModelInstanceRow {
  id: string;
  modelCode: string;
  modelName: string;
  modelType?: string;
  tier: ModelTier;
  providerId: string;
  providerName: string;
  contextWindow: number;
  status: 'ENABLED' | 'DISABLED';
  capabilities?: ModelCapabilities;
}

/** 模型能力矩阵 */
export interface ModelCapabilities {
  multimodal?: {
    supported: boolean;
    imageTypes?: string[];
    maxImageSizeKb?: number;
    maxImagesPerRequest?: number;
  };
  document?: {
    supported: boolean;
    docTypes?: string[];
    maxDocSizeKb?: number;
  };
  visionDescription?: {
    supported: boolean;
    description?: string;
  };
  contextWindow?: number;
  maxOutputTokens?: number;
  supportsFunctionCalling?: boolean;
  supportsJsonMode?: boolean;
}

/** 限流配置行 */
export interface RateLimitRow {
  id: string;
  scope: 'PLATFORM' | 'DEPT' | 'USER';
  scopeTargetId: string;
  scopeTargetName: string;
  lightQps: number;
  standardQps: number;
  strongQps: number;
  totalQps: number;
  action: 'ALERT' | 'LIMIT' | 'PASS';
}

// ===== 表单类型 =====

/** 供应商表单 */
export interface ProviderForm {
  providerCode: string;
  providerName: string;
  endpoint: string;
  apiKey: string;
  status: 'ACTIVE' | 'PENDING';
}

/** 模型实例表单 */
export interface ModelInstanceForm {
  modelCode: string;
  modelName: string;
  modelType?: string;
  tier: ModelTier;
  providerId: string;
  contextWindow: number;
  // 能力矩阵字段
  multimodalSupported?: boolean;
  multimodalImageTypes?: string;
  multimodalMaxSize?: number;
  multimodalMaxCount?: number;
  documentSupported?: boolean;
  documentTypes?: string;
  documentMaxSize?: number;
  visionSupported?: boolean;
  visionDescription?: string;
  maxOutputTokens?: number;
  supportsFunctionCalling?: boolean;
  supportsJsonMode?: boolean;
}

/** 限流配置表单 */
export interface RateLimitForm {
  scope: RateLimitRow['scope'];
  scopeTargetId: string;
  lightQps: number;
  standardQps: number;
  strongQps: number;
  totalQps: number;
  action: RateLimitRow['action'];
}
