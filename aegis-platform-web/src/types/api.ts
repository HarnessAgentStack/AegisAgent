/**
 * @file API 通用响应类型
 * @description 统一返回结构 Result、分页请求 / 响应类型、列表查询基础参数
 * @author wang.zhen
 * @since 1.0.0
 */

/** 业务码：0 表示成功，非 0 表示业务错误 */
export type BizCode = number;

/**
 * 资源唯一标识。
 * 后端雪花 ID 为 64 位 long，超过 JS Number.MAX_SAFE_INTEGER，
 * 因此前端一律以 string 传递、比较、存储，禁止 Number() 转换。
 * 响应拦截器的 ID 归一化层保证 API 返回的 ID 字段必为 string。
 */
export type ResourceId = string;

/** 统一响应结构 */
export interface Result<T = unknown> {
  /** 业务码 */
  code: BizCode;
  /** 提示信息 */
  message: string;
  /** 业务数据 */
  data: T;
  /** 请求追踪 ID */
  requestId?: string;
  /** 服务端时间戳 */
  timestamp?: number;
}

/** 分页请求参数 */
export interface PageRequest {
  /** 页码（从 1 开始） */
  page?: number;
  /** 每页条数 */
  pageSize?: number;
  /** 排序字段 */
  sortField?: string;
  /** 排序方向：asc / desc */
  sortOrder?: 'asc' | 'desc';
}

/** 分页响应结果 */
export interface PageResult<T> {
  /** 数据列表 */
  list: T[];
  /** 总条数 */
  total: number;
  /** 当前页码 */
  page: number;
  /** 每页条数 */
  pageSize: number;
}

/** 列表查询基础参数（含关键词与分页） */
export interface QueryParams extends PageRequest {
  /** 搜索关键词 */
  keyword?: string;
}