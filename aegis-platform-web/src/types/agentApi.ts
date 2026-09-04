/**
 * @file 系统智能体 API 发布管理类型定义
 * @description 对齐后端 AgentApi 实体扩展字段，覆盖 API 配置、Schema 定义等。
 * @author aegis
 * @since 2.0.0
 */

/** API 鉴权类型 */
export enum ApiAuthType {
  API_KEY = 'API_KEY',
  BEARER = 'BEARER',
  OAUTH2 = 'OAUTH2',
  BASIC = 'BASIC',
  NONE = 'NONE',
}

/** API 响应模式 */
export enum ApiResponseMode {
  SYNC = 'SYNC',
  ASYNC = 'ASYNC',
  SSE = 'SSE',
}

/** 密钥有效期类型 */
export enum ApiValidityType {
  PERMANENT = 'PERMANENT',
  DAYS_7 = 'DAYS_7',
  DAYS_30 = 'DAYS_30',
  CUSTOM = 'CUSTOM',
}

/** API 状态 */
enum ApiStatus {
  NORMAL = 'NORMAL',
  DISABLED = 'DISABLED',
}

/** Bearer Token 管理模式 */
export enum BearerTokenMode {
  STATIC = 'STATIC',
  PASSTHROUGH = 'PASSTHROUGH',
}

/** JWT 签名算法 */
export enum JwtAlgorithm {
  HS256 = 'HS256',
  HS384 = 'HS384',
  HS512 = 'HS512',
  RS256 = 'RS256',
  RS384 = 'RS384',
  RS512 = 'RS512',
  ES256 = 'ES256',
}

/** API 配置（对齐后端 AgentApi） */
export interface AgentApiConfig {
  id: string;
  agentId: string;
  apiName: string;
  apiPath: string;
  httpMethod: 'GET' | 'POST';
  version: string;
  authType: ApiAuthType;
  responseMode: ApiResponseMode;
  timeout: number;
  rateLimit: number;
  concurrentLimit: number;
  validityType: ApiValidityType;
  validUntil?: string;
  requestSchema?: string;
  responseSchema?: string;
  exampleRequest?: string;
  exampleResponse?: string;
  status: ApiStatus;
  createTime?: string;
  updateTime?: string;
  bearerTokenMode?: BearerTokenMode;
  bearerTokenValue?: string;
  bearerJwtSecret?: string;
  bearerJwtAlgorithm?: JwtAlgorithm;
  bearerIntrospectionUrl?: string;
  bearerPassThrough?: boolean;
}

/** 创建/更新 API 配置参数 */
export interface AgentApiConfigParams {
  apiName?: string;
  apiPath?: string;
  httpMethod?: 'GET' | 'POST';
  version?: string;
  authType?: ApiAuthType;
  responseMode?: ApiResponseMode;
  timeout?: number;
  rateLimit?: number;
  concurrentLimit?: number;
  validityType?: ApiValidityType;
  validUntil?: string;
  requestSchema?: string;
  responseSchema?: string;
  exampleRequest?: string;
  exampleResponse?: string;
  bearerTokenMode?: BearerTokenMode;
  bearerTokenValue?: string;
  bearerJwtSecret?: string;
  bearerJwtAlgorithm?: JwtAlgorithm;
  bearerIntrospectionUrl?: string;
  bearerPassThrough?: boolean;
}

/** API Key 信息 */
export interface AgentApiKeyInfo {
  id: string;
  agentId: string;
  apiId: string;
  keyLabel: string;
  keyPreview?: string;
  status: 'ACTIVE' | 'REVOKED' | 'EXPIRED';
  expiresAt?: string;
  lastUsedAt?: string;
  rotateFrom?: string;
  createTime?: string;
}

/** OpenAPI 规范 */
export interface OpenApiSpec {
  openapi: string;
  info: { title: string; version: string; description?: string };
  servers?: Array<{ url: string; description?: string }>;
  paths: Record<string, unknown>;
  components?: Record<string, unknown>;
}

/** API 调用响应 */
export interface AgentApiInvokeResponse {
  requestId: string;
  agentId: string;
  sessionId: string;
  answer: string;
  usage?: Record<string, unknown>;
  latencyMs: number;
  status: string;
  errorMessage?: string;
}

/** API 错误码定义 */
export interface ApiErrorCode {
  httpStatus: string;
  code: string;
  message: string;
  description: string;
}

/** API 版本信息 */
export interface AgentApiVersionInfo {
  version: string;
  apiName: string;
  apiPath: string;
  status: string;
  lastTestedAt?: string;
  concurrentLimit?: number;
  rateLimit?: number;
}

